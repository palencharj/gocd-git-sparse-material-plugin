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

import com.thoughtworks.go.plugin.api.response.DefaultGoPluginApiResponse;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The three response shapes GoCD's SCM extension understands. */
public final class ScmResponse {

    private ScmResponse() {
    }

    /** A 200 whose body is {@code body} serialised as JSON. */
    public static GoPluginApiResponse success(Object body) {
        return DefaultGoPluginApiResponse.success(Json.toJson(body));
    }

    /**
     * A 500 whose body is a bare message.
     *
     * <p>How {@code GoPluginApiResponse}'s non-2xx statuses are surfaced varies by request:
     * {@code latest-revision} logs the message and treats the poll as failed, while
     * {@code validate-scm-configuration} shows it in the UI. Either way the message reaches
     * somebody, which is why it must already be readable.
     */
    public static GoPluginApiResponse error(String message) {
        return DefaultGoPluginApiResponse.error(message);
    }

    /**
     * The {@code {"status": ..., "messages": [...]}} body that {@code check-scm-connection} and
     * {@code checkout} are read as.
     *
     * <p>Note that this is a 200 even when the operation failed. GoCD deserialises those two
     * responses into a result object before looking at the status, so answering a failure with
     * {@link #error} instead makes the UI report a deserialisation problem rather than the actual
     * cause.
     */
    public static GoPluginApiResponse status(boolean successful, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", successful ? "success" : "failure");
        body.put("messages", List.of(message));
        return success(body);
    }
}
