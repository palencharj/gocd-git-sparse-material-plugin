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

import java.util.List;

/**
 * The outcome of one command: its exit code and its two output streams, already redacted.
 *
 * <p>Named and shaped after GoCD's own {@code ConsoleResult} so it reads the same way to anyone
 * familiar with the server. Redaction happens on the way in rather than on the way out: once a
 * result exists, every accessor is safe to log, which removes the possibility of one caller
 * forgetting.
 */
public final class ConsoleResult {

    private final int returnValue;
    private final List<String> output;
    private final List<String> error;

    ConsoleResult(int returnValue, List<String> output, List<String> error) {
        this.returnValue = returnValue;
        this.output = List.copyOf(output);
        this.error = List.copyOf(error);
    }

    public int returnValue() {
        return returnValue;
    }

    public boolean failed() {
        return returnValue != 0;
    }

    public List<String> output() {
        return output;
    }

    public List<String> error() {
        return error;
    }

    public String outputAsString() {
        return String.join("\n", output);
    }

    public String errorAsString() {
        return String.join("\n", error);
    }

    /**
     * The most useful single line to show a human when a command fails.
     *
     * <p>git reports failures on stderr and progress on stdout, so stderr is preferred; stdout is
     * the fallback for the handful of subcommands that report problems there. When a command fails
     * silently — which happens — the exit code is at least something to go on, and is better than
     * an empty message that leaves the reader guessing.
     */
    public String describe() {
        String stderr = errorAsString().trim();
        if (!stderr.isEmpty()) {
            return stderr;
        }
        String stdout = outputAsString().trim();
        return stdout.isEmpty() ? "exit code " + returnValue : stdout;
    }
}
