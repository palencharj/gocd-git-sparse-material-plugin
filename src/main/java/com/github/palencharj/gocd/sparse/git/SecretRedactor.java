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
package com.github.palencharj.gocd.sparse.git;

/**
 * Removes secrets from text on its way out of this plugin.
 *
 * <p>Modelled on GoCD's own {@code com.thoughtworks.go.util.command.SecretRedactor}, which this
 * plugin cannot use directly because it is not part of the published plugin API. Keeping the same
 * shape means the behaviour is familiar and the intent is obvious to anyone who knows the server
 * code: a redactor is a small, composable thing, and anything that carries command output also
 * carries the redactors needed to make it safe.
 *
 * <p>Redaction is deliberately a separate concern from running commands. A credential reaches this
 * plugin as plaintext and is passed to git on a command line, so it can surface in stdout, in
 * stderr, and in exception messages. Every one of those paths has to be scrubbed, and the only way
 * to be sure is for scrubbing to live in one place that all of them go through.
 */
public interface SecretRedactor {

    /**
     * Text on its way to a log, a build console, or an exception, along with whether anything has
     * already been removed from it.
     *
     * <p>The flag lets redactors compose without losing the knowledge that a secret was present —
     * useful when deciding whether a message is safe to log at a lower level.
     */
    record Redactable(String value, boolean wasRedacted) {

        public static Redactable of(String value) {
            return new Redactable(value, false);
        }

        public Redactable redactedTo(String newValue) {
            return new Redactable(newValue, wasRedacted || !equalsIgnoringNull(value, newValue));
        }

        private static boolean equalsIgnoringNull(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }

        @Override
        public String toString() {
            return value == null ? "" : value;
        }
    }

    Redactable redactFrom(Redactable toRedact);

    default String redactFrom(String toRedact) {
        return redactFrom(Redactable.of(toRedact)).value();
    }

    /** A redactor that changes nothing, for materials with no credentials to hide. */
    SecretRedactor NONE = toRedact -> toRedact;
}
