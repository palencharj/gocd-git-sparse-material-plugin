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

import com.github.palencharj.gocd.sparse.MaterialException;
import com.github.palencharj.gocd.sparse.config.MaterialConfiguration;
import com.thoughtworks.go.plugin.api.request.GoPluginApiRequest;

import java.io.File;
import java.util.Map;

/**
 * An incoming SCM request, read once into typed form.
 *
 * <p>All seven requests draw on the same small vocabulary — a material's configuration, a folder to
 * work in, a revision — so they share one request type rather than getting seven near-identical
 * ones. Every field is optional in the protocol and only some are meaningful per request, which is
 * why the accessors that a handler cannot proceed without fail loudly rather than returning null.
 */
public final class ScmRequest {

    private static final String SCM_CONFIGURATION = "scm-configuration";
    private static final String FLYWEIGHT_FOLDER = "flyweight-folder";
    private static final String DESTINATION_FOLDER = "destination-folder";
    private static final String REVISION = "revision";
    private static final String PREVIOUS_REVISION = "previous-revision";

    private final Map<String, Object> body;
    private final MaterialConfiguration configuration;

    private ScmRequest(Map<String, Object> body) {
        this.body = body;
        this.configuration = MaterialConfiguration.from(nestedObject(SCM_CONFIGURATION));
    }

    public static ScmRequest from(GoPluginApiRequest request) {
        return new ScmRequest(Json.toMap(request.requestBody()));
    }

    public MaterialConfiguration configuration() {
        return configuration;
    }

    /** The server-side scratch directory GoCD keeps for polling this material. */
    public File flyweightFolder() {
        return folder(FLYWEIGHT_FOLDER, "a folder to poll in");
    }

    /** The agent-side working directory a checkout has to populate. */
    public File destinationFolder() {
        return folder(DESTINATION_FOLDER, "a folder to check out into");
    }

    /** The revision to check out. */
    public String revision() {
        String sha = nestedString(REVISION, REVISION);
        if (MaterialConfiguration.isBlank(sha)) {
            throw new MaterialException("GoCD did not say which revision to check out.");
        }
        return sha;
    }

    /**
     * The last revision GoCD already knows about, or {@code null} on the first poll.
     *
     * <p>Genuinely absent the first time a material is polled, so unlike the others this one has no
     * failure case — {@code null} means "report the newest commit" rather than "something is wrong".
     */
    public String previousRevision() {
        String sha = nestedString(PREVIOUS_REVISION, REVISION);
        return MaterialConfiguration.isBlank(sha) ? null : sha;
    }

    private File folder(String key, String description) {
        Object value = body.get(key);
        if (value == null || MaterialConfiguration.isBlank(value.toString())) {
            throw new MaterialException("GoCD did not supply " + description + " ('" + key + "').");
        }
        return new File(value.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedObject(String key) {
        Object value = body.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private String nestedString(String objectKey, String key) {
        Object value = nestedObject(objectKey).get(key);
        return value == null ? null : value.toString();
    }
}
