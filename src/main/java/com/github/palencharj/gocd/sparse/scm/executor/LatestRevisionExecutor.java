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

import com.github.palencharj.gocd.sparse.MaterialException;
import com.github.palencharj.gocd.sparse.git.Revision;
import com.github.palencharj.gocd.sparse.scm.RevisionRepresenter;
import com.github.palencharj.gocd.sparse.scm.ScmRequest;
import com.github.palencharj.gocd.sparse.scm.ScmResponse;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;

import java.util.List;
import java.util.Map;

/**
 * Answers {@code latest-revision}: the newest commit, asked for when a material is first added and
 * whenever GoCD has no history for it.
 *
 * <p>Unlike {@code latest-revisions-since}, an empty answer is not a valid one — GoCD needs a
 * revision to anchor the material to. Failing here says so; returning nothing would leave the
 * material stuck with no explanation.
 */
public class LatestRevisionExecutor extends PollingExecutor {

    public LatestRevisionExecutor(ScmRequest request) {
        super(request);
    }

    @Override
    protected String sinceExclusive() {
        return null;
    }

    @Override
    protected GoPluginApiResponse respond(List<Revision> revisions) {
        if (revisions.isEmpty()) {
            throw new MaterialException("Branch '" + configuration.branch() + "' has no commits"
                    + (configuration.isFilteredByPaths()
                    ? " touching the configured paths." : "."));
        }
        return ScmResponse.success(
                Map.of("revision", RevisionRepresenter.toJSON(revisions.get(0))));
    }
}
