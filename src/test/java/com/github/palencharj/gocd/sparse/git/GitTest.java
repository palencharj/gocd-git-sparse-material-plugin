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

import com.github.palencharj.gocd.sparse.TestRepository;
import com.github.palencharj.gocd.sparse.config.MaterialConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the real {@code git} binary against real repositories. */
class GitTest {

    @TempDir
    Path tempDir;

    private TestRepository origin;
    private File workspace;
    private File flyweight;

    @BeforeEach
    void setUp() {
        origin = TestRepository.create(tempDir.resolve("origin").toFile());
        origin.write("keep/a.txt", "a")
                .write("keep/nested/b.txt", "b")
                .write("drop/c.txt", "c")
                .write("root.txt", "r");
        origin.commit("initial");

        workspace = tempDir.resolve("workspace").toFile();
        flyweight = tempDir.resolve("flyweight").toFile();
    }

    // ------------------------------------------------------------------- checkout

    @Test
    void shouldCheckOutOnlyTheConfiguredPaths() {
        Git.in(workspace, configuration("keep/*")).checkout(origin.head());

        assertThat(TestRepository.filesIn(workspace))
                .containsExactly("keep/a.txt", "keep/nested/b.txt");
    }

    @Test
    void shouldCheckOutAnIndividualFileAndNotJustWholeDirectories() {
        // The entire reason for --no-cone: cone mode can only select directories, which would make
        // this no substitute for a Perforce client view.
        Git.in(workspace, configuration("root.txt")).checkout(origin.head());

        assertThat(TestRepository.filesIn(workspace)).containsExactly("root.txt");
    }

    @Test
    void shouldHonourExclusionPatterns() {
        Git.in(workspace, configuration("keep/*\n!keep/nested/")).checkout(origin.head());

        assertThat(TestRepository.filesIn(workspace)).containsExactly("keep/a.txt");
    }

    @Test
    void shouldTakeEverythingExceptAnExcludedDirectory() {
        // The form the Perforce migration emits for a depot whose client view takes the
        // whole thing and then subtracts a path: an include-all followed by a negation.
        // Asserted here because the migration tool generates it in bulk, and getting the
        // include-all wrong would silently hand builds an empty or a full working copy.
        Git.in(workspace, configuration("/*\n!drop")).checkout(origin.head());

        assertThat(TestRepository.filesIn(workspace))
                .containsExactly("keep/a.txt", "keep/nested/b.txt", "root.txt");
    }

    @Test
    void shouldExcludeANestedPathFromAnOtherwiseWholeCheckout() {
        origin.write("deep/a/b/d.txt", "d").write("deep/a/keep.txt", "k").commit("nest");

        Git.in(workspace, configuration("/*\n!deep/a/b")).checkout(origin.head());

        assertThat(TestRepository.filesIn(workspace))
                .contains("deep/a/keep.txt", "root.txt")
                .doesNotContain("deep/a/b/d.txt");
    }

    @Test
    void shouldCheckOutEverythingWhenNoPathsAreConfigured() {
        Git.in(workspace, configuration("")).checkout(origin.head());

        assertThat(TestRepository.filesIn(workspace))
                .containsExactly("drop/c.txt", "keep/a.txt", "keep/nested/b.txt", "root.txt");
    }

    @Test
    void shouldNarrowAnExistingWorkingCopyWhenThePathsChange() {
        // Self-healing rather than change-detecting: the patterns are re-applied on every checkout,
        // so an existing directory is repaired in place with nothing to get wrong.
        String head = origin.head();
        Git.in(workspace, configuration("keep/*\nroot.txt")).checkout(head);
        assertThat(TestRepository.filesIn(workspace)).contains("root.txt");

        Git.in(workspace, configuration("keep/*")).checkout(head);

        assertThat(TestRepository.filesIn(workspace))
                .containsExactly("keep/a.txt", "keep/nested/b.txt");
    }

    @Test
    void shouldRestoreAFullWorkingCopyWhenThePathsAreRemoved() {
        String head = origin.head();
        Git sparse = Git.in(workspace, configuration("keep/*"));
        sparse.checkout(head);
        assertThat(sparse.isSparse()).isTrue();

        Git full = Git.in(workspace, configuration(""));
        full.checkout(head);

        assertThat(full.isSparse()).isFalse();
        assertThat(TestRepository.filesIn(workspace))
                .containsExactly("drop/c.txt", "keep/a.txt", "keep/nested/b.txt", "root.txt");
    }

    @Test
    void shouldNotReportSparsityForARepositoryThatNeverHadIt() {
        Git.in(workspace, configuration("")).checkout(origin.head());

        assertThat(Git.in(workspace, configuration("")).isSparse()).isFalse();
    }

    @Test
    void shouldReadSparsityFromConfigBecauseDisableLeavesTheFileBehind() {
        // `git sparse-checkout disable` clears core.sparseCheckout but leaves
        // .git/info/sparse-checkout on disk, so the file's existence is not the answer.
        Git git = Git.in(workspace, configuration("keep/*"));
        git.checkout(origin.head());
        TestRepository.gitIn(workspace, "sparse-checkout", "disable");

        assertThat(new File(workspace, ".git/info/sparse-checkout")).exists();
        assertThat(git.isSparse()).isFalse();
    }

    @Test
    void shouldUpdateIncludedFilesAndIgnoreChangesToExcludedOnes() {
        Git.in(workspace, configuration("keep/*")).checkout(origin.head());

        origin.write("keep/a.txt", "a-updated").write("drop/new.txt", "new");
        String second = origin.commit("touch both sides");

        Git.in(workspace, configuration("keep/*")).checkout(second);

        assertThat(TestRepository.filesIn(workspace))
                .containsExactly("keep/a.txt", "keep/nested/b.txt");
        assertThat(new File(workspace, "keep/a.txt")).hasContent("a-updated");
        assertThat(new File(workspace, "drop/new.txt")).doesNotExist();
    }

    @Test
    void shouldLeaveTheWorkingCopyClean() {
        Git.in(workspace, configuration("keep/*")).checkout(origin.head());

        // A dirty status here would have GoCD believe the workspace had been tampered with.
        assertThat(TestRepository.gitIn(workspace, "status", "--porcelain").trim()).isEmpty();
    }

    @Test
    void shouldRemoveUntrackedFilesLeftBehindByAPreviousBuild() {
        Git.in(workspace, configuration("keep/*")).checkout(origin.head());
        write(new File(workspace, "leftover.txt"), "leftover");

        Git.in(workspace, configuration("keep/*")).checkout(origin.head());

        assertThat(new File(workspace, "leftover.txt")).doesNotExist();
    }

    @Test
    void shouldRemoveIgnoredFilesTooSoOneBuildCannotLeakIntoTheNext() {
        // -x as well as -d. Ignored build output is exactly what a previous job leaves behind.
        origin.write(".gitignore", "*.obj").commit("ignore build output");
        Git.in(workspace, configuration("*")).checkout(origin.head());
        write(new File(workspace, "stale.obj"), "from the last build");

        Git.in(workspace, configuration("*")).checkout(origin.head());

        assertThat(new File(workspace, "stale.obj")).doesNotExist();
    }

    @Test
    void shouldStillRespectTheSparsePathsForAShallowClone() {
        Git.in(workspace, configuration("keep/*", true, false)).checkout(origin.head());

        assertThat(TestRepository.filesIn(workspace))
                .containsExactly("keep/a.txt", "keep/nested/b.txt");
        assertThat(new File(workspace, ".git/shallow")).exists();
    }

    @Test
    void shouldDeepenAShallowCloneWhenAnOlderRevisionIsWanted() {
        // A re-run, or a pipeline pinned to a previous commit, asks for a revision outside a
        // depth-1 window. Failing here would make shallow unusable for anything but the tip.
        String first = origin.head();
        origin.write("keep/a.txt", "second").commit("second");

        Git.in(workspace, configuration("keep/*", true, false)).checkout(first);

        assertThat(new File(workspace, "keep/a.txt")).hasContent("a");
    }

    @Test
    void shouldPickUpRotatedCredentialsOnAnExistingCheckout() {
        // The remote is rewritten on every checkout, so a token replaced in GoCD takes effect without
        // anyone having to delete the agent's working directory.
        Git.in(workspace, configuration("keep/*")).checkout(origin.head());
        TestRepository.gitIn(workspace, "remote", "set-url", "origin", "https://wrong.invalid/r.git");

        Git.in(workspace, configuration("keep/*")).checkout(origin.head());

        assertThat(TestRepository.gitIn(workspace, "config", "--get", "remote.origin.url").trim())
                .isEqualTo(origin.url());
    }

    // -------------------------------------------------------------------- polling

    @Test
    void shouldReturnTheNewestCommitFirstWithItsModifiedFiles() {
        origin.write("keep/a.txt", "changed").commit("second commit");
        Git git = Git.in(flyweight, configuration("keep/*"));
        git.syncPollingRepository();

        List<Revision> revisions = git.log(null, List.of());

        assertThat(revisions).hasSize(1);
        Revision newest = revisions.get(0);
        assertThat(newest.comment()).isEqualTo("second commit");
        assertThat(newest.author()).contains("Test User").contains("test@example.com");
        assertThat(newest.timestamp()).isNotNull();
        assertThat(newest.modifiedFiles()).extracting(Revision.ModifiedFile::fileName)
                .containsExactly("keep/a.txt");
        assertThat(newest.modifiedFiles()).extracting(Revision.ModifiedFile::action)
                .containsExactly("modified");
    }

    @Test
    void shouldReportAddedAndDeletedActions() {
        origin.write("keep/added.txt", "x").delete("drop/c.txt").commit("add and delete");
        Git git = Git.in(flyweight, configuration("keep/*"));
        git.syncPollingRepository();

        Revision newest = git.log(null, List.of()).get(0);

        assertThat(newest.modifiedFiles())
                .extracting(file -> file.fileName() + ":" + file.action())
                .containsExactlyInAnyOrder("keep/added.txt:added", "drop/c.txt:deleted");
    }

    @Test
    void shouldReportTheFilesInTheFirstCommitOfARepository() {
        // diff-tree needs --root, or the initial commit has no parent to diff against and looks like
        // it changed nothing at all.
        Git git = Git.in(flyweight, configuration("keep/*"));
        git.syncPollingRepository();

        Revision initial = git.log(null, List.of()).get(0);

        assertThat(initial.modifiedFiles()).extracting(Revision.ModifiedFile::fileName)
                .containsExactlyInAnyOrder("keep/a.txt", "keep/nested/b.txt", "drop/c.txt", "root.txt");
    }

    @Test
    void shouldReportANonAsciiFileNameUnquoted() {
        // git C-quotes non-ASCII paths by default, which would reach GoCD's changes list verbatim as
        // "caf\303\251.txt". core.quotePath=false is what stops that.
        origin.write("keep/café.txt", "x").commit("add an accented name");
        Git git = Git.in(flyweight, configuration("keep/*"));
        git.syncPollingRepository();

        assertThat(git.log(null, List.of()).get(0).modifiedFiles())
                .extracting(Revision.ModifiedFile::fileName).containsExactly("keep/café.txt");
    }

    @Test
    void shouldReportAFileNameContainingASpace() {
        origin.write("keep/my file.txt", "x").commit("add a name with a space");
        Git git = Git.in(flyweight, configuration("keep/*"));
        git.syncPollingRepository();

        assertThat(git.log(null, List.of()).get(0).modifiedFiles())
                .extracting(Revision.ModifiedFile::fileName).containsExactly("keep/my file.txt");
    }

    @Test
    void shouldReportARenameAsOnePathPerFile() {
        // With rename detection on, git emits `R100<tab>old<tab>new`, which GoCD's revision format
        // cannot express — it would arrive as one file with a tab in its name. --no-renames keeps
        // every line to a single path regardless of anyone's diff.renames setting.
        origin.git("config", "diff.renames", "true");
        origin.move("root.txt", "moved.txt").commit("rename it");
        Git git = Git.in(flyweight, configuration("keep/*"));
        git.syncPollingRepository();

        assertThat(git.log(null, List.of()).get(0).modifiedFiles())
                .extracting(Revision.ModifiedFile::fileName)
                .containsExactlyInAnyOrder("root.txt", "moved.txt")
                .allSatisfy(name -> assertThat(name).doesNotContain("\t"));
    }

    @Test
    void shouldExcludeTheRevisionItWasAskedToStartAfter() {
        String first = origin.head();
        origin.write("keep/a.txt", "2").commit("second");
        origin.write("keep/a.txt", "3").commit("third");
        Git git = Git.in(flyweight, configuration("keep/*"));
        git.syncPollingRepository();

        List<Revision> since = git.log(first, List.of());

        assertThat(since).extracting(Revision::comment).containsExactly("third", "second");
    }

    @Test
    void shouldHideCommitsThatMissThePathspecsWhenFilteringByPath() {
        String first = origin.head();
        origin.write("drop/c.txt", "irrelevant").commit("only touches an excluded path");
        MaterialConfiguration filtered = configuration("keep/*", false, true);
        Git git = Git.in(flyweight, filtered);
        git.syncPollingRepository();

        assertThat(git.log(first, filtered.pathspecs())).isEmpty();

        origin.write("keep/a.txt", "relevant").commit("touches a watched path");
        git.syncPollingRepository();
        assertThat(git.log(first, filtered.pathspecs()))
                .extracting(Revision::comment).containsExactly("touches a watched path");
    }

    @Test
    void shouldReuseThePollingRepositoryAcrossCalls() {
        Git git = Git.in(flyweight, configuration("keep/*"));
        git.syncPollingRepository();
        origin.write("keep/a.txt", "again").commit("later");

        git.syncPollingRepository();

        assertThat(git.log(null, List.of()).get(0).comment()).isEqualTo("later");
    }

    @Test
    void shouldPollWithABareRepositoryBecausePollingNeedsNoWorkingTree() {
        Git.in(flyweight, configuration("keep/*")).syncPollingRepository();

        assertThat(new File(flyweight, "HEAD")).exists();
        assertThat(new File(flyweight, "keep")).doesNotExist();
    }

    // --------------------------------------------------------------- connectivity

    @Test
    void shouldNameTheMissingBranchWhenItCannotBeFound() {
        MaterialConfiguration configuration =
                propertiesOf("url", origin.url(), "branch", "no-such-branch");

        assertThatThrownBy(() -> Git.forRemote(configuration).checkConnection())
                .isInstanceOf(GitException.class)
                .hasMessageContaining("no-such-branch");
    }

    @Test
    void shouldFailClearlyWhenTheRepositoryDoesNotExist() {
        MaterialConfiguration configuration = propertiesOf(
                "url", tempDir.resolve("does-not-exist").toString(), "branch", "master");

        assertThatThrownBy(() -> Git.forRemote(configuration).checkConnection())
                .isInstanceOf(GitException.class)
                .hasMessageContaining("Cannot reach");
    }

    @Test
    void shouldConfirmAReachableBranch() {
        MaterialConfiguration configuration =
                propertiesOf("url", origin.url(), "branch", "master");

        Git.forRemote(configuration).checkConnection();
    }

    // --------------------------------------------------------------------- setup

    private MaterialConfiguration configuration(String sparsePaths) {
        return configuration(sparsePaths, false, false);
    }

    private MaterialConfiguration configuration(String sparsePaths, boolean shallow,
                                                boolean filterByPaths) {
        return propertiesOf("url", origin.url(),
                "branch", "master",
                "sparse_paths", sparsePaths,
                "shallow", String.valueOf(shallow),
                "filter_by_paths", String.valueOf(filterByPaths));
    }

    private static MaterialConfiguration propertiesOf(String... keysAndValues) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            raw.put(keysAndValues[i], Map.of("value", keysAndValues[i + 1]));
        }
        return MaterialConfiguration.from(raw);
    }

    private static void write(File file, String content) {
        try {
            Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
