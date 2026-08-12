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
package com.github.palencharj.gocd.sparse.scm;

import com.github.palencharj.gocd.sparse.git.Revision;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders a {@link Revision} in the shape GoCD's SCM extension reads it back from. */
public final class RevisionRepresenter {

    /**
     * GoCD parses these with a strict ISO-8601 parser that requires an offset, so the offset is not
     * optional. Emitting UTC with explicit milliseconds leaves nothing to infer.
     */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.UTC);

    private RevisionRepresenter() {
    }

    public static Map<String, Object> toJSON(Revision revision) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("revision", revision.sha());
        json.put("timestamp", TIMESTAMP.format(revision.timestamp()));
        json.put("user", revision.author());
        json.put("revisionComment", revision.comment());
        json.put("modifiedFiles", modifiedFiles(revision));
        return json;
    }

    public static List<Map<String, Object>> toJSON(List<Revision> revisions) {
        List<Map<String, Object>> json = new ArrayList<>(revisions.size());
        for (Revision revision : revisions) {
            json.add(toJSON(revision));
        }
        return json;
    }

    private static List<Map<String, String>> modifiedFiles(Revision revision) {
        List<Map<String, String>> files = new ArrayList<>();
        for (Revision.ModifiedFile file : revision.modifiedFiles()) {
            Map<String, String> json = new LinkedHashMap<>();
            json.put("fileName", file.fileName());
            json.put("action", file.action());
            files.add(json);
        }
        return files;
    }
}
