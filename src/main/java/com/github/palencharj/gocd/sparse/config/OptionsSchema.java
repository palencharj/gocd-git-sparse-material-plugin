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

import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSON Schema for the {@code options:} block of a config-repo material, generated from
 * {@link ScmProperty} so the documented options cannot drift from the enforced ones.
 *
 * <p>The checked-in copy at {@code docs/options.schema.json} is what a YAML linter consumes; a test
 * fails when it differs from {@link #json()}, and says how to regenerate it.
 *
 * <p>The schema is deliberately a little stricter than the plugin: flags are {@code "true"} or
 * {@code "false"} only, whereas the plugin treats any other text as false without complaint. A
 * linter is the right place to be strict about that, because rejecting it at runtime would break
 * configurations that are accepted today.
 */
public final class OptionsSchema {

    public static final String PLUGIN_ID = "com.github.palencharj.gocd.git-sparse-material";

    private OptionsSchema() {
    }

    public static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ScmProperty property : ScmProperty.values()) {
            properties.put(property.key(), describe(property));
            if (property.isRequired()) {
                required.add(property.key());
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("title", "options: for the Git sparse checkout material");
        schema.put("description", "The options: block of a pipelines-as-code material declared"
                + " with plugin_configuration.id " + PLUGIN_ID + " (version \"1\"). Every value is"
                + " a flat string. Generated from ScmProperty; do not edit by hand.");
        schema.put("x-gocd-plugin-id", PLUGIN_ID);
        schema.put("x-gocd-plugin-configuration-version", "1");
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> describe(ScmProperty property) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("type", "string");
        described.put("description", property.description());
        if (property.defaultValue() != null) {
            described.put("default", property.defaultValue());
        }
        if (property.isFlag()) {
            described.put("enum", List.of("true", "false"));
        } else if (property.isRequired()) {
            // At least one non-blank character, so an empty or whitespace-only value is caught.
            described.put("pattern", "\\S");
        }
        described.put("x-gocd-part-of-identity", property.isPartOfIdentity());
        if (property.isSecure()) {
            described.put("x-gocd-secret", true);
        }
        if (property.isMultiline()) {
            described.put("x-multiline", "one entry per line");
        }
        return described;
    }

    /** The schema as pretty-printed JSON with a trailing newline, exactly as checked in. */
    public static String json() {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                .toJson(schema()) + "\n";
    }
}
