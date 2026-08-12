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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Process handling, tested against a child process whose behaviour these tests dictate.
 *
 * <p>The child is this JVM re-launched into {@link ChildProcess}, whose modes each reproduce one
 * thing a real git invocation does that {@link CommandLine} has to survive.
 */
class CommandLineTest {

    /** Comfortably past a pipe buffer, which is 64KB on Linux and smaller on Windows. */
    private static final int PAST_THE_PIPE_BUFFER = 1_000_000;

    @Test
    void shouldCaptureStdoutAndStderrSeparately() {
        ConsoleResult result = fixture("streams").runOrBomb(true);

        assertThat(result.outputAsString()).contains("this went to stdout")
                .doesNotContain("this went to stderr");
        assertThat(result.errorAsString()).contains("this went to stderr")
                .doesNotContain("this went to stdout");
    }

    @Test
    @Timeout(120)
    void shouldNotDeadlockWhenACommandFillsBothPipeBuffers() {
        // Draining one stream to EOF before starting the other blocks the child as soon as it fills
        // the other pipe, and neither side can move. `git fetch --unshallow` on a large repository
        // produces exactly this much stderr, so the failure is a hung agent with no output at all.
        ConsoleResult result = fixture("flood", String.valueOf(PAST_THE_PIPE_BUFFER)).runOrBomb(true);

        assertThat(result.outputAsString()).hasSizeGreaterThanOrEqualTo(PAST_THE_PIPE_BUFFER);
        assertThat(result.errorAsString()).hasSizeGreaterThanOrEqualTo(PAST_THE_PIPE_BUFFER);
    }

    @Test
    @Timeout(120)
    void shouldTimeOutRatherThanWaitOnAProcessThatNeverFinishes() {
        // Reachable only because the main thread waits on the process rather than on a read. Blocking
        // on output first would make the timeout unreachable for the case it exists to cover: a
        // process that hangs silently.
        assertThatThrownBy(() -> fixture("hang")
                .withTimeout(Duration.ofSeconds(1))
                .runOrBomb(true))
                .isInstanceOf(GitException.class)
                .hasMessageContaining("did not finish within");
    }

    @Test
    void shouldReportANonZeroExitWithoutThrowingWhenAskedNotTo() {
        // Used for commands whose failure is a legitimate answer, such as reading a config key that
        // may not be set.
        ConsoleResult result = fixture("exit", "3").runOrBomb(false);

        assertThat(result.returnValue()).isEqualTo(3);
        assertThat(result.failed()).isTrue();
    }

    @Test
    void shouldExplainWhatToDoWhenTheExecutableIsMissing() {
        assertThatThrownBy(() -> CommandLine.createCommandLine("definitely-not-a-real-binary")
                .runOrBomb(true))
                .isInstanceOf(GitException.class)
                .hasMessageContaining("PATH");
    }

    @Test
    void shouldPassArgumentsToTheProcessWithoutAShellSeeingThem() {
        // Arguments go to ProcessBuilder as a list, so shell metacharacters arrive as data. This is
        // what lets a sparse path contain a space or a semicolon and still mean itself.
        //
        // A literal double quote is deliberately not in here. Windows has no argument vector — the OS
        // API takes one command string — so the JDK has to quote on the way out, and an embedded `"`
        // does not survive that. It is also not a legal character in a Windows filename, so nothing
        // real is lost; asserting otherwise would just be asserting something untrue.
        String hostile = "a b; rm -rf /; $(whoami) `id` 'single' | & > < %PATH% ~ !bang";

        ConsoleResult result = fixture("echo", hostile).runOrBomb(true);

        assertThat(result.outputAsString().trim()).isEqualTo(hostile);
    }

    @Test
    void shouldRedactASecretFromEveryStreamItCanAppearIn() {
        RemoteUrl remote = RemoteUrl.of("https://example.com/r.git", "alice", "s3cr3t");

        ConsoleResult result = fixture("secret", "s3cr3t").withSecret(remote).runOrBomb(false);

        assertThat(result.outputAsString()).doesNotContain("s3cr3t");
        assertThat(result.errorAsString()).doesNotContain("s3cr3t");
    }

    @Test
    void shouldRedactASecretFromTheDescriptionUsedInFailureMessages() {
        RemoteUrl remote = RemoteUrl.of("https://example.com/r.git", "alice", "s3cr3t");

        CommandLine command = CommandLine.createCommandLine("git")
                .withArgs("clone", remote.forCommandLine())
                .withSecret(remote);

        assertThat(command.describe()).doesNotContain("s3cr3t").contains("******");
    }

    @Test
    void shouldApplyAConditionalArgumentOnlyWhenTheConditionHolds() {
        assertThat(CommandLine.createCommandLine("git")
                .withArg("clone")
                .when(true, command -> command.withArg("--depth=1"))
                .when(false, command -> command.withArg("--bare"))
                .describe())
                .isEqualTo("git clone --depth=1");
    }

    /** A {@link CommandLine} that re-launches this JVM into {@link ChildProcess}. */
    private static CommandLine fixture(String... args) {
        return CommandLine.createCommandLine(javaBinary())
                .withArgs("-cp", System.getProperty("java.class.path"))
                .withArg(ChildProcess.class.getName())
                .withArgs(args);
    }

    private static String javaBinary() {
        return ProcessHandle.current().info().command()
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot find the java binary that is running this test."));
    }
}
