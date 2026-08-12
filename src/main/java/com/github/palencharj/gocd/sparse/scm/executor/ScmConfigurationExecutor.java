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

import com.github.palencharj.gocd.sparse.config.ScmProperty;
import com.github.palencharj.gocd.sparse.scm.ScmResponse;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;

/**
 * Answers {@code scm-configuration}: which properties this material has.
 *
 * <p>GoCD asks once per plugin load and caches the answer, then uses it for the admin form, for
 * config-repo conversion, and — via {@code part-of-identity} — for every material fingerprint it
 * computes. See {@link ScmProperty} for why that last one deserves care.
 */
public class ScmConfigurationExecutor implements RequestExecutor {

    @Override
    public GoPluginApiResponse execute() {
        return ScmResponse.success(ScmProperty.metadata());
    }
}
