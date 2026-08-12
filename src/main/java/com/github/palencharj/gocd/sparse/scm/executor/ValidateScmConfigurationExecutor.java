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

import com.github.palencharj.gocd.sparse.config.MaterialConfiguration;
import com.github.palencharj.gocd.sparse.scm.ScmRequest;
import com.github.palencharj.gocd.sparse.scm.ScmResponse;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers {@code validate-scm-configuration} with one {@code {key, message}} per problem, or an
 * empty list when the configuration is fine.
 *
 * <p>Attributing each message to a key is what makes GoCD show it against the offending field
 * instead of as an unattached banner, so a message here should read as help text for that one field.
 *
 * <p>Reached from the admin UI and the SCM API but <em>not</em> from a config repo; see
 * {@link MaterialConfiguration#assertUsable()} for how that gap is covered.
 */
public class ValidateScmConfigurationExecutor implements RequestExecutor {

    private final MaterialConfiguration configuration;

    public ValidateScmConfigurationExecutor(ScmRequest request) {
        this.configuration = request.configuration();
    }

    @Override
    public GoPluginApiResponse execute() {
        List<Map<String, String>> errors = new ArrayList<>();
        for (MaterialConfiguration.ValidationError error : configuration.validate()) {
            Map<String, String> json = new LinkedHashMap<>();
            json.put("key", error.key());
            json.put("message", error.message());
            errors.add(json);
        }
        return ScmResponse.success(errors);
    }
}
