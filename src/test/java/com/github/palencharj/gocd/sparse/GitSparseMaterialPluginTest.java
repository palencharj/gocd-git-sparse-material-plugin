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

import com.github.palencharj.gocd.sparse.config.ScmProperty;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.thoughtworks.go.plugin.api.exceptions.UnhandledRequestTypeException;
import com.thoughtworks.go.plugin.api.request.DefaultGoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the plugin the way GoCD does, and pins the JSON contracts
 * {@code JsonMessageHandler1_0} imposes.
 *
 * <p>Several of those are quiet traps — a number where a string is expected, or a failure returned in
 * the wrong shape — and each produces a deserialisation error in the server rather than the real
 * cause. Nothing in the plugin's own types can catch them, so they are asserted here against the
 * bytes that actually go over the wire.
 */
class GitSparseMaterialPluginTest {

    private static final Gson GSON = new Gson();

    private static final String URL = ScmProperty.URL.key();
    private static final String BRANCH = ScmProperty.BRANCH.key();
    private static final String SPARSE_PATHS = ScmProperty.SPARSE_PATHS.key();

    @TempDir
    Path tempDir;

    private GitSparseMaterialPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new GitSparseMaterialPlugin();
    }

    // -------------------------------------------------------------------- plumbing

    @Test
    void shouldIdentifyItselfAsAnScmExtensionAtVersionOnePointZero() {
        assertThat(plugin.pluginIdentifier().getExtension()).isEqualTo("scm");
        assertThat(plugin.pluginIdentifier().getSupportedExtensionVersions()).containsExactly("1.0");
    }

    @Test
    void shouldRejectAnUnknownRequestTypeTheWayTheExtensionPointExpects() {
        // DefaultPluginManager logs this against the plugin and abandons the call, which is the right
        // outcome for a server asking for something this version cannot do.
        assertThatThrownBy(() -> handle("no-such-request", "{}"))
                .isInstanceOf(UnhandledRequestTypeException.class)
                .hasMessageContaining("no-such-request");
    }

    @Test
    void shouldAnswerEveryRequestTheScmExtensionDefines() throws Exception {
        // A missing case would only show up as a runtime failure on a live server.
        for (String request : List.of("scm-configuration", "scm-view", "validate-scm-configuration",
                "check-scm-connection", "latest-revision", "latest-revisions-since", "checkout")) {
            assertThat(handle(request, "{}")).as(request).isNotNull();
        }
    }

    // --------------------------------------------------------------- configuration

    @Test
    void shouldDeclareEveryConfigurationFieldTheViewBindsTo() throws Exception {
        Map<String, Object> configuration = asMap(handle("scm-configuration", ""));

        assertThat(configuration).containsOnlyKeys(ScmProperty.keys().toArray(new String[0]));
    }

    @Test
    void shouldServeAViewThatBindsEveryDeclaredField() throws Exception {
        Map<String, Object> view = asMap(handle("scm-view", ""));

        assertThat((String) view.get("displayValue")).isNotEmpty();
        String template = (String) view.get("template");
        assertThat(template).isNotEmpty();
        for (String key : ScmProperty.keys()) {
            assertThat(template).as("the view binds " + key).contains("ng-model=\"" + key + "\"");
        }
    }

    // ------------------------------------------------------------------ validation

    @Test
    void shouldReportNoErrorsForAUsableConfiguration() throws Exception {
        GoPluginApiResponse response = handle("validate-scm-configuration",
                scmConfiguration(URL, "https://example.com/r.git", SPARSE_PATHS, "services/billing"));

        assertThat(asList(response)).isEmpty();
    }

    @Test
    void shouldAttributeEachValidationErrorToItsOwnField() throws Exception {
        // GoCD shows a message against the field named in "key". Without that it appears as an
        // unattached banner and the reader has to guess which input is wrong.
        GoPluginApiResponse response = handle("validate-scm-configuration",
                scmConfiguration(URL, "  ", SPARSE_PATHS, "  "));

        assertThat(asList(response)).extracting(error -> error.get("key"))
                .containsExactlyInAnyOrder(URL, SPARSE_PATHS);
        assertThat(asList(response)).allSatisfy(error ->
                assertThat((String) error.get("message")).isNotEmpty());
    }

    @Test
    void shouldAcceptEveryPropertyItDeclares() throws Exception {
        GoPluginApiResponse response = handle("validate-scm-configuration",
                scmConfiguration(URL, "https://example.com/r.git",
                        BRANCH, "main",
                        SPARSE_PATHS, "src",
                        "username", "alice",
                        "password", "s3cr3t",
                        "shallow", "true",
                        "filter_by_paths", "true"));

        assertThat(asList(response)).isEmpty();
    }

    @Test
    void shouldRejectAnUnknownPropertyBecauseGoCdFoldsItIntoTheFingerprint() throws Exception {
        GoPluginApiResponse response = handle("validate-scm-configuration",
                scmConfiguration(URL, "https://example.com/r.git", SPARSE_PATHS, "src", "pathz", "src"));

        assertThat(asList(response)).extracting(error -> error.get("key")).contains("pathz");
    }

    // --------------------------------------------------------------- failure shape

    @Test
    void shouldReportAConnectionFailureAsAStatusMapNotAnErrorResponse() throws Exception {
        // check-scm-connection responses are deserialised into a Result before the status is read, so
        // a non-2xx here surfaces as "unable to de-serialize json response" instead of the real cause.
        GoPluginApiResponse response = handle("check-scm-connection",
                scmConfiguration(URL, tempDir.resolve("nope").toString(), BRANCH, "master"));

        assertThat(response.responseCode()).isEqualTo(200);
        assertThat(asMap(response).get("status")).isEqualTo("failure");
        assertThat((List<?>) asMap(response).get("messages")).isNotEmpty();
    }

    @Test
    void shouldReportACheckoutFailureAsAStatusMapToo() throws Exception {
        GoPluginApiResponse response =
                handle("checkout", scmConfiguration(URL, "https://example.invalid/r.git"));

        assertThat(response.responseCode()).isEqualTo(200);
        assertThat(asMap(response).get("status")).isEqualTo("failure");
    }

    @Test
    void shouldReportAPollingFailureAsAnErrorResponse() throws Exception {
        GoPluginApiResponse response = handle("latest-revision",
                scmConfiguration(URL, "https://example.com/r.git", SPARSE_PATHS, "!only\n!exclusions"));

        assertThat(response.responseCode()).isEqualTo(500);
        assertThat(response.responseBody()).contains("exclusion");
    }

    @Test
    void shouldNotLeakACredentialIntoAFailureMessage() throws Exception {
        GoPluginApiResponse response = handle("check-scm-connection",
                scmConfiguration(URL, "https://example.invalid/r.git",
                        "username", "alice", "password", "s3cr3t"));

        assertThat(response.responseBody()).doesNotContain("s3cr3t");
    }

    @Test
    void shouldNameTheMissingRequestFieldRatherThanThrowing() throws Exception {
        // A checkout missing either of these can only mean a broken caller, but the message still has
        // to name which one, or the only clue is a stack trace in a server log.
        GoPluginApiResponse missingFolder = handle("checkout", request()
                .properties(URL, "https://example.com/r.git", SPARSE_PATHS, "src")
                .revision("abc")
                .json());
        GoPluginApiResponse missingRevision = handle("checkout", request()
                .properties(URL, "https://example.com/r.git", SPARSE_PATHS, "src")
                .destinationFolder(tempDir.resolve("x"))
                .json());

        assertThat(asMap(missingFolder).get("status")).isEqualTo("failure");
        assertThat(missingFolder.responseBody()).contains("destination-folder");
        assertThat(asMap(missingRevision).get("status")).isEqualTo("failure");
        assertThat(missingRevision.responseBody()).contains("revision");
    }

    // ------------------------------------------------------------ poll and checkout

    @Test
    void shouldConfirmAGoodConnection() throws Exception {
        TestRepository origin = TestRepository.create(tempDir.resolve("origin").toFile());
        origin.write("a.txt", "a").commit("initial");

        GoPluginApiResponse response = handle("check-scm-connection",
                scmConfiguration(URL, origin.url(), BRANCH, "master"));

        assertThat(asMap(response).get("status")).isEqualTo("success");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnARevisionGoCdCanParse() throws Exception {
        TestRepository origin = TestRepository.create(tempDir.resolve("origin").toFile());
        origin.write("src/a.txt", "a").commit("initial");

        GoPluginApiResponse response = handle("latest-revision", request()
                .properties(URL, origin.url(), BRANCH, "master", SPARSE_PATHS, "src")
                .flyweightFolder(tempDir.resolve("fly"))
                .json());

        Map<String, Object> revision = (Map<String, Object>) asMap(response).get("revision");
        assertThat((String) revision.get("revision")).isEqualTo(origin.head());
        assertThat((String) revision.get("revisionComment")).isEqualTo("initial");
        assertThat((String) revision.get("user")).contains("Test User");
        // Parsed with DateTimeFormatter.ISO_OFFSET_DATE_TIME, so the offset is not optional.
        assertThat((String) revision.get("timestamp"))
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat((List<?>) revision.get("modifiedFiles")).isNotEmpty();
    }

    @Test
    void shouldReturnAnEmptyRevisionListWhenNothingIsNew() throws Exception {
        TestRepository origin = TestRepository.create(tempDir.resolve("origin").toFile());
        origin.write("src/a.txt", "a").commit("initial");

        GoPluginApiResponse response = handle("latest-revisions-since", request()
                .properties(URL, origin.url(), BRANCH, "master", SPARSE_PATHS, "src")
                .flyweightFolder(tempDir.resolve("fly"))
                .previousRevision(origin.head())
                .json());

        assertThat((List<?>) asMap(response).get("revisions")).isEmpty();
    }

    @Test
    void shouldCheckOutOnlyTheConfiguredPathsAndReportSuccess() throws Exception {
        TestRepository origin = TestRepository.create(tempDir.resolve("origin").toFile());
        origin.write("src/a.txt", "a").write("docs/b.md", "b").commit("initial");
        File destination = tempDir.resolve("dest").toFile();

        GoPluginApiResponse response = handle("checkout", request()
                .properties(URL, origin.url(), BRANCH, "master", SPARSE_PATHS, "src")
                .destinationFolder(destination.toPath())
                .revision(origin.head())
                .json());

        assertThat(asMap(response).get("status")).isEqualTo("success");
        assertThat(TestRepository.filesIn(destination)).containsExactly("src/a.txt");
    }

    // ---- the pipelines-as-code path ----
    //
    // GoCD invokes validate-scm-configuration only from the SCM admin CRUD path
    // (PluggableScmService). A config repo is converted straight into config objects by
    // ConfigConverter and applied on poll, with no validation call and no approval gate — confirmed
    // against 26.1.0. So the same checks have to hold at runtime, or a typo'd config-repo material
    // quietly performs a full checkout instead of failing.

    @Test
    void shouldRefuseToCheckOutWithNoPathsRatherThanTakeTheWholeRepository() throws Exception {
        GoPluginApiResponse response = handle("checkout", request()
                .properties(URL, "https://example.com/r.git")
                .destinationFolder(tempDir.resolve("x"))
                .revision("abc")
                .json());

        assertThat(asMap(response).get("status")).isEqualTo("failure");
        assertThat(response.responseBody()).contains(SPARSE_PATHS);
    }

    @Test
    void shouldRefuseToCheckOutOnAnUnrecognisedProperty() throws Exception {
        // The material has already lost its identity by this point, because GoCD folded the unknown
        // property into the fingerprint. Better to say so than to build the wrong tree quietly.
        GoPluginApiResponse response = handle("checkout", request()
                .properties(URL, "https://example.com/r.git", SPARSE_PATHS, "src", "pathz", "src")
                .destinationFolder(tempDir.resolve("x"))
                .revision("abc")
                .json());

        assertThat(asMap(response).get("status")).isEqualTo("failure");
        assertThat(response.responseBody()).contains("pathz");
    }

    @Test
    void shouldRefuseToPollAnUnusableConfigurationToo() throws Exception {
        GoPluginApiResponse response = handle("latest-revision", request()
                .properties(URL, "https://example.com/r.git", SPARSE_PATHS, "  ")
                .flyweightFolder(tempDir.resolve("y"))
                .json());

        assertThat(response.responseCode()).isEqualTo(500);
        assertThat(response.responseBody()).contains(SPARSE_PATHS);
    }

    // --------------------------------------------------------------------- helpers

    private GoPluginApiResponse handle(String requestName, String requestBody)
            throws UnhandledRequestTypeException {
        DefaultGoPluginApiRequest request = new DefaultGoPluginApiRequest("scm", "1.0", requestName);
        request.setRequestBody(requestBody);
        return plugin.handle(request);
    }

    /** A request body carrying nothing but the material's properties. */
    private static String scmConfiguration(String... keysAndValues) {
        return request().properties(keysAndValues).json();
    }

    private static RequestBody request() {
        return new RequestBody();
    }

    /**
     * Builds a request body the way GoCD sends one.
     *
     * <p>Assembled as objects and serialised by Gson rather than concatenated as text, so a path
     * containing a backslash — which is every path on Windows — cannot produce a body that merely
     * looks like valid JSON.
     */
    private static final class RequestBody {

        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final Map<String, Object> fields = new LinkedHashMap<>();

        RequestBody properties(String... keysAndValues) {
            for (int i = 0; i < keysAndValues.length; i += 2) {
                properties.put(keysAndValues[i], Map.of("value", keysAndValues[i + 1]));
            }
            return this;
        }

        RequestBody flyweightFolder(Path folder) {
            fields.put("flyweight-folder", folder.toString());
            return this;
        }

        RequestBody destinationFolder(Path folder) {
            fields.put("destination-folder", folder.toString());
            return this;
        }

        RequestBody revision(String sha) {
            fields.put("revision", Map.of("revision", sha));
            return this;
        }

        RequestBody previousRevision(String sha) {
            fields.put("previous-revision", Map.of("revision", sha));
            return this;
        }

        String json() {
            Map<String, Object> body = new LinkedHashMap<>(fields);
            body.put("scm-configuration", properties);
            return GSON.toJson(body);
        }
    }

    private static Map<String, Object> asMap(GoPluginApiResponse response) {
        return GSON.fromJson(response.responseBody(),
                new TypeToken<Map<String, Object>>() { }.getType());
    }

    private static List<Map<String, Object>> asList(GoPluginApiResponse response) {
        return GSON.fromJson(response.responseBody(),
                new TypeToken<List<Map<String, Object>>>() { }.getType());
    }
}
