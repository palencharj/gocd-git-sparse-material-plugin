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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One commit, in the shape GoCD's SCM extension expects to receive it. */
public final class Revision {

    /** A file touched by a commit. GoCD only understands these three actions. */
    public static final class ModifiedFile {
        public final String fileName;
        public final String action;

        public ModifiedFile(String fileName, String action) {
            this.fileName = fileName;
            this.action = action;
        }
    }

    public final String sha;
    public final Instant timestamp;
    public final String author;
    public final String comment;
    public final List<ModifiedFile> modifiedFiles = new ArrayList<>();

    public Revision(String sha, Instant timestamp, String author, String comment) {
        this.sha = sha;
        this.timestamp = timestamp;
        this.author = author;
        this.comment = comment;
    }

    /**
     * Maps git's {@code --name-status} letter onto one of GoCD's three actions. Git reports more
     * cases than GoCD models (renames, copies, type changes), so anything that is not clearly an
     * addition or a deletion is reported as a modification.
     */
    public static String actionFor(char status) {
        switch (status) {
            case 'A':
                return "added";
            case 'D':
                return "deleted";
            default:
                return "modified";
        }
    }
}
