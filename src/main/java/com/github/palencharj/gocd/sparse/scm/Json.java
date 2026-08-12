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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The plugin's only contact with a JSON library, so swapping it stays a one-file change.
 *
 * <p>Package-private: JSON is how this package talks to GoCD and is not a concern anywhere else in
 * the plugin.
 */
final class Json {

    private static final Gson GSON = new Gson();

    private Json() {
    }

    static String toJson(Object value) {
        return GSON.toJson(value);
    }

    /**
     * Parses a request body into a map, treating an absent or empty body as an empty one.
     *
     * <p>Every SCM request body is a JSON object, and several are legitimately empty —
     * {@code scm-configuration} and {@code scm-view} carry nothing at all.
     */
    static Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> parsed =
                GSON.fromJson(json, new TypeToken<Map<String, Object>>() { }.getType());
        return parsed == null ? new LinkedHashMap<>() : parsed;
    }
}
