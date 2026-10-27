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

    URL(spec("url").displayName("Repository URL").partOfIdentity().required()
            .describedAs("Repository URL, https:// or ssh.")),

    BRANCH(spec("branch").displayName("Branch").defaultValue("master").partOfIdentity()
            .describedAs("Branch to poll and check out. Defaults to master.")),

    SPARSE_PATHS(spec("sparse_paths").displayName("Paths to check out").partOfIdentity().required()
            .multiline()
            .describedAs("Paths to check out, one gitignore-style pattern per line. In YAML use a"
                    + " literal block (|), not a folded one (>): a folded block joins the lines into"
                    + " one pattern.")),

    USERNAME(spec("username").displayName("Username")
            .describedAs("HTTP(S) username. Leave out for SSH keys.")),

    PASSWORD(spec("password").displayName("Password").secure()
            .describedAs("HTTP(S) password or token. In a config repo use a secret reference such"
                    + " as {{SECRET:[store][key]}} under options: (not secure_options:).")),

    SHALLOW(spec("shallow").displayName("Shallow clone").defaultValue("false").flag()
            .describedAs("Fetch only the tip commit; deepens automatically when a build needs an"
                    + " older revision.")),

    FILTER_BY_PATHS(spec("filter_by_paths")
            .displayName("Only trigger on changes under these paths")
            .defaultValue("false").flag()
            .describedAs("Poll only commits that touch the sparse paths, so a pipeline builds only"
                    + " when its own code changes.")),

    /**
     * Clone the agent's working copy with {@code --filter=blob:none}, so file contents are fetched
     * only for the paths actually checked out. Without it a clone, even a depth-1 one, transfers
     * every blob in the tree and sparse-checkout merely declines to write most of them.
     *
     * <p>Off by default: it changes what an agent downloads, so it is opted into per material.
     * Not part of the identity, and absent from every existing configuration, so adding it
     * re-fingerprints nothing. It takes effect when the working copy is next cloned; an existing
     * full clone on an agent is left as it is.
     */
    PARTIAL_CLONE(spec("partial_clone")
            .displayName("Partial clone (fetch file contents only under the checked-out paths)")
            .defaultValue("false").flag()
            .describedAs("Clone the agent's working copy with --filter=blob:none so file contents"
                    + " are fetched only for the checked-out paths. Applies on the next clone."));

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

    /** One-sentence help text; documentation only, never sent to GoCD. */
    public String description() {
        return spec.description;
    }

    /** True for a {@code "true"}/{@code "false"} option. */
    public boolean isFlag() {
        return spec.flag;
    }

    /** True for an option whose value is one entry per line. */
    public boolean isMultiline() {
        return spec.multiline;
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
        private boolean flag;
        private boolean multiline;
        private String description = "";

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

        private Spec flag() {
            this.flag = true;
            return this;
        }

        private Spec multiline() {
            this.multiline = true;
            return this;
        }

        private Spec describedAs(String description) {
            this.description = description;
            return this;
        }
    }
}
