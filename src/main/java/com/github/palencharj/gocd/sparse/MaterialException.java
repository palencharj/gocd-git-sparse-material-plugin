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

import java.io.Serial;

/**
 * A failure whose message is already redacted and already phrased for a person.
 *
 * <p>This type is the contract between the plugin's internals and the code that answers GoCD:
 * anything thrown as a {@code MaterialException} may be shown verbatim in a build console or the
 * GoCD UI. Anything else escaping this plugin is a bug in it, and is reported as an unexpected error
 * rather than shown raw.
 *
 * <p>Every message should say what was being attempted and, where it can, what to do about it. The
 * alternative — passing a tool's own output through unchanged — produces failures that are
 * technically accurate and practically useless, because the reader has no idea which material or
 * which setting caused them.
 */
public class MaterialException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public MaterialException(String message) {
        super(message);
    }

    public MaterialException(String message, Throwable cause) {
        super(message, cause);
    }
}
