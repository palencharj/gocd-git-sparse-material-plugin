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

import com.github.palencharj.gocd.sparse.MaterialException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One material's configuration, as GoCD sends it on every request.
 *
 * <p>GoCD delivers configuration as {@code {"url": {"value": "..."}, ...}} — a map of maps, with
 * secure values already decrypted and any {@code {{SECRET:...}}} reference already resolved by the
 * server. This class is the only place that shape is understood; everything downstream works with
 * typed accessors.
 *
 * <p>Instances are immutable and carry no server state, which is what lets the same configuration
 * be used from the admin UI, the REST API and a config repo with identical behaviour.
 */
public final class MaterialConfiguration {

    /** One thing wrong with a configuration, attributed to the property responsible. */
    public record ValidationError(String key, String message) {
    }

    private final Map<String, String> values;
    private final List<String> declaredKeys;

    private MaterialConfiguration(Map<String, String> values, List<String> declaredKeys) {
        this.values = Map.copyOf(values);
        this.declaredKeys = List.copyOf(declaredKeys);
    }

    @SuppressWarnings("unchecked")
    public static MaterialConfiguration from(Map<String, Object> scmConfiguration) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        if (scmConfiguration != null) {
            scmConfiguration.forEach((key, holder) -> {
                keys.add(key);
                if (holder instanceof Map) {
                    Object value = ((Map<String, Object>) holder).get("value");
                    values.put(key, value == null ? null : value.toString());
                }
            });
        }
        return new MaterialConfiguration(stripNulls(values), keys);
    }

    private static Map<String, String> stripNulls(Map<String, String> values) {
        Map<String, String> present = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value != null) {
                present.put(key, value);
            }
        });
        return present;
    }

    private String valueOf(ScmProperty property) {
        String value = values.get(property.key());
        return isBlank(value) ? property.defaultValue() : value.trim();
    }

    public String url() {
        return valueOf(ScmProperty.URL);
    }

    public String branch() {
        return valueOf(ScmProperty.BRANCH);
    }

    public String username() {
        return valueOf(ScmProperty.USERNAME);
    }

    public String password() {
        // Not trimmed via valueOf: leading or trailing whitespace could be part of a token.
        return values.get(ScmProperty.PASSWORD.key());
    }

    public boolean isShallow() {
        return Boolean.parseBoolean(valueOf(ScmProperty.SHALLOW));
    }

    public boolean isFilteredByPaths() {
        return Boolean.parseBoolean(valueOf(ScmProperty.FILTER_BY_PATHS));
    }

    /** Properties present in the request that this plugin does not declare. */
    public List<String> unknownKeys() {
        List<String> unknown = new ArrayList<>(declaredKeys);
        unknown.removeAll(ScmProperty.keys());
        return unknown;
    }

    /**
     * The configured patterns, one per line, with blank lines and surrounding whitespace discarded.
     *
     * <p>Line-delimited rather than space- or comma-delimited because real repositories contain
     * paths with spaces in them, and a comma is a legal character in a filename. Arguments reach git
     * as a list rather than a command string, so a path with a space needs no quoting anywhere.
     */
    public List<String> sparsePaths() {
        List<String> paths = new ArrayList<>();
        String configured = values.get(ScmProperty.SPARSE_PATHS.key());
        if (isBlank(configured)) {
            return paths;
        }
        for (String line : configured.split("\\R")) {
            String pattern = line.trim();
            if (!pattern.isEmpty()) {
                paths.add(pattern);
            }
        }
        return paths;
    }

    /**
     * Pathspecs for deciding whether a commit is interesting, when triggering is filtered by path.
     *
     * <p>Sparse-checkout patterns are gitignore-style and may be negated with {@code !}, which is
     * meaningless to {@code git log}. Negations are dropped rather than passed through, because git
     * rejects the whole pathspec if any element is invalid — which would turn a narrowing
     * optimisation into a hard failure.
     */
    public List<String> pathspecs() {
        List<String> pathspecs = new ArrayList<>();
        for (String path : sparsePaths()) {
            if (!path.startsWith("!")) {
                pathspecs.add(path.startsWith("/") ? path.substring(1) : path);
            }
        }
        return pathspecs;
    }

    /**
     * Everything wrong with this configuration, or an empty list.
     *
     * <p>Answers {@code validate-scm-configuration}, and is re-run at runtime by
     * {@link #assertUsable()}.
     */
    public List<ValidationError> validate() {
        List<ValidationError> errors = new ArrayList<>();

        for (String unknown : unknownKeys()) {
            errors.add(new ValidationError(unknown, "Unrecognised property '" + unknown
                    + "'. GoCD includes properties this plugin does not declare in the material's"
                    + " fingerprint, so a typo here silently changes which material a pipeline"
                    + " points at. Valid properties are " + ScmProperty.keys() + "."));
        }

        if (isBlank(url())) {
            errors.add(new ValidationError(ScmProperty.URL.key(), "Repository URL is required."));
        }

        List<String> paths = sparsePaths();
        if (paths.isEmpty()) {
            errors.add(new ValidationError(ScmProperty.SPARSE_PATHS.key(),
                    "Enter at least one path, one per line. This material exists to restrict the"
                            + " checkout, so an empty value is a misconfiguration rather than a"
                            + " request for everything — use GoCD's built-in Git material for a"
                            + " full checkout."));
        } else if (pathspecs().isEmpty()) {
            errors.add(new ValidationError(ScmProperty.SPARSE_PATHS.key(),
                    "Every pattern is an exclusion, so nothing would be checked out. git accepts"
                            + " this and produces an empty working directory, which fails the build"
                            + " far from the cause. Add at least one pattern that selects files."));
        }

        for (String path : paths) {
            if (path.contains("..")) {
                errors.add(new ValidationError(ScmProperty.SPARSE_PATHS.key(), "'" + path
                        + "' looks like it escapes the repository. Patterns are relative to the"
                        + " repository root."));
                break;
            }
        }

        return errors;
    }

    /**
     * Refuses to poll or check out an unusable configuration, reporting every problem at once.
     *
     * <p>Checked again here, rather than trusting {@code validate-scm-configuration}, because GoCD
     * never invokes that handler for a material defined in a config repo. The admin UI and the SCM
     * API both go through {@code PluggableScmService} and are validated; a config repo is turned
     * straight into configuration objects by {@code ConfigConverter} and applied on poll with no
     * approval gate. Runtime is therefore the last opportunity to reject something unusable.
     *
     * <p>Failing loudly beats the alternative. With no usable paths this material would quietly
     * check out the whole repository — giving none of the behaviour that was asked for, and no clue
     * why.
     */
    public void assertUsable() {
        List<ValidationError> errors = validate();
        if (errors.isEmpty()) {
            return;
        }
        // Each message is prefixed with the property it belongs to. In the UI that attribution comes
        // from the field the message is attached to; in a console log it has to be in the text, or
        // the reader is left guessing which setting to go and fix.
        StringBuilder message = new StringBuilder("This material is not configured correctly.");
        for (ValidationError error : errors) {
            message.append(' ').append(error.key()).append(": ").append(error.message());
        }
        throw new MaterialException(message.toString());
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
