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
import com.github.palencharj.gocd.sparse.scm.ScmResponse;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Answers {@code scm-view}: the admin form for this material.
 *
 * <p>The template is Angular markup rendered inside GoCD's own admin page, bound to the property
 * keys {@code ScmProperty} declares. It is read from the jar on each request rather than cached,
 * because the request arrives once per page load and correctness is worth more here than saving a
 * few kilobytes of I/O.
 */
public class ScmViewExecutor implements RequestExecutor {

    /** How this material is labelled in GoCD's "Material Type" dropdown. */
    public static final String DISPLAY_VALUE = "Git (sparse checkout)";

    private static final String TEMPLATE = "/scm.template.html";

    @Override
    public GoPluginApiResponse execute() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("displayValue", DISPLAY_VALUE);
        view.put("template", template());
        return ScmResponse.success(view);
    }

    private String template() {
        try (InputStream template = getClass().getResourceAsStream(TEMPLATE)) {
            if (template == null) {
                throw new MaterialException("This plugin's jar is missing " + TEMPLATE
                        + ", so its material cannot be configured. Reinstall the plugin.");
            }
            return new String(template.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MaterialException("Could not read " + TEMPLATE + " from this plugin's jar: "
                    + e.getMessage(), e);
        }
    }
}
