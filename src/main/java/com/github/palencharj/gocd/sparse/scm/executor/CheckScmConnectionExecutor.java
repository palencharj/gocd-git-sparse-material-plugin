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
import com.github.palencharj.gocd.sparse.git.Git;
import com.github.palencharj.gocd.sparse.scm.ScmRequest;
import com.github.palencharj.gocd.sparse.scm.ScmResponse;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;

/**
 * Answers {@code check-scm-connection}: the "Check Connection" button.
 *
 * <p>Only the remote and the branch are tested. The configured paths are deliberately not
 * validated here even though they might be wrong, because reporting them as a connection failure
 * would be misleading — that is
 * {@link ValidateScmConfigurationExecutor}'s job, and GoCD runs it against the same form.
 */
public class CheckScmConnectionExecutor implements RequestExecutor {

    private final MaterialConfiguration configuration;

    public CheckScmConnectionExecutor(ScmRequest request) {
        this.configuration = request.configuration();
    }

    @Override
    public GoPluginApiResponse execute() {
        if (MaterialConfiguration.isBlank(configuration.url())) {
            return failure("Repository URL is required.");
        }
        Git.forRemote(configuration).checkConnection();
        return ScmResponse.status(true, "Connected to the repository, and branch '"
                + configuration.branch() + "' exists.");
    }

    @Override
    public GoPluginApiResponse failure(String message) {
        return ScmResponse.status(false, message);
    }
}
