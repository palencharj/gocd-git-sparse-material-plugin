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

import com.github.palencharj.gocd.sparse.git.Revision;
import com.github.palencharj.gocd.sparse.scm.RevisionRepresenter;
import com.github.palencharj.gocd.sparse.scm.ScmRequest;
import com.github.palencharj.gocd.sparse.scm.ScmResponse;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;

import java.util.List;
import java.util.Map;

/**
 * Answers {@code latest-revisions-since}: every commit after the one GoCD already has.
 *
 * <p>This is the request that runs on every poll interval, for every material, forever — so it is
 * the one whose cost matters. An empty list is the normal answer and means nothing has changed.
 */
public class LatestRevisionsSinceExecutor extends PollingExecutor {

    public LatestRevisionsSinceExecutor(ScmRequest request) {
        super(request);
    }

    @Override
    protected String sinceExclusive() {
        return request.previousRevision();
    }

    @Override
    protected GoPluginApiResponse respond(List<Revision> revisions) {
        return ScmResponse.success(Map.of("revisions", RevisionRepresenter.toJSON(revisions)));
    }
}
