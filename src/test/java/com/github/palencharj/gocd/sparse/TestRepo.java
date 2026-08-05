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

/** A throwaway on-disk git repository, so the tests exercise the real git binary. */
final class TestRepo {

    private final File root;

    private TestRepo(File root) {
        this.root = root;
    }

    static TestRepo create(File root) {
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        TestRepo repo = new TestRepo(root);
        repo.git("init", "--quiet", "--initial-branch=master", ".");
        repo.git("config", "user.email", "test@example.com");
        repo.git("config", "user.name", "Test User");
        return repo;
    }

    File root() {
        return root;
    }

    /**
     * The repository location handed to the plugin.
     *
     * <p>A plain absolute path rather than a {@code file:} URL on purpose: {@link File#toURI()}
     * yields the single-slash {@code file:/path} form, which git parses as an scp-style remote with
     * the host {@code file}. Git clones local paths natively, so this sidesteps the issue entirely.
     */
    String url() {
        return root.getAbsolutePath();
    }

    TestRepo write(String path, String content) {
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

    TestRepo delete(String path) {
        //noinspection ResultOfMethodCallIgnored
        new File(root, path).delete();
        return this;
    }

    String commit(String message) {
        git("add", "-A");
        git("commit", "--quiet", "-m", message);
        return head();
    }

    String head() {
        return git("rev-parse", "HEAD").trim();
    }

    String git(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(command).directory(root).redirectErrorStream(true);
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
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

    /** Paths present in a working directory, ignoring {@code .git}, sorted for stable assertions. */
    static List<String> filesIn(File dir) {
        List<String> found = new ArrayList<>();
        collect(dir, dir, found);
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
