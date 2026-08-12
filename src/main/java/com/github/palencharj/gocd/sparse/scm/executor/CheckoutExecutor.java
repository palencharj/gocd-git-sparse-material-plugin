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

import java.util.List;

/**
 * Answers {@code checkout}: the only request that runs on an agent rather than the server.
 *
 * <p>That is worth remembering when reading anything this reaches. The agent needs its own git on
 * {@code PATH}, its own network route to the remote, and its own credentials — and the message
 * returned here is what lands in the build console, so it is the only thing whoever is looking at a
 * red build gets to read.
 */
public class CheckoutExecutor implements RequestExecutor {

    private final ScmRequest request;
    private final MaterialConfiguration configuration;

    public CheckoutExecutor(ScmRequest request) {
        this.request = request;
        this.configuration = request.configuration();
    }

    @Override
    public GoPluginApiResponse execute() {
        configuration.assertUsable();
        String sha = request.revision();
        Git.in(request.destinationFolder(), configuration).checkout(sha);

        // Naming the paths, rather than counting them, is what turns "why is this file missing?"
        // into a question the console log already answers.
        List<String> paths = configuration.sparsePaths();
        return ScmResponse.status(true, "Checked out " + sha + " with " + paths.size()
                + (paths.size() == 1 ? " path: " : " paths: ") + String.join(", ", paths));
    }

    @Override
    public GoPluginApiResponse failure(String message) {
        return ScmResponse.status(false, message);
    }
}
