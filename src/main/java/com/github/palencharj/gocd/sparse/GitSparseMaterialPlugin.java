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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.thoughtworks.go.plugin.api.GoApplicationAccessor;
import com.thoughtworks.go.plugin.api.GoPlugin;
import com.thoughtworks.go.plugin.api.GoPluginIdentifier;
import com.thoughtworks.go.plugin.api.annotation.Extension;
import com.thoughtworks.go.plugin.api.logging.Logger;
import com.thoughtworks.go.plugin.api.request.GoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.DefaultGoPluginApiResponse;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A GoCD SCM material backed by git, whose working directory contains only the paths you ask for.
 *
 * <p>Implements the {@code scm} extension at version 1.0. The request and response shapes here are
 * dictated by GoCD's {@code JsonMessageHandler1_0}; a few of them are easy to get subtly wrong and
 * are called out where they occur.
 */
@Extension
public class GitSparseMaterialPlugin implements GoPlugin {

    private static final Logger LOGGER = Logger.getLoggerFor(GitSparseMaterialPlugin.class);
    private static final String EXTENSION_NAME = "scm";
    private static final List<String> SUPPORTED_VERSIONS = Collections.singletonList("1.0");

    private static final String REQUEST_SCM_CONFIGURATION = "scm-configuration";
    private static final String REQUEST_SCM_VIEW = "scm-view";
    private static final String REQUEST_VALIDATE = "validate-scm-configuration";
    private static final String REQUEST_CHECK_CONNECTION = "check-scm-connection";
    private static final String REQUEST_LATEST_REVISION = "latest-revision";
    private static final String REQUEST_LATEST_REVISIONS_SINCE = "latest-revisions-since";
    private static final String REQUEST_CHECKOUT = "checkout";

    /**
     * GoCD parses revision timestamps with a strict ISO-8601 offset parser, so the offset is not
     * optional. Emitting UTC with explicit milliseconds keeps it unambiguous.
     */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.UTC);

    private static final Gson GSON = new Gson();

    @Override
    public void initializeGoApplicationAccessor(GoApplicationAccessor accessor) {
        // This extension is entirely request/response; we never call back into the server.
    }

    @Override
    public GoPluginIdentifier pluginIdentifier() {
        return new GoPluginIdentifier(EXTENSION_NAME, SUPPORTED_VERSIONS);
    }

    @Override
    public GoPluginApiResponse handle(GoPluginApiRequest request) {
        String name = request.requestName();
        try {
            switch (name) {
                case REQUEST_SCM_CONFIGURATION:
                    return success(scmConfiguration());
                case REQUEST_SCM_VIEW:
                    return success(scmView());
                case REQUEST_VALIDATE:
                    return success(validate(request));
                case REQUEST_CHECK_CONNECTION:
                    return success(checkConnection(request));
                case REQUEST_LATEST_REVISION:
                    return success(latestRevision(request));
                case REQUEST_LATEST_REVISIONS_SINCE:
                    return success(latestRevisionsSince(request));
                case REQUEST_CHECKOUT:
                    return success(checkout(request));
                default:
                    return DefaultGoPluginApiResponse.badRequest("Unknown request: " + name);
            }
        } catch (Git.GitException e) {
            // Already redacted, and phrased for a human. Surface it as-is.
            LOGGER.warn("{} failed: {}", name, e.getMessage());
            return failureFor(name, e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.error("Unexpected error handling " + name, e);
            return failureFor(name, "Unexpected error: " + e);
        }
    }

    // ------------------------------------------------------------- configuration

    private Map<String, Object> scmConfiguration() {
        Map<String, Object> config = new LinkedHashMap<>();
        // part-of-identity drives the material fingerprint (see SCM#getFingerprint in GoCD, which
        // hashes only the properties marked here).
        //
        // The paths are part of it deliberately. Two materials on the same repository and branch but
        // restricted to different paths are genuinely different materials; giving them one
        // fingerprint would make GoCD treat them as interchangeable and let fan-in resolve a
        // pipeline against a working copy that never contained the paths it needs. The cost is that
        // editing the paths starts a fresh material history, which is the right trade against
        // silently building the wrong tree.
        config.put(ScmConfig.URL, field("Repository URL", null, true, false, true, 0));
        config.put(ScmConfig.BRANCH, field("Branch", ScmConfig.DEFAULT_BRANCH, true, false, false, 1));
        config.put(ScmConfig.SPARSE_PATHS, field("Paths to check out", null, true, false, true, 2));
        config.put(ScmConfig.USERNAME, field("Username", null, false, false, false, 3));
        config.put(ScmConfig.PASSWORD, field("Password", null, false, true, false, 4));
        config.put(ScmConfig.SHALLOW, field("Shallow clone", "false", false, false, false, 5));
        config.put(ScmConfig.FILTER_BY_PATHS,
                field("Only trigger on changes under these paths", "false", false, false, false, 6));
        return config;
    }

    private Map<String, Object> field(String displayName, String defaultValue, boolean partOfIdentity,
                                      boolean secure, boolean required, int order) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("display-name", displayName);
        if (defaultValue != null) {
            f.put("default-value", defaultValue);
        }
        f.put("part-of-identity", partOfIdentity);
        f.put("secure", secure);
        f.put("required", required);
        // Deliberately a string: GoCD reads this with Integer.valueOf((String) value), so a JSON
        // number arrives as a Double and blows up the whole configuration response.
        f.put("display-order", String.valueOf(order));
        return f;
    }

    private Map<String, Object> scmView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("displayValue", "Git (sparse checkout)");
        view.put("template", readResource("/scm.template.html"));
        return view;
    }

    // ------------------------------------------------------------- validation

    /** Responds with a list of {@code {key, message}}; an empty list means the config is valid. */
    private List<Map<String, String>> validate(GoPluginApiRequest request) {
        ScmConfig config = configOf(request);
        List<Map<String, String>> errors = new ArrayList<>();

        // Nothing upstream rejects a key we do not recognise. Worse, GoCD includes an undeclared
        // property in the material fingerprint, so a typo in a config repo's `options:` silently
        // changes the material's identity and detaches it from its build history. This is the only
        // place that can be caught, so be strict about it.
        for (String key : configKeysOf(request)) {
            if (!ScmConfig.KNOWN_KEYS.contains(key)) {
                errors.add(error(key, "Unknown property '" + key + "'. GoCD includes properties"
                        + " this plugin does not declare in the material's fingerprint, so a typo"
                        + " here would change the material's identity and detach it from its build"
                        + " history. Valid properties are: "
                        + String.join(", ", ScmConfig.KNOWN_KEYS) + "."));
            }
        }

        if (ScmConfig.isBlank(config.url())) {
            errors.add(error(ScmConfig.URL, "Repository URL is required."));
        }

        List<String> paths = config.sparsePaths();
        if (paths.isEmpty()) {
            errors.add(error(ScmConfig.SPARSE_PATHS,
                    "Enter at least one path, one per line. To check out everything, use GoCD's"
                            + " built-in Git material instead."));
        } else if (config.pathspecs().isEmpty()) {
            // Every pattern is a negation, so nothing would ever be selected. git accepts this
            // happily and produces an empty working directory, which then fails the build in a
            // thoroughly confusing way. Much better to reject it here.
            errors.add(error(ScmConfig.SPARSE_PATHS,
                    "Every pattern is an exclusion, so nothing would be checked out. Add at least"
                            + " one pattern that selects files."));
        }
        for (String path : paths) {
            if (path.contains("..")) {
                errors.add(error(ScmConfig.SPARSE_PATHS,
                        "'" + path + "' looks like it escapes the repository. Patterns are relative"
                                + " to the repository root."));
                break;
            }
        }
        return errors;
    }

    private Map<String, String> error(String key, String message) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("key", key);
        e.put("message", message);
        return e;
    }

    private Map<String, Object> checkConnection(GoPluginApiRequest request) {
        ScmConfig config = configOf(request);
        if (ScmConfig.isBlank(config.url())) {
            return result(false, "Repository URL is required.");
        }
        new Git(null, config).checkConnection();
        return result(true, "Connected to the repository, and branch '" + config.branch() + "' exists.");
    }

    // ------------------------------------------------------------- polling

    private Map<String, Object> latestRevision(GoPluginApiRequest request) {
        ScmConfig config = configOf(request);
        Git git = pollingRepo(request, config);

        List<Revision> revisions = git.log(null, pathspecsFor(config));
        if (revisions.isEmpty()) {
            throw new Git.GitException("Branch '" + config.branch() + "' has no commits"
                    + (config.filterByPaths() ? " touching the configured paths." : "."));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("revision", revisionToMap(revisions.get(0)));
        return response;
    }

    private Map<String, Object> latestRevisionsSince(GoPluginApiRequest request) {
        ScmConfig config = configOf(request);
        Git git = pollingRepo(request, config);

        String previous = previousRevisionOf(request);
        List<Map<String, Object>> revisions = new ArrayList<>();
        for (Revision revision : git.log(previous, pathspecsFor(config))) {
            revisions.add(revisionToMap(revision));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("revisions", revisions);
        return response;
    }

    /**
     * Pathspecs to narrow polling by, or empty to consider every commit.
     *
     * <p>Off by default on purpose. Restricting the checkout and restricting what triggers a build
     * are genuinely separate decisions, and silently coupling them would surprise people migrating
     * from the built-in git material.
     */
    private List<String> pathspecsFor(ScmConfig config) {
        return config.filterByPaths() ? config.pathspecs() : Collections.<String>emptyList();
    }

    private Git pollingRepo(GoPluginApiRequest request, ScmConfig config) {
        String flyweight = string(body(request), "flyweight-folder");
        if (ScmConfig.isBlank(flyweight)) {
            throw new Git.GitException("GoCD did not supply a working folder to poll in.");
        }
        Git git = new Git(new File(flyweight), config);
        git.syncPollingRepo();
        return git;
    }

    // ------------------------------------------------------------- checkout

    private Map<String, Object> checkout(GoPluginApiRequest request) {
        Map<String, Object> body = body(request);
        ScmConfig config = configOf(request);

        String destination = string(body, "destination-folder");
        if (ScmConfig.isBlank(destination)) {
            throw new Git.GitException("GoCD did not supply a destination folder.");
        }
        String sha = revisionOf(body);
        if (ScmConfig.isBlank(sha)) {
            throw new Git.GitException("GoCD did not supply a revision to check out.");
        }

        new Git(new File(destination), config).checkout(sha);

        List<String> paths = config.sparsePaths();
        return result(true, "Checked out " + sha + " with " + paths.size()
                + (paths.size() == 1 ? " path: " : " paths: ") + String.join(", ", paths));
    }

    // ------------------------------------------------------------- json helpers

    private Map<String, Object> revisionToMap(Revision revision) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("revision", revision.sha);
        map.put("timestamp", TIMESTAMP.format(revision.timestamp));
        map.put("user", revision.author);
        map.put("revisionComment", revision.comment);
        List<Map<String, String>> files = new ArrayList<>();
        for (Revision.ModifiedFile file : revision.modifiedFiles) {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("fileName", file.fileName);
            f.put("action", file.action);
            files.add(f);
        }
        map.put("modifiedFiles", files);
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(GoPluginApiRequest request) {
        String raw = request.requestBody();
        if (ScmConfig.isBlank(raw)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> parsed = GSON.fromJson(raw, new TypeToken<Map<String, Object>>() {}.getType());
        return parsed == null ? new LinkedHashMap<>() : parsed;
    }

    /** The property keys actually present in the request, declared or not. */
    @SuppressWarnings("unchecked")
    private List<String> configKeysOf(GoPluginApiRequest request) {
        Object scmConfiguration = body(request).get(REQUEST_SCM_CONFIGURATION);
        if (!(scmConfiguration instanceof Map)) {
            return Collections.emptyList();
        }
        return new ArrayList<>(((Map<String, Object>) scmConfiguration).keySet());
    }

    @SuppressWarnings("unchecked")
    private ScmConfig configOf(GoPluginApiRequest request) {
        Object scmConfiguration = body(request).get(REQUEST_SCM_CONFIGURATION);
        return ScmConfig.from(scmConfiguration instanceof Map
                ? (Map<String, Object>) scmConfiguration
                : new LinkedHashMap<>());
    }

    @SuppressWarnings("unchecked")
    private String previousRevisionOf(GoPluginApiRequest request) {
        Object previous = body(request).get("previous-revision");
        if (!(previous instanceof Map)) {
            return null;
        }
        String sha = string((Map<String, Object>) previous, "revision");
        return ScmConfig.isBlank(sha) ? null : sha;
    }

    @SuppressWarnings("unchecked")
    private String revisionOf(Map<String, Object> body) {
        Object revision = body.get("revision");
        return revision instanceof Map ? string((Map<String, Object>) revision, "revision") : null;
    }

    private String string(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : value.toString();
    }

    private Map<String, Object> result(boolean ok, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", ok ? "success" : "failure");
        result.put("messages", Collections.singletonList(message));
        return result;
    }

    /**
     * Turns a thrown error into the shape the specific request expects. {@code check-scm-connection}
     * and {@code checkout} are read as a status/messages map, so a plain error response would be
     * reported to the user as a deserialisation failure instead of the real cause.
     */
    private GoPluginApiResponse failureFor(String requestName, String message) {
        if (REQUEST_CHECK_CONNECTION.equals(requestName) || REQUEST_CHECKOUT.equals(requestName)) {
            return success(result(false, message));
        }
        return DefaultGoPluginApiResponse.error(message);
    }

    private GoPluginApiResponse success(Object body) {
        return DefaultGoPluginApiResponse.success(GSON.toJson(body));
    }

    private String readResource(String path) {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing packaged resource: " + path);
            }
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw new IllegalStateException("Could not read packaged resource: " + path, e);
        }
    }
}
