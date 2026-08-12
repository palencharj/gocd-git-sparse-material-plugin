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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One commit, in the shape GoCD's SCM extension expects to receive it. */
public final class Revision {

    /**
     * A file touched by a commit.
     *
     * <p>GoCD models exactly three actions, while git reports more — renames, copies, type changes,
     * merges. Anything that is not unambiguously an addition or a deletion is reported as a
     * modification, which is both true and the only option the contract allows.
     */
    public record ModifiedFile(String fileName, String action) {

        public static final String ADDED = "added";
        public static final String MODIFIED = "modified";
        public static final String DELETED = "deleted";

        public static ModifiedFile from(char status, String fileName) {
            return new ModifiedFile(fileName, switch (status) {
                case 'A' -> ADDED;
                case 'D' -> DELETED;
                default -> MODIFIED;
            });
        }
    }

    private final String sha;
    private final Instant timestamp;
    private final String author;
    private final String comment;
    private final List<ModifiedFile> modifiedFiles = new ArrayList<>();

    public Revision(String sha, Instant timestamp, String author, String comment) {
        this.sha = sha;
        this.timestamp = timestamp;
        this.author = author;
        this.comment = comment;
    }

    public String sha() {
        return sha;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public String author() {
        return author;
    }

    public String comment() {
        return comment;
    }

    public List<ModifiedFile> modifiedFiles() {
        return modifiedFiles;
    }

    void add(ModifiedFile modifiedFile) {
        modifiedFiles.add(modifiedFile);
    }

    @Override
    public String toString() {
        return "Revision{" + sha + " by " + author + " at " + timestamp + "}";
    }
}
