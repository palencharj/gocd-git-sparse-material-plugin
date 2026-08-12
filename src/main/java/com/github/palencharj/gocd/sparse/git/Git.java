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
package com.github.palencharj.gocd.sparse.git;

import com.github.palencharj.gocd.sparse.config.MaterialConfiguration;

import java.io.File;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every git operation this material performs, expressed against one directory.
 *
 * <p>The real {@code git} client is driven rather than a Java implementation, because
 * {@code git sparse-checkout} is the feature being exposed and only the real client implements it
 * faithfully. Process execution, credential embedding and redaction are delegated to
 * {@link CommandLine} and {@link RemoteUrl}, leaving this class to express what to do rather than
 * how to run it.
 *
 * <p>Requires <b>git 2.25 or newer</b> on whichever machine runs it — that is the release
 * {@code sparse-checkout} arrived in. On an older client the failure is a bare "unknown subcommand",
 * which is why the version is called out in the README and in the operator docs.
 */
public final class Git {

    /**
     * ASCII unit and record separators. git will not emit these inside a commit message, which makes
     * them safe delimiters for {@code --pretty=format} output — unlike newlines, which appear in
     * every message body.
     */
    private static final char FIELD = (char) 0x1F;
    private static final char RECORD = (char) 0x1E;

    /** Depth used when the material is configured shallow. */
    private static final int SHALLOW_DEPTH = 1;

    private final File directory;
    private final MaterialConfiguration configuration;
    private final RemoteUrl remote;

    private Git(File directory, MaterialConfiguration configuration) {
        this.directory = directory;
        this.configuration = configuration;
        this.remote = RemoteUrl.of(configuration.url(), configuration.username(),
                configuration.password());
    }

    /** Operations against a repository in {@code directory}, creating it when they need to. */
    public static Git in(File directory, MaterialConfiguration configuration) {
        return new Git(directory, configuration);
    }

    /**
     * Operations that talk to the remote without touching the disk.
     *
     * <p>Only {@link #checkConnection()} is valid on the result. A separate factory rather than a
     * null directory, so "there is no working copy" is stated in the code instead of discovered by
     * a {@code NullPointerException}.
     */
    public static Git forRemote(MaterialConfiguration configuration) {
        return new Git(null, configuration);
    }

    // ------------------------------------------------------------------ connectivity

    /** Fails with a redacted, human-readable message if the remote or the branch is unreachable. */
    public void checkConnection() {
        ConsoleResult result = git()
                .withArgs("ls-remote", "--heads", remote.forCommandLine(), configuration.branch())
                .runOrBomb(false);
        if (result.failed()) {
            throw new GitException("Cannot reach " + remote.forDisplay() + ": " + result.describe());
        }
        if (result.outputAsString().trim().isEmpty()) {
            throw new GitException("Connected to " + remote.forDisplay() + ", but branch '"
                    + configuration.branch() + "' does not exist.");
        }
    }

    // ---------------------------------------------------------------------- polling

    /**
     * Brings the polling repository up to date, creating it if it is not there.
     *
     * <p>Polling needs history and nothing else, so this is a bare repository fetched with
     * {@code --filter=blob:none}. On a large monorepo that is the difference between transferring
     * every blob that ever existed and transferring none of them. A server that does not support
     * filtering warns and sends everything, so asking for it unconditionally is safe.
     */
    public void syncPollingRepository() {
        if (!isRepository()) {
            recreateDirectory();
            git().withWorkingDir(directory.getParentFile())
                    .withArgs("clone", "--bare", "--filter=blob:none", "--quiet")
                    .withArg(remote.forCommandLine())
                    .withArg(directory.getAbsolutePath())
                    .runOrBomb(true);
            inRepository().withArgs("config", "remote.origin.fetch", "+refs/heads/*:refs/heads/*")
                    .runOrBomb(true);
        }
        inRepository().withArgs("fetch", "--prune", "--quiet", "origin").runOrBomb(true);
    }

    /**
     * Commits reachable from the configured branch, newest first — matching the ordering GoCD's own
     * git material returns.
     *
     * @param sinceExclusive a commit to start after, or {@code null} for only the newest one
     * @param pathspecs      when non-empty, only commits touching these paths are reported
     */
    public List<Revision> log(String sinceExclusive, List<String> pathspecs) {
        CommandLine command = inRepository()
                .withArgs("log", "--date=iso-strict", "--no-color", "--no-decorate")
                .withArg("--pretty=format:%H" + FIELD + "%aI" + FIELD + "%an" + FIELD + "%ae"
                        + FIELD + "%B" + RECORD)
                .when(sinceExclusive == null, git -> git.withArgs("-1", configuration.branch()))
                .when(sinceExclusive != null,
                        git -> git.withArg(sinceExclusive + ".." + configuration.branch()))
                .when(!pathspecs.isEmpty(), git -> git.withArg("--").withArgs(pathspecs));

        List<Revision> revisions = new ArrayList<>();
        for (String record : command.runOrBomb(true).outputAsString().split(String.valueOf(RECORD))) {
            parse(record).ifPresent(revisions::add);
        }
        return revisions;
    }

    private Optional<Revision> parse(String record) {
        String trimmed = record.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String[] fields = trimmed.split(String.valueOf(FIELD), -1);
        if (fields.length < 5) {
            return Optional.empty();
        }
        String name = fields[2];
        String email = fields[3];
        String author = email.isEmpty() ? name : name + " <" + email + ">";
        Revision revision = new Revision(fields[0], toInstant(fields[1]), author, fields[4].trim());
        describeModifiedFiles(revision);
        return Optional.of(revision);
    }

    /**
     * Populates the files a commit touched.
     *
     * <p>{@code diff-tree --root} rather than {@code diff}, because the root commit has no parent to
     * diff against and would otherwise report no files at all — so the first commit in a repository
     * would look like it changed nothing.
     *
     * <p>{@code --no-renames} keeps every line to one path. With rename detection on, a renamed file
     * is reported as {@code R100<tab>old<tab>new}, and GoCD's revision format has no way to express
     * that — it would arrive as one file with a tab in its name. Rename detection is off by default
     * for plumbing commands, but saying so explicitly means a {@code diff.renames} in someone's
     * global git config cannot turn it on underneath us.
     */
    private void describeModifiedFiles(Revision revision) {
        ConsoleResult result = inRepository()
                .withArgs("diff-tree", "--name-status", "--root", "-r", "--no-commit-id",
                        "--no-renames", revision.sha())
                .runOrBomb(true);
        for (String line : result.output()) {
            // Tab-separated, not whitespace-separated: paths legitimately contain spaces.
            String[] fields = line.trim().split("\t");
            if (fields.length >= 2 && !fields[0].isEmpty()) {
                revision.add(Revision.ModifiedFile.from(fields[0].charAt(0), fields[fields.length - 1]));
            }
        }
    }

    private static Instant toInstant(String iso8601) {
        return OffsetDateTime.parse(iso8601).toInstant();
    }

    // --------------------------------------------------------------------- checkout

    /**
     * Lays down {@code sha} in this directory, containing only the configured paths.
     *
     * <p>The ordering is the whole design, and it is not interchangeable:
     *
     * <ol>
     *   <li>Clone with {@code --no-checkout}, so nothing is written yet.</li>
     *   <li>Fetch the wanted revision, deepening a shallow clone if it falls outside the window.</li>
     *   <li>Apply the sparse patterns.</li>
     *   <li><em>Then</em> populate the working tree with {@code reset --hard}.</li>
     * </ol>
     *
     * <p>Applying the patterns before the tree is populated means excluded files are never written,
     * rather than written and then deleted — which is the entire point of the feature. Re-applying
     * them on every checkout also makes the working copy self-healing: widening, narrowing or
     * removing the paths repairs an existing directory in place, with no change detection to get
     * wrong and no need to re-clone.
     */
    public void checkout(String sha) {
        if (!isRepository()) {
            recreateDirectory();
            git().withWorkingDir(directory.getParentFile())
                    .withArgs("clone", "--no-checkout", "--quiet")
                    .when(configuration.isShallow(), clone -> clone
                            .withArg("--depth=" + SHALLOW_DEPTH)
                            .withArgs("--branch", configuration.branch()))
                    .withArg(remote.forCommandLine())
                    .withArg(directory.getAbsolutePath())
                    .runOrBomb(true);
        }

        // Credentials may have been rotated since the clone; keep the stored remote current.
        inRepository().withArgs("config", "remote.origin.url", remote.forCommandLine())
                .runOrBomb(true);
        fetchRevision(sha);
        applySparsePaths();
        inRepository().withArgs("reset", "--hard", sha).runOrBomb(true);
        // -x as well as -d: ignored build output from a previous job on this agent must not leak
        // into this one.
        inRepository().withArgs("clean", "-dffx").runOrBomb(true);
    }

    /**
     * Ensures {@code sha} is present locally, unshallowing only if it has to.
     *
     * <p>A shallow clone holds one commit, so any build of an older revision — a re-run, or a
     * pipeline pinned to a previous commit — needs the history deepened. Trying shallow first keeps
     * the common case cheap.
     */
    private void fetchRevision(String sha) {
        if (!configuration.isShallow()) {
            inRepository().withArgs("fetch", "--prune", "--quiet", "origin").runOrBomb(true);
            return;
        }
        ConsoleResult shallowFetch = inRepository()
                .withArgs("fetch", "--quiet", "--depth=" + SHALLOW_DEPTH, "origin",
                        configuration.branch())
                .runOrBomb(false);
        if (!shallowFetch.failed() && hasCommit(sha)) {
            return;
        }
        inRepository().withArgs("fetch", "--quiet", "--unshallow", "origin").runOrBomb(true);
    }

    private boolean hasCommit(String sha) {
        return !inRepository().withArgs("cat-file", "-e", sha + "^{commit}")
                .runOrBomb(false).failed();
    }

    /**
     * Applies the configured patterns, or removes sparsity when none are configured.
     *
     * <p>{@code --no-cone} is deliberate. Cone mode is faster but selects only whole directories,
     * while non-cone takes gitignore-style patterns and so can select individual files — which is
     * what makes this a genuine equivalent of a Perforce client view.
     */
    private void applySparsePaths() {
        List<String> paths = configuration.sparsePaths();
        if (paths.isEmpty()) {
            if (isSparse()) {
                inRepository().withArgs("sparse-checkout", "disable").runOrBomb(true);
            }
            return;
        }
        inRepository().withArgs("sparse-checkout", "set", "--no-cone").withArgs(paths)
                .runOrBomb(true);
    }

    /**
     * Whether this working copy is currently sparse.
     *
     * <p>{@code git sparse-checkout disable} leaves {@code .git/info/sparse-checkout} on disk and
     * only clears {@code core.sparseCheckout}, so the config flag is the authoritative signal. The
     * file check short-circuits the common case of a repository that has never been sparse, so a
     * material that does not use this feature never spawns a process to find out.
     */
    public boolean isSparse() {
        if (!new File(directory, ".git/info/sparse-checkout").isFile()) {
            return false;
        }
        return "true".equals(inRepository()
                .withArgs("config", "--get", "core.sparseCheckout")
                .runOrBomb(false).outputAsString().trim());
    }

    /** True for either a working repository or a bare one. */
    public boolean isRepository() {
        return new File(directory, ".git").exists() || new File(directory, "HEAD").isFile();
    }

    // --------------------------------------------------------------------- plumbing

    /**
     * A {@code git} invocation with this material's remote and the two settings that must not be
     * inherited from whatever git config happens to exist on the machine.
     *
     * <p>{@code credential.helper=} empties the list of helpers, so an agent's stored credentials
     * can never stand in for a material's own — a material with the wrong token has to fail rather
     * than quietly succeed on someone else's.
     *
     * <p>{@code core.quotePath=false} stops git C-quoting non-ASCII paths, which it does by default:
     * with it on, {@code café.txt} is reported to GoCD as {@code "caf\303\251.txt"} and shown that
     * way in the changes list.
     */
    private CommandLine git() {
        return CommandLine.createCommandLine("git")
                .withArgs("-c", "credential.helper=")
                .withArgs("-c", "core.quotePath=false")
                .withSecret(remote);
    }

    private CommandLine inRepository() {
        return git().withWorkingDir(directory);
    }

    private void recreateDirectory() {
        deleteRecursively(directory);
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new GitException("Could not create the working directory " + directory);
        }
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
        if (!file.delete() && file.exists()) {
            throw new GitException("Could not delete " + file);
        }
    }
}
