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
import com.github.palencharj.gocd.sparse.git.Revision;
import com.github.palencharj.gocd.sparse.scm.ScmRequest;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;

import java.util.List;

/**
 * Shared by the two requests GoCD polls with, {@code latest-revision} and
 * {@code latest-revisions-since}.
 *
 * <p>They differ only in where the history starts and how the answer is shaped, so everything else
 * lives here — which also means the two cannot drift apart on the parts that must agree. If polling
 * validated the configuration differently from polling-since, or filtered by path differently, GoCD
 * would see one material behaving as two.
 */
abstract class PollingExecutor implements RequestExecutor {

    protected final ScmRequest request;
    protected final MaterialConfiguration configuration;

    PollingExecutor(ScmRequest request) {
        this.request = request;
        this.configuration = request.configuration();
    }

    @Override
    public final GoPluginApiResponse execute() {
        configuration.assertUsable();
        Git git = Git.in(request.flyweightFolder(), configuration);
        git.syncPollingRepository();
        return respond(git.log(sinceExclusive(), pathspecs()));
    }

    /** The commit to report history after, or {@code null} for only the newest one. */
    protected abstract String sinceExclusive();

    /** Renders the commits found, newest first. */
    protected abstract GoPluginApiResponse respond(List<Revision> revisions);

    /**
     * Pathspecs to narrow polling by, or empty to consider every commit on the branch.
     *
     * <p>Off unless asked for. Restricting what gets checked out and restricting what triggers a
     * build are separate decisions, and coupling them silently would surprise anyone moving here
     * from GoCD's built-in git material — a commit that used to start a build would stop doing so
     * with no visible cause.
     */
    private List<String> pathspecs() {
        return configuration.isFilteredByPaths() ? configuration.pathspecs() : List.of();
    }
}
