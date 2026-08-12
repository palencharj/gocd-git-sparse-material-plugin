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

import java.time.Duration;

/**
 * A child process that behaves exactly as a test tells it to.
 *
 * <p>Run by {@link CommandLineTest}, which re-launches this JVM into this class rather than using a
 * shell script or a contrived git invocation. Nothing else gives precise control over how much each
 * stream produces and in what order, and the failure mode being guarded against only appears past a
 * pipe buffer's worth of output — a threshold no git command can be relied on to cross on demand, on
 * every platform.
 *
 * <p>A top-level class rather than one nested in the test, so its name has no {@code $} in it and can
 * be passed to {@code java} without quoting games on any platform.
 */
public final class ChildProcess {

    private ChildProcess() {
    }

    public static void main(String[] args) throws InterruptedException {
        switch (args[0]) {
            case "streams" -> {
                System.out.println("this went to stdout");
                System.err.println("this went to stderr");
            }
            case "flood" -> flood(Integer.parseInt(args[1]));
            case "hang" -> Thread.sleep(Duration.ofMinutes(10).toMillis());
            case "exit" -> System.exit(Integer.parseInt(args[1]));
            case "echo" -> System.out.println(args[1]);
            case "secret" -> {
                System.out.println("cloning https://alice:" + args[1] + "@example.com/r.git");
                System.err.println("fatal: authentication failed for " + args[1]);
                System.exit(1);
            }
            default -> throw new IllegalArgumentException("Unknown mode " + args[0]);
        }
        System.out.flush();
        System.err.flush();
    }

    /** Writes to both streams at once, so neither can be drained to completion on its own. */
    private static void flood(int bytesEach) {
        String chunk = "x".repeat(8192);
        for (int written = 0; written < bytesEach; written += chunk.length()) {
            System.out.print(chunk);
            System.err.print(chunk);
            System.out.flush();
            System.err.flush();
        }
    }
}
