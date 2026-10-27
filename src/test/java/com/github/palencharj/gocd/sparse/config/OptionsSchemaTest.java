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
package com.github.palencharj.gocd.sparse.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OptionsSchemaTest {

    private static final Path CHECKED_IN = Path.of("docs", "options.schema.json");

    @Test
    void shouldMatchTheCheckedInSchemaSoTheDocumentedOptionsCannotDrift() throws IOException {
        if (System.getenv("UPDATE_SCHEMA") != null) {
            Files.createDirectories(CHECKED_IN.getParent());
            Files.writeString(CHECKED_IN, OptionsSchema.json(), StandardCharsets.UTF_8);
        }

        assertThat(CHECKED_IN).as("run the tests once with UPDATE_SCHEMA=1 to regenerate").exists();
        assertThat(Files.readString(CHECKED_IN, StandardCharsets.UTF_8).replace("\r\n", "\n"))
                .as("docs/options.schema.json is generated from ScmProperty; run the tests with"
                        + " UPDATE_SCHEMA=1 to regenerate it")
                .isEqualTo(OptionsSchema.json());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldListExactlyTheOptionsThePluginAcceptsAndForbidAnythingElse() {
        Map<String, Object> schema = new Gson().fromJson(OptionsSchema.json(), Map.class);

        assertThat(((Map<String, Object>) schema.get("properties")).keySet())
                .containsExactlyElementsOf(ScmProperty.keys());
        assertThat(schema.get("additionalProperties")).isEqualTo(false);
        assertThat((List<String>) schema.get("required")).containsExactly("url", "sparse_paths");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRestrictFlagsToTrueOrFalseAndMarkIdentity() {
        Map<String, Object> schema = new Gson().fromJson(OptionsSchema.json(), Map.class);
        Map<String, Map<String, Object>> properties =
                (Map<String, Map<String, Object>>) schema.get("properties");

        for (String flag : List.of("shallow", "filter_by_paths", "partial_clone")) {
            assertThat((List<String>) properties.get(flag).get("enum")).containsExactly("true", "false");
            assertThat(properties.get(flag).get("x-gocd-part-of-identity")).isEqualTo(false);
        }
        assertThat(properties.get("sparse_paths").get("x-gocd-part-of-identity")).isEqualTo(true);
        assertThat(properties.get("password").get("x-gocd-secret")).isEqualTo(true);
    }
}
