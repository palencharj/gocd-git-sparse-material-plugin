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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A single external command, built up fluently and then run.
 *
 * <p>Deliberately shaped after GoCD's own {@code com.thoughtworks.go.util.command.CommandLine} —
 * same factory method, same {@code withArg}/{@code withArgs}/{@code when} builder, same
 * {@code runOrBomb} returning a {@link ConsoleResult}. The server's class cannot be reused because
 * it is not part of the published plugin API, so the next best thing is to be indistinguishable
 * from it in use.
 *
 * <p>Three properties this class exists to guarantee, each of which was a real failure mode before
 * it was centralised:
 *
 * <ul>
 *   <li><b>Arguments are never interpreted by a shell.</b> They are passed to
 *       {@link ProcessBuilder} as a list, so a path containing a space, a quote or a semicolon is
 *       data rather than syntax. This is why sparse paths are line-delimited rather than
 *       space-delimited: real repositories contain both.</li>
 *   <li><b>Nothing ever prompts.</b> A build agent has no console. Left to itself git will block
 *       on a credential prompt until the job times out, so terminal prompting and every credential
 *       helper are disabled on every invocation.</li>
 *   <li><b>Output is redacted before anyone can see it.</b> A credential is passed to git inside a
 *       URL, so it can appear in stdout, stderr and exception text. Redaction is applied here,
 *       once, rather than trusted to each caller.</li>
 * </ul>
 */
public final class CommandLine {

    /**
     * Long enough for a first clone of a large monorepo over a slow link, short enough that a hung
     * process fails the job rather than occupying an agent indefinitely.
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

    private final String executable;
    private final List<String> arguments = new ArrayList<>();
    private final Map<String, String> env = new LinkedHashMap<>();
    private final List<SecretRedactor> secrets = new ArrayList<>();
    private File workingDirectory;
    private Charset encoding = StandardCharsets.UTF_8;
    private Duration timeout = DEFAULT_TIMEOUT;

    private CommandLine(String executable) {
        this.executable = executable;
    }

    public static CommandLine createCommandLine(String executable) {
        return new CommandLine(executable);
    }

    public CommandLine withArg(String argument) {
        arguments.add(argument);
        return this;
    }

    public CommandLine withArgs(String... args) {
        arguments.addAll(Arrays.asList(args));
        return this;
    }

    public CommandLine withArgs(List<String> args) {
        arguments.addAll(args);
        return this;
    }

    /**
     * Applies {@code thenDo} only when {@code condition} holds.
     *
     * <p>Lets a command that varies by configuration stay a single readable expression instead of
     * being assembled across a series of if-statements — the same reason GoCD's own CommandLine
     * has it.
     */
    public CommandLine when(boolean condition, Consumer<CommandLine> thenDo) {
        if (condition) {
            thenDo.accept(this);
        }
        return this;
    }

    public CommandLine withWorkingDir(File folder) {
        this.workingDirectory = folder;
        return this;
    }

    public CommandLine withEnv(String name, String value) {
        env.put(name, value);
        return this;
    }

    public CommandLine withEncoding(Charset encoding) {
        this.encoding = encoding;
        return this;
    }

    public CommandLine withTimeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public CommandLine withSecrets(List<SecretRedactor> redactors) {
        secrets.addAll(redactors);
        return this;
    }

    public CommandLine withSecret(SecretRedactor redactor) {
        secrets.add(redactor);
        return this;
    }

    /** The command as a person should see it: executable and arguments, secrets removed. */
    public String describe() {
        return redact(executable + " " + String.join(" ", arguments)).trim();
    }

    /**
     * Runs the command and returns its result.
     *
     * @param failOnNonZeroReturn when true, a non-zero exit throws instead of returning; use false
     *                            for commands whose failure is a legitimate answer, such as
     *                            querying a config key that may not be set
     */
    public ConsoleResult runOrBomb(boolean failOnNonZeroReturn) {
        ConsoleResult result = run();
        if (failOnNonZeroReturn && result.failed()) {
            throw new GitException(describe() + " failed: " + result.describe());
        }
        return result;
    }

    private ConsoleResult run() {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory);
        }
        builder.environment().putAll(noninteractiveEnvironment());
        builder.environment().putAll(env);

        Process process = null;
        try {
            process = builder.start();
            // Both pipes are drained on their own threads, and the main thread does nothing but wait
            // on the process. Two failure modes make that necessary rather than tidy:
            //
            //  - Draining one stream to EOF before starting on the other deadlocks as soon as the
            //    process writes more to the second than its pipe buffer holds. git writes progress
            //    and warnings to stderr, so `fetch --unshallow` on a large repository does exactly
            //    that, and the agent hangs with no output and no error.
            //  - Blocking on a read before waitFor makes the timeout unreachable. A process that
            //    hangs without writing anything would never be terminated, because nothing gets as
            //    far as asking how long it has been.
            StreamDrainer stdout = StreamDrainer.reading(process.getInputStream(), encoding);
            StreamDrainer stderr = StreamDrainer.reading(process.getErrorStream(), encoding);

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new GitException(describe() + " did not finish within " + timeout.toMinutes()
                        + " minutes and was terminated. " + stderr.describeSoFar());
            }
            return new ConsoleResult(process.exitValue(),
                    lines(stdout.text()), lines(stderr.text()));
        } catch (IOException e) {
            throw new GitException("Could not run '" + executable + "'. Is it installed and on the"
                    + " agent's PATH? " + redact(String.valueOf(e.getMessage())), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new GitException("Interrupted while running " + describe(), e);
        }
    }

    /**
     * Reads one of a process's output streams to EOF on a thread of its own.
     *
     * <p>A daemon thread, so a stream that never closes cannot keep the JVM — or a GoCD agent —
     * alive. The thread ends on its own once the process exits or is destroyed, because that closes
     * the pipe.
     */
    private static final class StreamDrainer {

        /**
         * How long to wait for a drainer to finish after the process has already exited. EOF should
         * arrive immediately; this only bounds the wait so a stuck reader cannot hang the caller.
         */
        private static final long SETTLE_MILLIS = 5_000;

        private final Thread thread;
        private final StringBuilder collected = new StringBuilder();
        private volatile IOException failure;

        private StreamDrainer(InputStream stream, Charset encoding) {
            this.thread = new Thread(() -> read(stream, encoding), "git-output-reader");
            this.thread.setDaemon(true);
        }

        static StreamDrainer reading(InputStream stream, Charset encoding) {
            StreamDrainer drainer = new StreamDrainer(stream, encoding);
            drainer.thread.start();
            return drainer;
        }

        private void read(InputStream stream, Charset encoding) {
            byte[] chunk = new byte[8192];
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try {
                int count;
                while ((count = stream.read(chunk)) != -1) {
                    buffer.write(chunk, 0, count);
                    synchronized (collected) {
                        // Decoded as it arrives so a timeout can still report what was said. Chunk
                        // boundaries can split a multi-byte character, so the whole buffer is decoded
                        // each time rather than appending the new bytes alone.
                        collected.setLength(0);
                        collected.append(buffer.toString(encoding));
                    }
                }
            } catch (IOException e) {
                failure = e;
            }
        }

        /** Everything the stream produced, once it has ended. */
        String text() throws IOException, InterruptedException {
            thread.join(SETTLE_MILLIS);
            if (failure != null) {
                throw failure;
            }
            return describeSoFar();
        }

        /** Whatever has arrived so far, safe to call while the stream is still open. */
        String describeSoFar() {
            synchronized (collected) {
                return collected.toString();
            }
        }
    }

    /**
     * Environment that makes git incapable of waiting for a human.
     *
     * <p>{@code GIT_TERMINAL_PROMPT=0} covers git's own prompt; {@code GIT_ASKPASS=echo} covers the
     * askpass helper; {@code GCM_INTERACTIVE=never} covers Git Credential Manager on Windows
     * agents, which otherwise pops a dialog nobody will ever see. {@code LC_ALL=C} keeps messages
     * parseable regardless of the agent's locale.
     */
    private static Map<String, String> noninteractiveEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_ASKPASS", "echo");
        environment.put("GCM_INTERACTIVE", "never");
        environment.put("LC_ALL", "C");
        return environment;
    }

    private List<String> lines(String text) {
        List<String> result = new ArrayList<>();
        for (String line : redact(text).split("\\R")) {
            result.add(line);
        }
        // A trailing newline yields one empty element; drop it so callers do not have to.
        if (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    private String redact(String text) {
        String redacted = text == null ? "" : text;
        for (SecretRedactor secret : secrets) {
            redacted = secret.redactFrom(redacted);
        }
        return redacted;
    }

}
