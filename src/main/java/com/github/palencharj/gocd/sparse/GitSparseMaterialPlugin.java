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
package com.github.palencharj.gocd.sparse;

import com.github.palencharj.gocd.sparse.scm.ScmRequest;
import com.github.palencharj.gocd.sparse.scm.executor.CheckScmConnectionExecutor;
import com.github.palencharj.gocd.sparse.scm.executor.CheckoutExecutor;
import com.github.palencharj.gocd.sparse.scm.executor.LatestRevisionExecutor;
import com.github.palencharj.gocd.sparse.scm.executor.LatestRevisionsSinceExecutor;
import com.github.palencharj.gocd.sparse.scm.executor.RequestExecutor;
import com.github.palencharj.gocd.sparse.scm.executor.ScmConfigurationExecutor;
import com.github.palencharj.gocd.sparse.scm.executor.ScmViewExecutor;
import com.github.palencharj.gocd.sparse.scm.executor.ValidateScmConfigurationExecutor;
import com.thoughtworks.go.plugin.api.AbstractGoPlugin;
import com.thoughtworks.go.plugin.api.GoPluginIdentifier;
import com.thoughtworks.go.plugin.api.annotation.Extension;
import com.thoughtworks.go.plugin.api.exceptions.UnhandledRequestTypeException;
import com.thoughtworks.go.plugin.api.logging.Logger;
import com.thoughtworks.go.plugin.api.request.GoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;

import java.util.List;

/**
 * A GoCD SCM material backed by git, whose working directory contains only the paths you ask for.
 *
 * <p>Implements the {@code scm} extension at version 1.0 — the only version there has ever been.
 * This class routes and handles failures; each request is answered by its own
 * {@link RequestExecutor}, and the protocol's JSON lives in
 * {@link com.github.palencharj.gocd.sparse.scm}.
 */
@Extension
public class GitSparseMaterialPlugin extends AbstractGoPlugin {

    private static final Logger LOGGER = Logger.getLoggerFor(GitSparseMaterialPlugin.class);

    private static final String EXTENSION_NAME = "scm";
    private static final List<String> SUPPORTED_VERSIONS = List.of("1.0");

    private static final String SCM_CONFIGURATION = "scm-configuration";
    private static final String SCM_VIEW = "scm-view";
    private static final String VALIDATE_SCM_CONFIGURATION = "validate-scm-configuration";
    private static final String CHECK_SCM_CONNECTION = "check-scm-connection";
    private static final String LATEST_REVISION = "latest-revision";
    private static final String LATEST_REVISIONS_SINCE = "latest-revisions-since";
    private static final String CHECKOUT = "checkout";

    @Override
    public GoPluginIdentifier pluginIdentifier() {
        return new GoPluginIdentifier(EXTENSION_NAME, SUPPORTED_VERSIONS);
    }

    @Override
    public GoPluginApiResponse handle(GoPluginApiRequest request) throws UnhandledRequestTypeException {
        RequestExecutor executor = executorFor(request);
        try {
            return executor.execute();
        } catch (MaterialException e) {
            // Already redacted and already phrased for a person; show it unchanged.
            LOGGER.warn("{} failed: {}", request.requestName(), e.getMessage());
            return executor.failure(e.getMessage());
        } catch (RuntimeException e) {
            // A bug in this plugin. Log the stack trace where an operator can find it, and tell the
            // user something went wrong without pasting internals into the GoCD UI.
            LOGGER.error("Unexpected error handling " + request.requestName(), e);
            return executor.failure("The Git sparse checkout plugin failed unexpectedly: " + e
                    + ". See the GoCD server or agent log for the full stack trace.");
        }
    }

    /**
     * Picks the handler for a request.
     *
     * <p>An unknown request name is a checked exception rather than a bad-request response because
     * that is what the extension point declares: GoCD logs it against this plugin and gives up on
     * the call, which is the right outcome for a server asking for something this version cannot do.
     */
    private RequestExecutor executorFor(GoPluginApiRequest request)
            throws UnhandledRequestTypeException {
        return switch (request.requestName()) {
            case SCM_CONFIGURATION -> new ScmConfigurationExecutor();
            case SCM_VIEW -> new ScmViewExecutor();
            case VALIDATE_SCM_CONFIGURATION ->
                    new ValidateScmConfigurationExecutor(ScmRequest.from(request));
            case CHECK_SCM_CONNECTION -> new CheckScmConnectionExecutor(ScmRequest.from(request));
            case LATEST_REVISION -> new LatestRevisionExecutor(ScmRequest.from(request));
            case LATEST_REVISIONS_SINCE ->
                    new LatestRevisionsSinceExecutor(ScmRequest.from(request));
            case CHECKOUT -> new CheckoutExecutor(ScmRequest.from(request));
            default -> throw new UnhandledRequestTypeException(request.requestName());
        };
    }
}
