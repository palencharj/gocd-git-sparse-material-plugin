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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Everything this plugin does to a git repository, done by shelling out to the {@code git} binary.
 *
 * <p>We drive the real client rather than a Java implementation because {@code git sparse-checkout}
 * is the feature we are here to expose, and only the real client implements it faithfully.
 *
 * <p>Two safety properties this class is responsible for:
 * <ul>
 *   <li><b>Nothing prompts.</b> A build agent has no console. Every invocation disables terminal
 *       prompting and credential helpers, so bad credentials fail fast instead of hanging a job
 *       until it times out.</li>
 *   <li><b>The password never escapes.</b> It is embedded in a remote URL on the command line, so
 *       every string this class returns or throws is passed through {@link #redact}.</li>
 * </ul>
 */
public final class Git {

    // ASCII unit/record separators: git will never emit these inside a commit message,
    // which makes them safe delimiters for --pretty=format output.
    private static final char FIELD = (char) 0x1F;
    private static final char RECORD = (char) 0x1E;
    private static final long TIMEOUT_MINUTES = 30;

    /** Depth used when the material is configured shallow. */
    static final int SHALLOW_DEPTH = 1;

    private final File dir;
    private final ScmConfig config;

    public Git(File dir, ScmConfig config) {
        this.dir = dir;
        this.config = config;
    }

    // ---------------------------------------------------------------- polling

    /**
     * Brings the polling repository in {@code dir} up to date, creating it if needed.
     *
     * <p>Polling only ever needs history, never file contents, so this is a bare repository cloned
     * with {@code --filter=blob:none}. On a large monorepo that is the difference between copying
     * every blob in history and copying none of them. Servers that do not support filtering simply
     * warn and send everything, so this is safe to ask for unconditionally.
     */
    public void syncPollingRepo() {
        if (!isGitRepo()) {
            deleteRecursively(dir);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            run(dir.getParentFile(), "clone", "--bare", "--filter=blob:none", "--quiet",
                    remoteUrl(), dir.getAbsolutePath());
            run(dir, "config", "remote.origin.fetch", "+refs/heads/*:refs/heads/*");
        }
        run(dir, "fetch", "--prune", "--quiet", "origin");
    }

    /** Fails with a redacted, human-readable message if the remote or ref is not reachable. */
    public void checkConnection() {
        Output out = exec(null, "ls-remote", "--heads", remoteUrl(), config.branch());
        if (out.exitCode != 0) {
            throw new GitException("Cannot reach the repository: " + out.combined());
        }
        if (out.stdout.trim().isEmpty()) {
            throw new GitException("Connected, but branch '" + config.branch() + "' does not exist.");
        }
    }

    /**
     * Commits reachable from the configured branch, newest first, matching GoCD's own git material.
     *
     * @param sinceExclusive a commit to start after, or {@code null} for "just the newest one"
     * @param pathspecs      when non-empty, only commits touching these paths are reported
     */
    public List<Revision> log(String sinceExclusive, List<String> pathspecs) {
        List<String> args = new ArrayList<>(Arrays.asList(
                "log",
                "--date=iso-strict",
                "--no-color",
                "--no-decorate",
                "--pretty=format:%H" + FIELD + "%aI" + FIELD + "%an" + FIELD + "%ae" + FIELD + "%B" + RECORD));
        if (sinceExclusive == null) {
            args.add("-1");
            args.add(config.branch());
        } else {
            args.add(sinceExclusive + ".." + config.branch());
        }
        if (!pathspecs.isEmpty()) {
            args.add("--");
            args.addAll(pathspecs);
        }

        List<Revision> revisions = new ArrayList<>();
        for (String record : run(dir, args.toArray(new String[0])).split(String.valueOf(RECORD))) {
            String trimmed = record.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] f = trimmed.split(String.valueOf(FIELD), -1);
            if (f.length < 5) {
                continue;
            }
            String author = f[3].isEmpty() ? f[2] : f[2] + " <" + f[3] + ">";
            Revision revision = new Revision(f[0], parseTimestamp(f[1]), author, f[4].trim());
            addModifiedFiles(revision);
            revisions.add(revision);
        }
        return revisions;
    }

    /** Uses {@code diff-tree}, which handles the root commit correctly (unlike {@code diff}). */
    private void addModifiedFiles(Revision revision) {
        String out = run(dir, "diff-tree", "--name-status", "--root", "-r", "--no-commit-id", revision.sha);
        for (String line : out.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length == 2) {
                revision.modifiedFiles.add(
                        new Revision.ModifiedFile(parts[1], Revision.actionFor(parts[0].charAt(0))));
            }
        }
    }

    private static Instant parseTimestamp(String iso) {
        return OffsetDateTime.parse(iso).toInstant();
    }

    // --------------------------------------------------------------- checkout

    /**
     * Lays down {@code sha} in {@code dir}, containing only the configured paths.
     *
     * <p>Ordering matters. The sparse patterns are applied <em>before</em> the working tree is
     * populated, so excluded files are never written to disk in the first place — that, rather than
     * deleting them afterwards, is the whole point.
     *
     * <p>Applying the patterns on every checkout also makes this self-healing: widening, narrowing
     * or removing them repairs an existing working copy in place, with no need to detect the change
     * or re-clone.
     */
    public void checkout(String sha) {
        if (!isGitRepo()) {
            deleteRecursively(dir);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            List<String> clone = new ArrayList<>(Arrays.asList("clone", "--no-checkout", "--quiet"));
            if (config.shallow()) {
                clone.add("--depth=" + SHALLOW_DEPTH);
                clone.add("--branch");
                clone.add(config.branch());
            }
            clone.add(remoteUrl());
            clone.add(dir.getAbsolutePath());
            run(dir.getParentFile(), clone.toArray(new String[0]));
        }

        // A shallow repository may not contain the requested commit; deepen rather than fail.
        run(dir, "config", "remote.origin.url", remoteUrl());
        fetchFor(sha);

        applySparsePatterns();

        run(dir, "reset", "--hard", sha);
        // -x so ignored build output from a previous job on this agent cannot leak into this one.
        run(dir, "clean", "-dffx");
    }

    private void fetchFor(String sha) {
        if (config.shallow()) {
            Output shallowFetch = exec(dir, "fetch", "--quiet", "--depth=" + SHALLOW_DEPTH,
                    "origin", config.branch());
            if (shallowFetch.exitCode == 0 && hasCommit(sha)) {
                return;
            }
            // Fall through: the wanted revision is older than the shallow window.
            run(dir, "fetch", "--quiet", "--unshallow", "origin");
            return;
        }
        run(dir, "fetch", "--prune", "--quiet", "origin");
    }

    private boolean hasCommit(String sha) {
        return exec(dir, "cat-file", "-e", sha + "^{commit}").exitCode == 0;
    }

    /**
     * Applies the configured patterns, or removes sparsity entirely when none are configured.
     *
     * <p>{@code --no-cone} is deliberate. Cone mode is faster but can only select whole
     * directories; non-cone takes gitignore-style patterns and so can select individual files,
     * which is what makes this a genuine equivalent of a Perforce client view.
     */
    private void applySparsePatterns() {
        List<String> paths = config.sparsePaths();
        if (paths.isEmpty()) {
            if (isSparse()) {
                run(dir, "sparse-checkout", "disable");
            }
            return;
        }
        List<String> args = new ArrayList<>(Arrays.asList("sparse-checkout", "set", "--no-cone"));
        args.addAll(paths);
        run(dir, args.toArray(new String[0]));
    }

    /**
     * Whether the working copy is currently sparse.
     *
     * <p>{@code git sparse-checkout disable} leaves {@code .git/info/sparse-checkout} on disk and
     * only flips {@code core.sparseCheckout}, so the config flag is the authoritative signal. The
     * file check is just a cheap short-circuit.
     */
    boolean isSparse() {
        if (!new File(dir, ".git/info/sparse-checkout").isFile()) {
            return false;
        }
        return "true".equals(exec(dir, "config", "--get", "core.sparseCheckout").stdout.trim());
    }

    boolean isGitRepo() {
        return new File(dir, ".git").exists() || new File(dir, "HEAD").isFile();
    }

    // ------------------------------------------------------------------- plumbing

    /**
     * The remote URL, with credentials folded in when supplied.
     *
     * <p>Only meaningful for HTTP(S). An ssh:// or scp-style remote authenticates with a key, and
     * injecting a password there would produce a URL git cannot use.
     */
    String remoteUrl() {
        String url = config.url();
        if (ScmConfig.isBlank(config.username()) || url == null) {
            return url;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return url;
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        String scheme = url.substring(0, schemeEnd + 3);
        String rest = url.substring(schemeEnd + 3);

        // Drop any userinfo already present, so ours is the only credential in play.
        int at = rest.indexOf('@');
        int firstSlash = rest.indexOf('/');
        if (at >= 0 && (firstSlash < 0 || at < firstSlash)) {
            rest = rest.substring(at + 1);
        }

        StringBuilder userInfo = new StringBuilder(encode(config.username()));
        if (!ScmConfig.isBlank(config.password())) {
            userInfo.append(':').append(encode(config.password()));
        }
        // Assembled by hand rather than via URI's multi-argument constructor, which percent-encodes
        // the userinfo a second time and turns %40 into %2540.
        return scheme + userInfo + "@" + rest;
    }

    private static String encode(String s) {
        // Only the delimiters that would corrupt the userinfo component.
        return s.replace("%", "%25").replace(":", "%3A").replace("@", "%40").replace("/", "%2F");
    }

    /** Removes the password and any embedded credentials from text that may be surfaced. */
    String redact(String text) {
        if (text == null) {
            return null;
        }
        String out = text;
        if (!ScmConfig.isBlank(config.password())) {
            out = out.replace(config.password(), "******");
            out = out.replace(encode(config.password()), "******");
        }
        // Belt and braces: strip userinfo from any URL that made it into the message.
        return out.replaceAll("(://)[^/@\\s]+(@)", "$1******$2");
    }

    private String run(File cwd, String... args) {
        Output out = exec(cwd, args);
        if (out.exitCode != 0) {
            throw new GitException("git " + args[0] + " failed: " + out.combined());
        }
        return out.stdout;
    }

    private Output exec(File cwd, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        // Never block a build waiting for input that can never arrive.
        command.add("-c");
        command.add("credential.helper=");
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        if (cwd != null) {
            pb.directory(cwd);
        }
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "echo");
        pb.environment().put("GCM_INTERACTIVE", "never");
        pb.environment().put("LC_ALL", "C");

        Process process = null;
        try {
            process = pb.start();
            String stdout = drain(process.getInputStream());
            String stderr = drain(process.getErrorStream());
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new GitException("git " + args[0] + " timed out after " + TIMEOUT_MINUTES + " minutes");
            }
            return new Output(process.exitValue(), redact(stdout), redact(stderr));
        } catch (IOException e) {
            throw new GitException("Could not run git — is it installed and on the agent's PATH? "
                    + redact(String.valueOf(e.getMessage())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new GitException("Interrupted while running git " + args[0]);
        }
    }

    private static String drain(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static final class Output {
        final int exitCode;
        final String stdout;
        final String stderr;

        Output(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        String combined() {
            String merged = (stderr == null ? "" : stderr.trim());
            if (merged.isEmpty()) {
                merged = (stdout == null ? "" : stdout.trim());
            }
            return merged.isEmpty() ? "exit code " + exitCode : merged;
        }
    }

    /** Carries an already-redacted message, safe to show in the GoCD UI and build logs. */
    public static final class GitException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public GitException(String message) {
            super(message);
        }
    }
}
