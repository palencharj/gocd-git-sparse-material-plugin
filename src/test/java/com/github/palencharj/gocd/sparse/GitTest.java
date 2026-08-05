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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the real {@code git} binary against real repositories. */
class GitTest {

    @TempDir
    Path tempDir;

    private TestRepo origin;
    private File workspace;
    private File flyweight;

    @BeforeEach
    void setUp() {
        origin = TestRepo.create(tempDir.resolve("origin").toFile());
        origin.write("keep/a.txt", "a")
                .write("keep/nested/b.txt", "b")
                .write("drop/c.txt", "c")
                .write("root.txt", "r");
        origin.commit("initial");

        workspace = tempDir.resolve("workspace").toFile();
        flyweight = tempDir.resolve("flyweight").toFile();
    }

    private ScmConfig config(String sparsePaths) {
        return config(sparsePaths, false, false);
    }

    private ScmConfig config(String sparsePaths, boolean shallow, boolean filterByPaths) {
        Map<String, Object> raw = new LinkedHashMap<>();
        put(raw, ScmConfig.URL, origin.url());
        put(raw, ScmConfig.BRANCH, "master");
        put(raw, ScmConfig.SPARSE_PATHS, sparsePaths);
        put(raw, ScmConfig.SHALLOW, String.valueOf(shallow));
        put(raw, ScmConfig.FILTER_BY_PATHS, String.valueOf(filterByPaths));
        return ScmConfig.from(raw);
    }

    private void put(Map<String, Object> raw, String key, String value) {
        Map<String, Object> holder = new LinkedHashMap<>();
        holder.put("value", value);
        raw.put(key, holder);
    }

    // ------------------------------------------------------------------ checkout

    @Test
    void checksOutOnlyTheConfiguredPaths() {
        new Git(workspace, config("keep/*")).checkout(origin.head());

        assertThat(TestRepo.filesIn(workspace))
                .containsExactly("keep/a.txt", "keep/nested/b.txt");
    }

    @Test
    void checksOutAnIndividualFileNotJustDirectories() {
        // The reason for --no-cone: cone mode can only select whole directories.
        new Git(workspace, config("root.txt")).checkout(origin.head());

        assertThat(TestRepo.filesIn(workspace)).containsExactly("root.txt");
    }

    @Test
    void checksOutEverythingWhenNoPathsAreConfigured() {
        new Git(workspace, config("")).checkout(origin.head());

        assertThat(TestRepo.filesIn(workspace))
                .containsExactly("drop/c.txt", "keep/a.txt", "keep/nested/b.txt", "root.txt");
    }

    @Test
    void honoursExclusionPatterns() {
        new Git(workspace, config("keep/*\n!keep/nested/")).checkout(origin.head());

        assertThat(TestRepo.filesIn(workspace)).containsExactly("keep/a.txt");
    }

    @Test
    void narrowsAnExistingWorkingCopyWhenPathsChange() {
        String head = origin.head();
        new Git(workspace, config("keep/*\nroot.txt")).checkout(head);
        assertThat(TestRepo.filesIn(workspace)).contains("root.txt");

        new Git(workspace, config("keep/*")).checkout(head);

        assertThat(TestRepo.filesIn(workspace))
                .containsExactly("keep/a.txt", "keep/nested/b.txt");
    }

    @Test
    void restoresAFullWorkingCopyWhenPathsAreRemoved() {
        String head = origin.head();
        Git sparse = new Git(workspace, config("keep/*"));
        sparse.checkout(head);
        assertThat(sparse.isSparse()).isTrue();

        Git full = new Git(workspace, config(""));
        full.checkout(head);

        assertThat(full.isSparse()).isFalse();
        assertThat(TestRepo.filesIn(workspace))
                .containsExactly("drop/c.txt", "keep/a.txt", "keep/nested/b.txt", "root.txt");
    }

    @Test
    void isNotSparseOnARepositoryThatNeverWas() {
        new Git(workspace, config("")).checkout(origin.head());

        assertThat(new Git(workspace, config("")).isSparse()).isFalse();
    }

    @Test
    void updatesIncludedFilesAndIgnoresChangesToExcludedOnes() {
        new Git(workspace, config("keep/*")).checkout(origin.head());

        origin.write("keep/a.txt", "a-updated").write("drop/new.txt", "new");
        String second = origin.commit("touch both sides");

        new Git(workspace, config("keep/*")).checkout(second);

        assertThat(TestRepo.filesIn(workspace))
                .containsExactly("keep/a.txt", "keep/nested/b.txt");
        assertThat(new File(workspace, "keep/a.txt")).hasContent("a-updated");
        assertThat(new File(workspace, "drop/new.txt")).doesNotExist();
    }

    @Test
    void leavesTheWorkingCopyClean() {
        new Git(workspace, config("keep/*")).checkout(origin.head());

        // A dirty status here would make GoCD think the workspace had been tampered with.
        assertThat(gitIn(workspace, "status", "--porcelain").trim()).isEmpty();
    }

    @Test
    void removesUntrackedFilesLeftBehindByAPreviousBuild() {
        new Git(workspace, config("keep/*")).checkout(origin.head());
        writeFile(new File(workspace, "leftover.txt"));

        new Git(workspace, config("keep/*")).checkout(origin.head());

        assertThat(new File(workspace, "leftover.txt")).doesNotExist();
    }

    @Test
    void shallowCheckoutStillRespectsSparsePaths() {
        new Git(workspace, config("keep/*", true, false)).checkout(origin.head());

        assertThat(TestRepo.filesIn(workspace))
                .containsExactly("keep/a.txt", "keep/nested/b.txt");
        assertThat(new File(workspace, ".git/shallow")).exists();
    }

    @Test
    void shallowCheckoutDeepensWhenAnOlderRevisionIsRequested() {
        String first = origin.head();
        origin.write("keep/a.txt", "second").commit("second");

        // Only the tip is within a depth-1 window, so this forces an unshallow.
        new Git(workspace, config("keep/*", true, false)).checkout(first);

        assertThat(new File(workspace, "keep/a.txt")).hasContent("a");
    }

    // ------------------------------------------------------------------- polling

    @Test
    void logReturnsTheNewestCommitFirstWithItsModifiedFiles() {
        origin.write("keep/a.txt", "changed").commit("second commit");
        Git git = new Git(flyweight, config("keep/*"));
        git.syncPollingRepo();

        List<Revision> revisions = git.log(null, Collections.emptyList());

        assertThat(revisions).hasSize(1);
        Revision newest = revisions.get(0);
        assertThat(newest.comment).isEqualTo("second commit");
        assertThat(newest.author).contains("Test User").contains("test@example.com");
        assertThat(newest.timestamp).isNotNull();
        assertThat(newest.modifiedFiles).extracting(f -> f.fileName).containsExactly("keep/a.txt");
        assertThat(newest.modifiedFiles).extracting(f -> f.action).containsExactly("modified");
    }

    @Test
    void logReportsAddedAndDeletedActions() {
        origin.write("keep/added.txt", "x").delete("drop/c.txt").commit("add and delete");
        Git git = new Git(flyweight, config("keep/*"));
        git.syncPollingRepo();

        Revision newest = git.log(null, Collections.emptyList()).get(0);

        assertThat(newest.modifiedFiles).extracting(f -> f.fileName + ":" + f.action)
                .containsExactlyInAnyOrder("keep/added.txt:added", "drop/c.txt:deleted");
    }

    @Test
    void logSinceAPreviousRevisionExcludesThatRevision() {
        String first = origin.head();
        origin.write("keep/a.txt", "2").commit("second");
        origin.write("keep/a.txt", "3").commit("third");
        Git git = new Git(flyweight, config("keep/*"));
        git.syncPollingRepo();

        List<Revision> since = git.log(first, Collections.emptyList());

        assertThat(since).extracting(r -> r.comment).containsExactly("third", "second");
    }

    @Test
    void logCanBeNarrowedToPathsSoUnrelatedCommitsAreInvisible() {
        String first = origin.head();
        origin.write("drop/c.txt", "irrelevant").commit("only touches excluded path");
        ScmConfig filtered = config("keep/*", false, true);
        Git git = new Git(flyweight, filtered);
        git.syncPollingRepo();

        assertThat(git.log(first, filtered.pathspecs())).isEmpty();

        origin.write("keep/a.txt", "relevant").commit("touches watched path");
        git.syncPollingRepo();
        assertThat(git.log(first, filtered.pathspecs()))
                .extracting(r -> r.comment).containsExactly("touches watched path");
    }

    @Test
    void pollingRepoIsReusedAcrossCalls() {
        Git git = new Git(flyweight, config("keep/*"));
        git.syncPollingRepo();
        origin.write("keep/a.txt", "again").commit("later");

        git.syncPollingRepo();

        assertThat(git.log(null, Collections.emptyList()).get(0).comment).isEqualTo("later");
    }

    @Test
    void checkConnectionFailsClearlyForAMissingBranch() {
        Map<String, Object> raw = new LinkedHashMap<>();
        put(raw, ScmConfig.URL, origin.url());
        put(raw, ScmConfig.BRANCH, "no-such-branch");

        assertThatThrownBy(() -> new Git(null, ScmConfig.from(raw)).checkConnection())
                .isInstanceOf(Git.GitException.class)
                .hasMessageContaining("no-such-branch");
    }

    @Test
    void checkConnectionFailsClearlyForAMissingRepository() {
        Map<String, Object> raw = new LinkedHashMap<>();
        put(raw, ScmConfig.URL, tempDir.resolve("does-not-exist").toUri().toString());
        put(raw, ScmConfig.BRANCH, "master");

        assertThatThrownBy(() -> new Git(null, ScmConfig.from(raw)).checkConnection())
                .isInstanceOf(Git.GitException.class);
    }

    // ------------------------------------------------------------------ secrets

    @Test
    void embedsCredentialsInHttpUrlsOnly() {
        Map<String, Object> http = new LinkedHashMap<>();
        put(http, ScmConfig.URL, "https://example.com/r.git");
        put(http, ScmConfig.USERNAME, "alice");
        put(http, ScmConfig.PASSWORD, "s3cr3t");
        assertThat(new Git(workspace, ScmConfig.from(http)).remoteUrl())
                .isEqualTo("https://alice:s3cr3t@example.com/r.git");

        Map<String, Object> ssh = new LinkedHashMap<>();
        put(ssh, ScmConfig.URL, "git@example.com:org/r.git");
        put(ssh, ScmConfig.USERNAME, "alice");
        put(ssh, ScmConfig.PASSWORD, "s3cr3t");
        assertThat(new Git(workspace, ScmConfig.from(ssh)).remoteUrl())
                .isEqualTo("git@example.com:org/r.git");
    }

    @Test
    void redactsThePasswordAndAnyEmbeddedCredentials() {
        Map<String, Object> raw = new LinkedHashMap<>();
        put(raw, ScmConfig.URL, "https://example.com/r.git");
        put(raw, ScmConfig.USERNAME, "alice");
        put(raw, ScmConfig.PASSWORD, "s3cr3t");
        Git git = new Git(workspace, ScmConfig.from(raw));

        String redacted = git.redact("fatal: auth failed for https://alice:s3cr3t@example.com/r.git");

        assertThat(redacted).doesNotContain("s3cr3t");
        assertThat(redacted).contains("******");
    }

    @Test
    void escapesCredentialCharactersThatWouldCorruptTheUrl() {
        Map<String, Object> raw = new LinkedHashMap<>();
        put(raw, ScmConfig.URL, "https://example.com/r.git");
        put(raw, ScmConfig.USERNAME, "alice@corp");
        put(raw, ScmConfig.PASSWORD, "p@ss:word/x");

        String url = new Git(workspace, ScmConfig.from(raw)).remoteUrl();

        assertThat(url).isEqualTo("https://alice%40corp:p%40ss%3Aword%2Fx@example.com/r.git");
    }

    // ------------------------------------------------------------------- helpers

    private String gitIn(File dir, String... args) {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.Arrays.asList(args));
        try {
            Process p = new ProcessBuilder(command).directory(dir).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor();
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeFile(File file) {
        try {
            java.nio.file.Files.write(file.toPath(), "leftover".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
