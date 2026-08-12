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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A throwaway on-disk git repository.
 *
 * <p>The tests drive the real {@code git} binary rather than a stub, because what is being tested is
 * how git behaves — the ordering of {@code sparse-checkout} against {@code reset}, what
 * {@code disable} leaves behind, how {@code diff-tree} formats its output. A stub would only assert
 * that this code does what it was written to do.
 */
public final class TestRepository {

    private final File root;

    private TestRepository(File root) {
        this.root = root;
    }

    public static TestRepository create(File root) {
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        TestRepository repository = new TestRepository(root);
        repository.git("init", "--quiet", "--initial-branch=master", ".");
        repository.git("config", "user.email", "test@example.com");
        repository.git("config", "user.name", "Test User");
        return repository;
    }

    public File root() {
        return root;
    }

    /**
     * The repository location to hand the plugin.
     *
     * <p>A plain absolute path rather than a {@code file:} URL: {@link File#toURI()} produces the
     * single-slash {@code file:/path} form, which git reads as an scp-style remote whose host is
     * {@code file}. git clones local paths natively, so this avoids the question.
     */
    public String url() {
        return root.getAbsolutePath();
    }

    public TestRepository write(String path, String content) {
        try {
            File file = new File(root, path);
            //noinspection ResultOfMethodCallIgnored
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
            return this;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public TestRepository delete(String path) {
        //noinspection ResultOfMethodCallIgnored
        new File(root, path).delete();
        return this;
    }

    public TestRepository move(String from, String to) {
        git("mv", from, to);
        return this;
    }

    public String commit(String message) {
        git("add", "-A");
        git("commit", "--quiet", "-m", message);
        return head();
    }

    public String head() {
        return git("rev-parse", "HEAD").trim();
    }

    public String git(String... args) {
        return gitIn(root, args);
    }

    /** Runs git in an arbitrary directory and returns its combined output, ignoring the exit code. */
    public static String gitIn(File directory, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        try {
            ProcessBuilder builder =
                    new ProcessBuilder(command).directory(directory).redirectErrorStream(true);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Paths in a working directory, ignoring {@code .git}, sorted so assertions are stable. */
    public static List<String> filesIn(File directory) {
        List<String> found = new ArrayList<>();
        collect(directory, directory, found);
        found.sort(String::compareTo);
        return found;
    }

    private static void collect(File base, File current, List<String> found) {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.getName().equals(".git")) {
                continue;
            }
            if (child.isDirectory()) {
                collect(base, child, found);
            } else {
                found.add(base.toPath().relativize(child.toPath()).toString().replace('\\', '/'));
            }
        }
    }
}
