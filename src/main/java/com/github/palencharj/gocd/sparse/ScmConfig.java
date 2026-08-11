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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The material's configuration, as GoCD hands it to us in every request.
 *
 * <p>Which fields are {@code part-of-identity} matters a great deal: GoCD derives the material
 * fingerprint from exactly those, and the fingerprint is what ties a pipeline to its material
 * history. Only {@link #URL} and {@link #BRANCH} identify the material. The sparse paths
 * deliberately do <em>not</em> — they describe how much of the repository we lay down, not which
 * repository it is, so editing them must not orphan a pipeline's history.
 */
public final class ScmConfig {

    public static final String URL = "url";
    public static final String BRANCH = "branch";
    public static final String SPARSE_PATHS = "sparse_paths";
    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";
    public static final String SHALLOW = "shallow";
    public static final String FILTER_BY_PATHS = "filter_by_paths";

    public static final String DEFAULT_BRANCH = "master";

    /**
     * Every property this plugin understands, in display order.
     *
     * <p>Used to reject anything else during validation. That matters more than it looks: GoCD
     * folds a property into the material fingerprint when the plugin declares no metadata for it
     * (see {@code SCM#getFingerprint}), so a key we do not know still changes the material's
     * identity while being silently ignored by us. A typo in a config repo's {@code options:}
     * would therefore detach a material from its history and produce no error anywhere. Rejecting
     * unknown keys here is the only place that can be caught.
     */
    public static final List<String> KNOWN_KEYS = List.of(
            URL, BRANCH, SPARSE_PATHS, USERNAME, PASSWORD, SHALLOW, FILTER_BY_PATHS);

    private final String url;
    private final String branch;
    private final String sparsePaths;
    private final String username;
    private final String password;
    private final boolean shallow;
    private final boolean filterByPaths;

    private ScmConfig(String url, String branch, String sparsePaths, String username,
                      String password, boolean shallow, boolean filterByPaths) {
        this.url = url;
        this.branch = branch;
        this.sparsePaths = sparsePaths;
        this.username = username;
        this.password = password;
        this.shallow = shallow;
        this.filterByPaths = filterByPaths;
    }

    /**
     * Reads the {@code scm-configuration} block of a request, which GoCD sends as
     * {@code {"url": {"value": "..."}, ...}}.
     */
    @SuppressWarnings("unchecked")
    public static ScmConfig from(Map<String, Object> scmConfiguration) {
        return new ScmConfig(
                value(scmConfiguration, URL),
                blankToDefault(value(scmConfiguration, BRANCH), DEFAULT_BRANCH),
                value(scmConfiguration, SPARSE_PATHS),
                value(scmConfiguration, USERNAME),
                value(scmConfiguration, PASSWORD),
                Boolean.parseBoolean(value(scmConfiguration, SHALLOW)),
                Boolean.parseBoolean(value(scmConfiguration, FILTER_BY_PATHS))
        );
    }

    @SuppressWarnings("unchecked")
    private static String value(Map<String, Object> config, String key) {
        Object entry = config == null ? null : config.get(key);
        if (!(entry instanceof Map)) {
            return null;
        }
        Object v = ((Map<String, Object>) entry).get("value");
        return v == null ? null : v.toString();
    }

    private static String blankToDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public String url() {
        return url == null ? null : url.trim();
    }

    public String branch() {
        return branch;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public boolean shallow() {
        return shallow;
    }

    public boolean filterByPaths() {
        return filterByPaths;
    }

    public String rawSparsePaths() {
        return sparsePaths;
    }

    /**
     * The configured paths, one per line, with blank lines and surrounding whitespace dropped.
     *
     * <p>Line-delimited rather than space- or comma-delimited on purpose: real repositories contain
     * paths with spaces in them, and a comma is a legal character in a filename.
     */
    public List<String> sparsePaths() {
        List<String> paths = new ArrayList<>();
        if (isBlank(sparsePaths)) {
            return paths;
        }
        for (String line : sparsePaths.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                paths.add(trimmed);
            }
        }
        return paths;
    }

    /**
     * Pathspecs used to decide whether a commit is interesting, when {@code filter_by_paths} is on.
     *
     * <p>Sparse-checkout patterns are gitignore-style and may be negated with {@code !}, which is
     * meaningless to {@code git log}. We drop negations rather than pass them through and have git
     * reject the whole pathspec.
     */
    public List<String> pathspecs() {
        List<String> specs = new ArrayList<>();
        for (String path : sparsePaths()) {
            if (!path.startsWith("!")) {
                specs.add(path.startsWith("/") ? path.substring(1) : path);
            }
        }
        return specs;
    }
}
