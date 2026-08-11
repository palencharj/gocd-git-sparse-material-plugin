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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.thoughtworks.go.plugin.api.request.DefaultGoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks down the JSON contracts GoCD's {@code JsonMessageHandler1_0} imposes.
 *
 * <p>Several of these are quiet traps: a number where a string is expected, or an error returned in
 * the wrong shape, produces a deserialisation failure in the server rather than a useful message.
 */
class PluginContractTest {

    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    private GitSparseMaterialPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new GitSparseMaterialPlugin();
    }

    private GoPluginApiResponse handle(String requestName, String body) {
        DefaultGoPluginApiRequest request = new DefaultGoPluginApiRequest("scm", "1.0", requestName);
        request.setRequestBody(body);
        return plugin.handle(request);
    }

    private Map<String, Object> asMap(GoPluginApiResponse response) {
        return GSON.fromJson(response.responseBody(), new TypeToken<Map<String, Object>>() {}.getType());
    }

    private List<Map<String, Object>> asList(GoPluginApiResponse response) {
        return GSON.fromJson(response.responseBody(), new TypeToken<List<Map<String, Object>>>() {}.getType());
    }

    private String scmConfigJson(String... keysAndValues) {
        StringBuilder json = new StringBuilder("{\"scm-configuration\":{");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(keysAndValues[i]).append("\":{\"value\":")
                    .append(GSON.toJson(keysAndValues[i + 1])).append('}');
        }
        return json.append("}}").toString();
    }

    // ------------------------------------------------------- identity / plumbing

    @Test
    void identifiesItselfAsAnScmExtensionAtVersionOnePointZero() {
        assertThat(plugin.pluginIdentifier().getExtension()).isEqualTo("scm");
        assertThat(plugin.pluginIdentifier().getSupportedExtensionVersions()).containsExactly("1.0");
    }

    @Test
    void rejectsAnUnknownRequestRatherThanFailingObscurely() {
        assertThat(handle("no-such-request", "{}").responseCode()).isEqualTo(400);
    }

    // ------------------------------------------------------------ configuration

    @Test
    void declaresEveryConfigurationFieldTheViewBindsTo() {
        Map<String, Object> config = asMap(handle("scm-configuration", ""));

        assertThat(config).containsOnlyKeys(ScmConfig.URL, ScmConfig.BRANCH, ScmConfig.SPARSE_PATHS,
                ScmConfig.USERNAME, ScmConfig.PASSWORD, ScmConfig.SHALLOW, ScmConfig.FILTER_BY_PATHS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsDisplayOrderAsAStringBecauseGoCdCastsItToOne() {
        // GoCD does Integer.valueOf((String) value). A JSON number arrives as a Double and the
        // whole scm-configuration response is rejected.
        Map<String, Object> config = asMap(handle("scm-configuration", ""));

        for (Map.Entry<String, Object> entry : config.entrySet()) {
            Object order = ((Map<String, Object>) entry.getValue()).get("display-order");
            assertThat(order).as("display-order for " + entry.getKey()).isInstanceOf(String.class);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRepositoryBranchAndPathsTogetherIdentifyTheMaterial() {
        // part-of-identity feeds the material fingerprint. The paths must be included: two
        // materials on the same repo and branch restricted to different paths are different
        // materials, and sharing a fingerprint lets fan-in resolve a pipeline against a working
        // copy that never contained the paths it needs.
        Map<String, Object> config = asMap(handle("scm-configuration", ""));

        assertThat(((Map<String, Object>) config.get(ScmConfig.URL)).get("part-of-identity")).isEqualTo(true);
        assertThat(((Map<String, Object>) config.get(ScmConfig.BRANCH)).get("part-of-identity")).isEqualTo(true);
        assertThat(((Map<String, Object>) config.get(ScmConfig.SPARSE_PATHS)).get("part-of-identity")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void howMuchHistoryOrWhatTriggersABuildDoesNotIdentifyTheMaterial() {
        // These change behaviour, not which repository content the material refers to, so they must
        // stay out of the fingerprint or toggling them would orphan a pipeline's history.
        Map<String, Object> config = asMap(handle("scm-configuration", ""));

        assertThat(((Map<String, Object>) config.get(ScmConfig.SHALLOW)).get("part-of-identity")).isEqualTo(false);
        assertThat(((Map<String, Object>) config.get(ScmConfig.FILTER_BY_PATHS)).get("part-of-identity")).isEqualTo(false);
        assertThat(((Map<String, Object>) config.get(ScmConfig.USERNAME)).get("part-of-identity")).isEqualTo(false);
        assertThat(((Map<String, Object>) config.get(ScmConfig.PASSWORD)).get("part-of-identity")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void marksOnlyThePasswordSecure() {
        Map<String, Object> config = asMap(handle("scm-configuration", ""));

        assertThat(((Map<String, Object>) config.get(ScmConfig.PASSWORD)).get("secure")).isEqualTo(true);
        assertThat(((Map<String, Object>) config.get(ScmConfig.USERNAME)).get("secure")).isEqualTo(false);
        assertThat(((Map<String, Object>) config.get(ScmConfig.URL)).get("secure")).isEqualTo(false);
    }

    @Test
    void servesAViewThatBindsEveryDeclaredField() {
        Map<String, Object> view = asMap(handle("scm-view", ""));

        assertThat((String) view.get("displayValue")).isNotEmpty();
        String template = (String) view.get("template");
        assertThat(template).isNotEmpty();
        for (String key : asMap(handle("scm-configuration", "")).keySet()) {
            assertThat(template).as("view binds " + key).contains("ng-model=\"" + key + "\"");
        }
    }

    // --------------------------------------------------------------- validation

    @Test
    void acceptsAValidConfiguration() {
        GoPluginApiResponse response = handle("validate-scm-configuration",
                scmConfigJson(ScmConfig.URL, "https://example.com/r.git",
                        ScmConfig.SPARSE_PATHS, "services/billing"));

        assertThat(asList(response)).isEmpty();
    }

    @Test
    void requiresAUrl() {
        GoPluginApiResponse response = handle("validate-scm-configuration",
                scmConfigJson(ScmConfig.URL, "  ", ScmConfig.SPARSE_PATHS, "src"));

        assertThat(asList(response)).extracting(e -> e.get("key")).contains(ScmConfig.URL);
    }

    @Test
    void requiresAtLeastOnePath() {
        GoPluginApiResponse response = handle("validate-scm-configuration",
                scmConfigJson(ScmConfig.URL, "https://example.com/r.git", ScmConfig.SPARSE_PATHS, "\n  \n"));

        assertThat(asList(response)).extracting(e -> e.get("key")).contains(ScmConfig.SPARSE_PATHS);
    }

    @Test
    void rejectsAConfigurationThatWouldCheckOutNothing() {
        // git accepts an all-exclusions pattern set and silently produces an empty working
        // directory, which fails the build in a baffling way. Catch it at configuration time.
        GoPluginApiResponse response = handle("validate-scm-configuration",
                scmConfigJson(ScmConfig.URL, "https://example.com/r.git", ScmConfig.SPARSE_PATHS, "!a\n!b"));

        assertThat(asList(response)).extracting(e -> e.get("message"))
                .anyMatch(m -> String.valueOf(m).contains("nothing would be checked out"));
    }

    @Test
    void rejectsAnUnknownPropertyBecauseGoCdWouldFoldItIntoTheFingerprint() {
        // A property the plugin does not declare is included in the material fingerprint by GoCD,
        // so a typo in a config repo's `options:` would silently change the material's identity.
        // Nothing upstream catches it.
        GoPluginApiResponse response = handle("validate-scm-configuration",
                scmConfigJson(ScmConfig.URL, "https://example.com/r.git",
                        ScmConfig.SPARSE_PATHS, "src",
                        "pathz", "src"));

        assertThat(asList(response)).extracting(e -> e.get("key")).contains("pathz");
        assertThat(asList(response)).extracting(e -> e.get("message"))
                .anyMatch(m -> String.valueOf(m).contains("Unknown property"));
    }

    @Test
    void acceptsEveryPropertyItDeclares() {
        GoPluginApiResponse response = handle("validate-scm-configuration",
                scmConfigJson(ScmConfig.URL, "https://example.com/r.git",
                        ScmConfig.BRANCH, "main",
                        ScmConfig.SPARSE_PATHS, "src",
                        ScmConfig.USERNAME, "alice",
                        ScmConfig.PASSWORD, "s3cr3t",
                        ScmConfig.SHALLOW, "true",
                        ScmConfig.FILTER_BY_PATHS, "true"));

        assertThat(asList(response)).isEmpty();
    }

    @Test
    void everyDeclaredKeyIsInTheKnownKeyList() {
        // The metadata response and the validation whitelist must not drift apart, or a legitimate
        // property starts being reported as unknown.
        assertThat(ScmConfig.KNOWN_KEYS)
                .containsExactlyInAnyOrderElementsOf(asMap(handle("scm-configuration", "")).keySet());
    }

    @Test
    void rejectsPathsThatTryToEscapeTheRepository() {
        GoPluginApiResponse response = handle("validate-scm-configuration",
                scmConfigJson(ScmConfig.URL, "https://example.com/r.git",
                        ScmConfig.SPARSE_PATHS, "../../etc/passwd"));

        assertThat(asList(response)).extracting(e -> e.get("key")).contains(ScmConfig.SPARSE_PATHS);
    }

    // ------------------------------------------------------ failure shapes

    @Test
    void reportsAConnectionFailureAsAStatusMapNotAnErrorResponse() {
        // check-scm-connection responses are read as {status, messages}. Returning a bare error
        // response makes the server report a deserialisation problem instead of the real cause.
        GoPluginApiResponse response = handle("check-scm-connection",
                scmConfigJson(ScmConfig.URL, tempDir.resolve("nope").toUri().toString(),
                        ScmConfig.BRANCH, "master"));

        assertThat(response.responseCode()).isEqualTo(200);
        Map<String, Object> result = asMap(response);
        assertThat(result.get("status")).isEqualTo("failure");
        assertThat((List<?>) result.get("messages")).isNotEmpty();
    }

    @Test
    void reportsACheckoutFailureAsAStatusMapToo() {
        GoPluginApiResponse response = handle("checkout",
                scmConfigJson(ScmConfig.URL, "https://example.invalid/r.git"));

        assertThat(response.responseCode()).isEqualTo(200);
        assertThat(asMap(response).get("status")).isEqualTo("failure");
    }

    @Test
    void confirmsAGoodConnection() {
        TestRepo origin = TestRepo.create(tempDir.resolve("origin").toFile());
        origin.write("a.txt", "a").commit("initial");

        GoPluginApiResponse response = handle("check-scm-connection",
                scmConfigJson(ScmConfig.URL, origin.url(), ScmConfig.BRANCH, "master"));

        assertThat(asMap(response).get("status")).isEqualTo("success");
    }

    // ------------------------------------------------------------ poll + checkout

    @Test
    @SuppressWarnings("unchecked")
    void latestRevisionReturnsAParseableRevisionBlock() {
        TestRepo origin = TestRepo.create(tempDir.resolve("origin").toFile());
        origin.write("src/a.txt", "a").commit("initial");
        String flyweight = tempDir.resolve("fly").toFile().getAbsolutePath();

        GoPluginApiResponse response = handle("latest-revision",
                "{\"scm-configuration\":{\"url\":{\"value\":" + GSON.toJson(origin.url())
                        + "},\"branch\":{\"value\":\"master\"},\"sparse_paths\":{\"value\":\"src\"}},"
                        + "\"flyweight-folder\":" + GSON.toJson(flyweight) + "}");

        Map<String, Object> revision = (Map<String, Object>) asMap(response).get("revision");
        assertThat((String) revision.get("revision")).isEqualTo(origin.head());
        assertThat((String) revision.get("revisionComment")).isEqualTo("initial");
        // GoCD parses this with a strict ISO-8601 offset parser; the offset is not optional.
        assertThat((String) revision.get("timestamp")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat((List<?>) revision.get("modifiedFiles")).isNotEmpty();
    }

    @Test
    void latestRevisionsSinceReturnsAnEmptyListWhenNothingIsNew() {
        TestRepo origin = TestRepo.create(tempDir.resolve("origin").toFile());
        origin.write("src/a.txt", "a").commit("initial");
        String flyweight = tempDir.resolve("fly").toFile().getAbsolutePath();

        GoPluginApiResponse response = handle("latest-revisions-since",
                "{\"scm-configuration\":{\"url\":{\"value\":" + GSON.toJson(origin.url())
                        + "},\"branch\":{\"value\":\"master\"},\"sparse_paths\":{\"value\":\"src\"}},"
                        + "\"flyweight-folder\":" + GSON.toJson(flyweight) + ","
                        + "\"previous-revision\":{\"revision\":" + GSON.toJson(origin.head()) + "}}");

        assertThat((List<?>) asMap(response).get("revisions")).isEmpty();
    }

    // ---- runtime guards for the pipelines-as-code path ----
    //
    // GoCD only calls validate-scm-configuration from the SCM admin CRUD path
    // (PluggableScmService). A config repo is converted straight to config objects by
    // ConfigConverter with no validation call, and preflight does not run it either - both
    // confirmed against 26.1.0. So these checks must also happen at runtime, or a typo'd
    // config-repo material silently performs a full checkout instead of failing.

    @Test
    void checkoutFailsRatherThanSilentlyDoingAFullCheckoutWhenPathsAreMissing() {
        GoPluginApiResponse response = handle("checkout",
                "{\"scm-configuration\":{\"url\":{\"value\":\"https://example.com/r.git\"}},"
                        + "\"destination-folder\":\"/tmp/x\",\"revision\":{\"revision\":\"abc\"}}");

        Map<String, Object> result = asMap(response);
        assertThat(result.get("status")).isEqualTo("failure");
        assertThat(String.valueOf(result.get("messages"))).contains("sparse_paths");
    }

    @Test
    void checkoutFailsOnAnUnrecognisedProperty() {
        // An undeclared property is folded into the fingerprint by GoCD, so the material has
        // already lost its identity. Better to say so loudly than build the wrong tree quietly.
        GoPluginApiResponse response = handle("checkout",
                "{\"scm-configuration\":{\"url\":{\"value\":\"https://example.com/r.git\"},"
                        + "\"sparse_paths\":{\"value\":\"src\"},\"pathz\":{\"value\":\"src\"}},"
                        + "\"destination-folder\":\"/tmp/x\",\"revision\":{\"revision\":\"abc\"}}");

        Map<String, Object> result = asMap(response);
        assertThat(result.get("status")).isEqualTo("failure");
        assertThat(String.valueOf(result.get("messages"))).contains("pathz");
    }

    @Test
    void pollingFailsOnAnUnusableConfigurationToo() {
        GoPluginApiResponse response = handle("latest-revision",
                "{\"scm-configuration\":{\"url\":{\"value\":\"https://example.com/r.git\"},"
                        + "\"sparse_paths\":{\"value\":\"!only\\n!exclusions\"}},"
                        + "\"flyweight-folder\":\"/tmp/y\"}");

        assertThat(response.responseCode()).isEqualTo(500);
        assertThat(response.responseBody()).contains("exclusion");
    }

    @Test
    void aValidConfigurationPassesTheRuntimeGuard() {
        TestRepo origin = TestRepo.create(tempDir.resolve("guard").toFile());
        origin.write("src/a.txt", "a").commit("initial");
        java.io.File dest = tempDir.resolve("guarddest").toFile();

        GoPluginApiResponse response = handle("checkout",
                "{\"scm-configuration\":{\"url\":{\"value\":" + GSON.toJson(origin.url())
                        + "},\"branch\":{\"value\":\"master\"},\"sparse_paths\":{\"value\":\"src\"}},"
                        + "\"destination-folder\":" + GSON.toJson(dest.getAbsolutePath()) + ","
                        + "\"revision\":{\"revision\":" + GSON.toJson(origin.head()) + "}}");

        assertThat(asMap(response).get("status")).isEqualTo("success");
    }

    @Test
    void checkoutLaysDownOnlyTheConfiguredPathsAndReportsSuccess() {
        TestRepo origin = TestRepo.create(tempDir.resolve("origin").toFile());
        origin.write("src/a.txt", "a").write("docs/b.md", "b").commit("initial");
        java.io.File dest = tempDir.resolve("dest").toFile();

        GoPluginApiResponse response = handle("checkout",
                "{\"scm-configuration\":{\"url\":{\"value\":" + GSON.toJson(origin.url())
                        + "},\"branch\":{\"value\":\"master\"},\"sparse_paths\":{\"value\":\"src\"}},"
                        + "\"destination-folder\":" + GSON.toJson(dest.getAbsolutePath()) + ","
                        + "\"revision\":{\"revision\":" + GSON.toJson(origin.head()) + "}}");

        assertThat(asMap(response).get("status")).isEqualTo("success");
        assertThat(TestRepo.filesIn(dest)).containsExactly("src/a.txt");
    }
}
