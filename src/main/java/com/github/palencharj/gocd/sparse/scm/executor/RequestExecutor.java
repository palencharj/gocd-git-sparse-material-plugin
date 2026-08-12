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
package com.github.palencharj.gocd.sparse.scm.executor;

import com.github.palencharj.gocd.sparse.scm.ScmResponse;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;

/**
 * One SCM request, answered.
 *
 * <p>Implementations are built per request and thrown away, so they may hold whatever they parsed
 * out of it. GoCD calls the plugin concurrently — several materials poll at once — and per-request
 * instances are what keep that safe without any locking.
 */
public interface RequestExecutor {

    GoPluginApiResponse execute();

    /**
     * Renders a failure for this request.
     *
     * <p>Overridable because the shape is not uniform across the extension: most requests report a
     * failure as a non-2xx response, while {@code check-scm-connection} and {@code checkout} are
     * deserialised into a result object first and need a 200 carrying a failure status. Putting the
     * choice here keeps it next to the handler that knows its own response shape, rather than in a
     * second switch on request names that has to be kept in step with the first.
     */
    default GoPluginApiResponse failure(String message) {
        return ScmResponse.error(message);
    }
}
