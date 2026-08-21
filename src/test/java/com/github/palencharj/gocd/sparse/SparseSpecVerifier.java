/*
 * Copyright 2026 John Palenchar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.palencharj.gocd.sparse;

import com.github.palencharj.gocd.sparse.config.MaterialConfiguration;
import com.github.palencharj.gocd.sparse.git.Git;
import com.github.palencharj.gocd.sparse.git.Revision;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Checks a real set of sparse specifications against the real repositories they name.
 *
 * <p>This exists because {@code checkout} is the one SCM request that runs on a build agent
 * rather than on the server, so it is the one part of this plugin that no amount of
 * server-side verification exercises. With no agent available, running the same code here
 * is the closest honest substitute: it drives {@link Git#checkout(String)} itself, not a
 * reimplementation of it, so what it proves is what an agent would do.
 *
 * <p>What it answers, per specification:
 * <ul>
 *   <li>Does the checkout succeed at all against the live repository and branch?</li>
 *   <li>Which configured patterns match <b>nothing</b>? That is the interesting failure --
 *       a path that was renamed or misspelled costs a build its files, and git reports it
 *       as no error whatsoever. A working directory that is quietly missing a header is far
 *       harder to diagnose than a checkout that fails outright.</li>
 *   <li>How much is actually checked out, against the size of the whole tree -- the number
 *       that says whether any of this was worth doing.</li>
 * </ul>
 *
 * <p>Specifications that share a repository and branch reuse one working directory, which
 * also exercises the re-application path: patterns are narrowed and widened in place over
 * and over, the way an agent's directory is reused between builds.
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew verifySparseSpecs -Pspecs=/path/to/specs.json -Pwork=/path/to/scratch
 * </pre>
 * Input is a JSON array of {@code {name, ssh_url, branch, paths[]}}. An {@code ssh_url} is
 * used deliberately: this plugin disables every git credential helper on purpose, so an
 * https remote would have no credential to authenticate with outside GoCD, whereas an ssh
 * remote uses the operator's existing key.
 */
public final class SparseSpecVerifier {

    private static final Gson GSON = new Gson();

    private SparseSpecVerifier() {
    }

    /** One specification, as exported from a migration plan. */
    private static final class Spec {
        String name;
        String url;
        String ssh_url;
        String branch;
        List<String> paths;

        String remote() {
            return ssh_url != null && !ssh_url.isBlank() ? ssh_url : url;
        }

        String repoAndBranch() {
            return remote() + "#" + branch;
        }
    }

    /** What verifying one specification found. */
    private static final class Result {
        String name;
        String repo;
        String branch;
        boolean checkedOut;
        String failure;
        int filesCheckedOut;
        int filesInWholeTree;
        List<String> patternsMatchingNothing = new ArrayList<>();
        int patterns;
    }

    public static void main(String[] args) throws Exception {
        Path specsFile = Path.of(required("specs", args));
        Path workRoot = Path.of(required("work", args));
        Files.createDirectories(workRoot);

        List<Spec> specs = GSON.fromJson(Files.readString(specsFile, StandardCharsets.UTF_8),
                new TypeToken<List<Spec>>() { }.getType());

        // Group by repository and branch so each one is cloned once. Reusing the directory
        // across that group is not just an optimisation: it is how an agent behaves, and it
        // puts the pattern re-application path under test at the same time.
        Map<String, List<Spec>> grouped = new LinkedHashMap<>();
        for (Spec spec : specs) {
            grouped.computeIfAbsent(spec.repoAndBranch(), key -> new ArrayList<>()).add(spec);
        }

        System.out.printf("Verifying %d specification(s) across %d repository/branch pair(s)%n%n",
                specs.size(), grouped.size());

        List<Result> results = new ArrayList<>();
        int groupIndex = 0;
        for (Map.Entry<String, List<Spec>> group : grouped.entrySet()) {
            groupIndex++;
            Spec first = group.getValue().get(0);
            File directory = workRoot.resolve("repo-" + groupIndex).toFile();
            System.out.printf("[%d/%d] %s (%s) -- %d spec(s)%n", groupIndex, grouped.size(),
                    shortName(first.remote()), first.branch, group.getValue().size());

            Branch branch;
            try {
                branch = inspect(workRoot.resolve("poll-" + groupIndex).toFile(), first);
            } catch (RuntimeException e) {
                for (Spec spec : group.getValue()) {
                    results.add(failed(spec, "could not reach the repository: " + oneLine(e)));
                }
                System.out.printf("        UNREACHABLE: %s%n", oneLine(e));
                continue;
            }
            System.out.printf("        head %s, %d file(s) in the whole tree%n",
                    branch.sha.substring(0, 8), branch.paths.size());

            for (Spec spec : group.getValue()) {
                Result result = verify(directory, spec, branch);
                results.add(result);
                System.out.printf("        %-36s %s%n", spec.name, describe(result));
            }
        }

        report(results);
        Files.writeString(workRoot.resolve("verification-report.json"),
                GSON.toJson(results), StandardCharsets.UTF_8);
        System.out.printf("%nfull report: %s%n", workRoot.resolve("verification-report.json"));

        long broken = results.stream().filter(r -> !r.checkedOut
                || !r.patternsMatchingNothing.isEmpty()).count();
        System.exit(broken == 0 ? 0 : 1);
    }

    /** The branch tip, and every path the tree holds there. */
    private static final class Branch {
        String sha;
        List<String> paths;
    }

    /**
     * Establishes the branch tip and the full file list, so "this pattern matched nothing"
     * can be told apart from "this repository is empty".
     *
     * <p>A separate polling repository, cloned bare with {@code --filter=blob:none} by the
     * plugin's own polling path, so the whole tree is enumerated without transferring a
     * single file's contents. Kept apart from the checkout directory deliberately: a bare
     * repository has no working tree, and a checkout into one cannot work.
     */
    private static Branch inspect(File pollingDirectory, Spec spec) {
        MaterialConfiguration full = configuration(spec, List.of());
        Git polling = Git.in(pollingDirectory, full);
        polling.syncPollingRepository();
        List<Revision> revisions = polling.log(null, List.of());
        if (revisions.isEmpty()) {
            throw new IllegalStateException("branch '" + spec.branch + "' has no commits");
        }
        Branch branch = new Branch();
        branch.sha = revisions.get(0).sha();
        branch.paths = TestRepository.gitIn(pollingDirectory, "ls-tree", "-r", "--name-only",
                        branch.sha)
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        return branch;
    }

    private static Result verify(File directory, Spec spec, Branch branch) {
        Result result = new Result();
        result.name = spec.name;
        result.repo = shortName(spec.remote());
        result.branch = spec.branch;
        result.patterns = spec.paths.size();
        result.filesInWholeTree = branch.paths.size();

        MaterialConfiguration configuration = configuration(spec, spec.paths);
        try {
            // The real thing: the same call an agent makes, on the same code.
            Git.in(directory, configuration).checkout(branch.sha);
            result.checkedOut = true;
        } catch (RuntimeException e) {
            result.failure = oneLine(e);
            return result;
        }

        List<String> present = listFiles(directory);
        result.filesCheckedOut = present.size();

        // A pattern that selects nothing is the failure worth hunting. git treats it as
        // perfectly valid, so the only symptom is a build that cannot find a file.
        for (String pattern : spec.paths) {
            if (pattern.startsWith("!")) {
                continue;                    // a negation selecting nothing is harmless
            }
            if (present.stream().noneMatch(path -> covers(pattern, path))) {
                result.patternsMatchingNothing.add(pattern);
            }
        }
        return result;
    }

    /** Whether a checked-out path is plausibly the one a pattern asked for. */
    private static boolean covers(String pattern, String path) {
        String cleaned = pattern.replaceAll("^/+", "").replaceAll("/+$", "");
        if (cleaned.isEmpty() || cleaned.equals("*")) {
            return true;
        }
        if (cleaned.contains("*")) {
            // Compare only the literal head of a wildcard pattern; git already decided the
            // match, and re-implementing its globbing here would just be a second, worse
            // matcher that could disagree with the first.
            String head = cleaned.substring(0, cleaned.indexOf('*'))
                    .replaceAll("/+$", "");
            return head.isEmpty() || startsWithSegment(path, head);
        }
        return path.equals(cleaned) || startsWithSegment(path, cleaned);
    }

    private static boolean startsWithSegment(String path, String prefix) {
        String a = path.toLowerCase(Locale.ROOT);
        String b = prefix.toLowerCase(Locale.ROOT);
        return a.equals(b) || a.startsWith(b + "/");
    }

    private static MaterialConfiguration configuration(Spec spec, List<String> paths) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("url", Map.of("value", spec.remote()));
        raw.put("branch", Map.of("value", spec.branch));
        raw.put("sparse_paths", Map.of("value", String.join("\n", paths)));
        raw.put("shallow", Map.of("value", "false"));
        raw.put("filter_by_paths", Map.of("value", "false"));
        // A token from the environment rather than from the specs file or the command line,
        // so it stays out of anything committed and out of the process list. It is supplied
        // as the username, which is how GitHub accepts a token over https, and it therefore
        // travels the plugin's ordinary credential path -- including its redaction, so the
        // token cannot reach this tool's own output either.
        String token = System.getenv("GITHUB_TOKEN");
        if (token != null && !token.isBlank() && spec.remote().startsWith("https://")) {
            raw.put("username", Map.of("value", token.trim()));
        }
        return MaterialConfiguration.from(raw);
    }

    private static Result failed(Spec spec, String why) {
        Result result = new Result();
        result.name = spec.name;
        result.repo = shortName(spec.remote());
        result.branch = spec.branch;
        result.patterns = spec.paths == null ? 0 : spec.paths.size();
        result.failure = why;
        return result;
    }

    private static List<String> listFiles(File root) {
        Path base = root.toPath();
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.filter(Files::isRegularFile)
                    .map(base::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .filter(path -> !path.startsWith(".git/"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("could not list " + root, e);
        }
    }

    private static String describe(Result result) {
        if (!result.checkedOut) {
            return "FAILED  " + result.failure;
        }
        String reduction = result.filesInWholeTree > 0
                ? String.format("%.1f%% of tree", 100.0 * result.filesCheckedOut / result.filesInWholeTree)
                : "tree size unknown";
        String suffix = result.patternsMatchingNothing.isEmpty()
                ? "" : String.format("  <-- %d pattern(s) matched NOTHING",
                        result.patternsMatchingNothing.size());
        return String.format("%5d files (%s)%s", result.filesCheckedOut, reduction, suffix);
    }

    private static void report(List<Result> results) {
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("VERIFICATION SUMMARY");
        System.out.println("=".repeat(78));

        List<Result> failures = results.stream().filter(r -> !r.checkedOut).toList();
        List<Result> empty = results.stream()
                .filter(r -> r.checkedOut && r.filesCheckedOut == 0).toList();
        List<Result> dead = results.stream()
                .filter(r -> !r.patternsMatchingNothing.isEmpty()).toList();

        System.out.printf("  specifications verified   : %d%n", results.size());
        System.out.printf("  checked out successfully  : %d%n",
                results.stream().filter(r -> r.checkedOut).count());
        System.out.printf("  FAILED to check out       : %d%n", failures.size());
        System.out.printf("  checked out ZERO files    : %d%n", empty.size());
        System.out.printf("  with dead patterns        : %d%n", dead.size());

        long files = results.stream().filter(r -> r.checkedOut)
                .mapToLong(r -> r.filesCheckedOut).sum();
        long trees = results.stream().filter(r -> r.checkedOut)
                .mapToLong(r -> r.filesInWholeTree).sum();
        if (trees > 0) {
            System.out.printf("%n  files checked out in total: %,d of %,d in the full trees (%.1f%%)%n",
                    files, trees, 100.0 * files / trees);
        }

        if (!failures.isEmpty()) {
            System.out.println("\n--- FAILED TO CHECK OUT ---");
            failures.forEach(r -> System.out.printf("  %-36s %s%n", r.name, r.failure));
        }
        if (!empty.isEmpty()) {
            System.out.println("\n--- CHECKED OUT NOTHING (build would find an empty directory) ---");
            empty.forEach(r -> System.out.printf("  %-36s %s (%s)%n", r.name, r.repo, r.branch));
        }
        if (!dead.isEmpty()) {
            System.out.println("\n--- PATTERNS THAT MATCHED NOTHING ---");
            System.out.println("    A renamed or misspelled path. git reports no error; the build");
            System.out.println("    simply cannot find the file.");
            Map<String, List<String>> byRepo = new TreeMap<>();
            for (Result result : dead) {
                for (String pattern : result.patternsMatchingNothing) {
                    byRepo.computeIfAbsent(result.repo + " (" + result.branch + ")",
                            key -> new ArrayList<>()).add(result.name + ": " + pattern);
                }
            }
            byRepo.forEach((repo, entries) -> {
                System.out.printf("  %s%n", repo);
                entries.stream().sorted(Comparator.naturalOrder()).distinct()
                        .forEach(entry -> System.out.printf("      %s%n", entry));
            });
        }
    }

    private static String shortName(String url) {
        String trimmed = url.replaceAll("\\.git$", "");
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf(':'));
        return slash < 0 ? trimmed : trimmed.substring(slash + 1);
    }

    private static String oneLine(Throwable error) {
        String message = error.getMessage();
        message = message == null ? error.toString() : message;
        return message.replaceAll("\\s+", " ").trim();
    }

    private static String required(String name, String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--" + name + "=")) {
                return arg.substring(name.length() + 3);
            }
        }
        throw new IllegalArgumentException("missing --" + name + "=<path>");
    }
}
