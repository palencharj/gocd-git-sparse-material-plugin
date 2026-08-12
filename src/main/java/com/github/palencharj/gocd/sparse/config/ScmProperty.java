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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every property this material understands, and the metadata GoCD needs about each one.
 *
 * <p>This enum is the single source of truth. The {@code scm-configuration} response, the whitelist
 * used to reject unknown properties, and the typed accessors in {@link MaterialConfiguration} are
 * all derived from it, so they cannot drift apart. That matters more here than it usually would,
 * because of how GoCD computes material identity.
 *
 * <h2>Why {@code partOfIdentity} is the most consequential flag on this page</h2>
 *
 * <p>GoCD derives a material's fingerprint from the properties marked part-of-identity, and that
 * fingerprint is what ties a pipeline to its build history. Two rules follow, and both have bitten
 * this plugin in production:
 *
 * <ul>
 *   <li><b>A property GoCD has no metadata for is treated as part of the identity.</b> In
 *       {@code SCM#getFingerprint} an absent declaration means "include it", so a misspelled
 *       property silently changes which material a pipeline is pointing at. Nothing upstream
 *       reports this, which is why unknown properties are rejected outright.</li>
 *   <li><b>Adding or removing a declared property re-fingerprints existing materials.</b> Treat any
 *       change to this enum as a migration, not a refactor.</li>
 * </ul>
 *
 * <p>The paths are part of the identity on purpose. Two materials on the same repository and branch
 * restricted to different paths are genuinely different materials; sharing one fingerprint would
 * let fan-in resolve a pipeline against a working copy that never contained its code. The price is
 * that editing the paths starts a fresh material history, and that two pipelines wanting the same
 * subset must share one SCM configuration — GoCD rejects a second one with an identical spec.
 * Credentials and the behaviour-only flags are excluded, so rotating a token or toggling either
 * flag leaves history intact.
 */
public enum ScmProperty {

    URL(spec("url").displayName("Repository URL").partOfIdentity().required()),

    BRANCH(spec("branch").displayName("Branch").defaultValue("master").partOfIdentity()),

    SPARSE_PATHS(spec("sparse_paths").displayName("Paths to check out").partOfIdentity().required()),

    USERNAME(spec("username").displayName("Username")),

    PASSWORD(spec("password").displayName("Password").secure()),

    SHALLOW(spec("shallow").displayName("Shallow clone").defaultValue("false")),

    FILTER_BY_PATHS(spec("filter_by_paths")
            .displayName("Only trigger on changes under these paths")
            .defaultValue("false"));

    private final Spec spec;

    ScmProperty(Spec spec) {
        this.spec = spec;
    }

    private static Spec spec(String key) {
        return new Spec(key);
    }

    public String key() {
        return spec.key;
    }

    public String defaultValue() {
        return spec.defaultValue;
    }

    public boolean isPartOfIdentity() {
        return spec.partOfIdentity;
    }

    public boolean isSecure() {
        return spec.secure;
    }

    public boolean isRequired() {
        return spec.required;
    }

    public static List<String> keys() {
        return Arrays.stream(values()).map(ScmProperty::key).toList();
    }

    public static Optional<ScmProperty> forKey(String key) {
        return Arrays.stream(values()).filter(property -> property.key().equals(key)).findFirst();
    }

    /**
     * The {@code scm-configuration} response, in declaration order.
     *
     * <p>{@code display-order} is a string rather than a number on purpose: GoCD reads it with
     * {@code Integer.valueOf((String) value)}, so a JSON number arrives as a Double and rejects the
     * entire configuration response rather than just that field.
     */
    public static Map<String, Object> metadata() {
        Map<String, Object> configuration = new LinkedHashMap<>();
        ScmProperty[] properties = values();
        for (int order = 0; order < properties.length; order++) {
            configuration.put(properties[order].key(), properties[order].describe(order));
        }
        return configuration;
    }

    private Map<String, Object> describe(int displayOrder) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("display-name", spec.displayName);
        if (spec.defaultValue != null) {
            described.put("default-value", spec.defaultValue);
        }
        described.put("part-of-identity", spec.partOfIdentity);
        described.put("secure", spec.secure);
        described.put("required", spec.required);
        described.put("display-order", String.valueOf(displayOrder));
        return described;
    }

    /**
     * Fluent description of one property, so each enum constant reads as a sentence rather than as
     * a row of unexplained booleans.
     */
    private static final class Spec {
        private final String key;
        private String displayName;
        private String defaultValue;
        private boolean partOfIdentity;
        private boolean secure;
        private boolean required;

        private Spec(String key) {
            this.key = key;
            this.displayName = key;
        }

        private Spec displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        private Spec defaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        private Spec partOfIdentity() {
            this.partOfIdentity = true;
            return this;
        }

        private Spec secure() {
            this.secure = true;
            return this;
        }

        private Spec required() {
            this.required = true;
            return this;
        }
    }
}
