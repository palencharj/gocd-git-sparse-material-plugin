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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that existing materials behave exactly as they did before the config-repo work, using real
 * SCM definitions exported from GoCD ({@code GET /go/api/admin/scms/:name}, the shape
 * {@code GoCD-ConfigRepo-scms/scms/*.json} holds).
 *
 * <p>Two things must never change for a configuration that already exists. Its <b>identity</b>: GoCD
 * fingerprints a material from the properties this plugin marks {@code part-of-identity}, and that
 * fingerprint is what ties a pipeline to its build history. And its <b>acceptance</b>: anything
 * 1.3.0 accepted must still be accepted, or a pipeline that polled yesterday fails today.
 *
 * <p>The real export is read from {@code -Dgocd.scms.dir=...} or the {@code GOCD_SCMS_DIR}
 * environment variable and the test skips when neither is set, because those files hold production
 * repository names. A small anonymised sample in {@code src/test/resources/scms-sample} always runs,
 * so the check is never silently absent.
 *
 * <p>The 1.3.0 behaviour is frozen in {@link Baseline} below rather than referenced, so this test
 * keeps meaning the same thing after the production code moves on.
 */
class EstateRegressionTest {

    private static final String PLUGIN_ID = "com.github.palencharj.gocd.git-sparse-material";

    /** What 1.3.0 declared part-of-identity. Changing this re-fingerprints every material. */
    private static final List<String> BASELINE_IDENTITY_KEYS = List.of("url", "branch", "sparse_paths");

    private static final List<String> BASELINE_KEYS = List.of("url", "branch", "sparse_paths",
            "username", "password", "shallow", "filter_by_paths", "partial_clone");

    @Test
    void shouldKeepTheMetadataThatDecidesIdentityExactlyAsItWas() {
        assertThat(ScmProperty.keys()).containsExactlyElementsOf(BASELINE_KEYS);
        List<String> identity = new ArrayList<>();
        ScmProperty.metadata().forEach((key, described) -> {
            if (Boolean.TRUE.equals(asMap(described).get("part-of-identity"))) {
                identity.add(key);
            }
        });
        assertThat(identity).containsExactlyElementsOf(BASELINE_IDENTITY_KEYS);
    }

    @Test
    void shouldBehaveAsBeforeForTheAnonymisedSample() throws IOException {
        Path sample = Path.of("src", "test", "resources", "scms-sample");
        assertThat(checkAll(sample)).as("sample SCMs checked").isGreaterThanOrEqualTo(4);
    }

    @Test
    void shouldBehaveAsBeforeForEveryRealScmInTheEstate() throws IOException {
        Path dir = realExport();
        Assumptions.assumeTrue(dir != null && Files.isDirectory(dir),
                "set GOCD_SCMS_DIR (or -Dgocd.scms.dir) to the exported scms folder to run this");

        int checked = checkAll(dir);

        assertThat(checked).as("SCMs checked in " + dir).isPositive();
    }

    // ------------------------------------------------------------------ the check

    private static int checkAll(Path dir) throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        for (Path file : files) {
            check(file);
        }
        return files.size();
    }

    private static void check(Path file) throws IOException {
        JsonObject scm = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        String name = file.getFileName().toString();

        // GoCD decrypts secure values before the plugin sees them; a placeholder stands in.
        Map<String, String> stored = new TreeMap<>();
        Map<String, Object> request = new LinkedHashMap<>();
        for (JsonElement element : scm.getAsJsonArray("configuration")) {
            JsonObject property = element.getAsJsonObject();
            String key = property.get("key").getAsString();
            String value = property.has("value") ? property.get("value").getAsString() : "decrypted";
            stored.put(key, value);
            request.put(key, Map.of("value", value));
        }
        MaterialConfiguration now = MaterialConfiguration.from(request);
        Baseline then = new Baseline(stored);

        // Acceptance: whatever 1.3.0 accepted must still be accepted.
        List<String> keysNow = now.validate().stream()
                .map(MaterialConfiguration.ValidationError::key).toList();
        assertThat(keysNow).as("%s: keys with validation errors", name)
                .containsExactlyElementsOf(then.errorKeys());

        // What the plugin does with the values.
        assertThat(now.url()).as("%s url", name).isEqualTo(then.url());
        assertThat(now.branch()).as("%s branch", name).isEqualTo(then.branch());
        assertThat(now.sparsePaths()).as("%s sparse paths", name).isEqualTo(then.sparsePaths());
        assertThat(now.isShallow()).as("%s shallow", name).isEqualTo(then.flag("shallow"));
        assertThat(now.isFilteredByPaths()).as("%s filter", name).isEqualTo(then.flag("filter_by_paths"));
        assertThat(now.isPartialClone()).as("%s partial", name).isEqualTo(then.flag("partial_clone"));
        assertThat(now.unknownKeys()).as("%s unknown keys", name).isEmpty();

        // Identity: the fingerprint model over live metadata equals the one over frozen 1.3.0
        // metadata, and does not move when anything outside the identity changes.
        String frozen = fingerprint(PLUGIN_ID, stored, BASELINE_IDENTITY_KEYS);
        assertThat(fingerprint(PLUGIN_ID, stored, liveIdentityKeys())).as("%s fingerprint", name)
                .isEqualTo(frozen);
        Map<String, String> rotated = new TreeMap<>(stored);
        rotated.put("password", "a-rotated-token");
        rotated.put("username", "someone-else");
        rotated.put("shallow", "false");
        rotated.put("filter_by_paths", "true");
        rotated.put("partial_clone", "true");
        assertThat(fingerprint(PLUGIN_ID, rotated, liveIdentityKeys()))
                .as("%s fingerprint after credentials/flags change", name).isEqualTo(frozen);
    }

    private static List<String> liveIdentityKeys() {
        return ScmProperty.metadata().entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(asMap(entry.getValue()).get("part-of-identity")))
                .map(Map.Entry::getKey).toList();
    }

    /**
     * A stand-in for {@code SCM#getFingerprint}: a SHA-256 over the plugin id and the identity
     * properties. It is a model, not GoCD's literal algorithm, so its absolute value means nothing;
     * what it proves is that the inputs GoCD derives identity from are unchanged.
     */
    private static String fingerprint(String pluginId, Map<String, String> values, List<String> identity) {
        StringBuilder text = new StringBuilder("plugin-id=").append(pluginId);
        values.forEach((key, value) -> {
            if (identity.contains(key)) {
                text.append('|').append(key).append('=').append(value);
            }
        });
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path realExport() {
        String configured = System.getProperty("gocd.scms.dir", System.getenv("GOCD_SCMS_DIR"));
        return configured == null || configured.isBlank() ? null : Path.of(configured);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    /** The 1.3.0 validation and value semantics, frozen. Do not "improve" this. */
    private static final class Baseline {
        private final Map<String, String> values;

        Baseline(Map<String, String> values) {
            this.values = values;
        }

        private String valueOf(String key, String fallback) {
            String value = values.get(key);
            return value == null || value.trim().isEmpty() ? fallback : value.trim();
        }

        String url() {
            return valueOf("url", null);
        }

        String branch() {
            return valueOf("branch", "master");
        }

        boolean flag(String key) {
            return Boolean.parseBoolean(valueOf(key, "false"));
        }

        List<String> sparsePaths() {
            List<String> paths = new ArrayList<>();
            String configured = values.get("sparse_paths");
            if (configured == null || configured.trim().isEmpty()) {
                return paths;
            }
            for (String line : configured.split("\\R")) {
                if (!line.trim().isEmpty()) {
                    paths.add(line.trim());
                }
            }
            return paths;
        }

        List<String> errorKeys() {
            List<String> keys = new ArrayList<>();
            for (String key : values.keySet()) {
                if (!BASELINE_KEYS.contains(key)) {
                    keys.add(key);
                }
            }
            if (url() == null) {
                keys.add("url");
            }
            List<String> paths = sparsePaths();
            boolean anyInclude = paths.stream().anyMatch(path -> !path.startsWith("!"));
            if (paths.isEmpty() || !anyInclude) {
                keys.add("sparse_paths");
            }
            if (paths.stream().anyMatch(path -> path.contains(".."))) {
                keys.add("sparse_paths");
            }
            return keys;
        }
    }
}
