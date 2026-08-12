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
 * A remote in two forms: one for git, with credentials embedded, and one for humans, without.
 *
 * <p>Its own class because both halves of this are easy to get wrong, and each mistake is quiet.
 * Embed the credential badly and every clone fails with an authentication error that looks like a
 * bad token. Forget to redact and the token lands in a build log that anyone can read.
 *
 * <p>Doubling as the {@link SecretRedactor} for its own credential is deliberate: the object that
 * knows the secret is the object that knows how to hide it, so there is no way to pass one to a
 * command without the other.
 */
public final class RemoteUrl implements SecretRedactor {

    private static final String MASK = "******";

    private final String url;
    private final String username;
    private final String password;

    private RemoteUrl(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    public static RemoteUrl of(String url, String username, String password) {
        return new RemoteUrl(url, username, password);
    }

    /**
     * The URL to hand to git.
     *
     * <p>Credentials are embedded only for HTTP(S). An {@code ssh://} or scp-style remote
     * authenticates with a key, and injecting a password there produces a URL git cannot use.
     *
     * <p>Built by string surgery rather than with {@link java.net.URI}'s multi-argument constructor,
     * which percent-encodes the userinfo it is given. Since the parts must already be encoded to be
     * unambiguous, that constructor double-encodes them — a username of {@code alice@corp} became
     * {@code alice%2540corp}, and every clone failed with "Invalid username or token". Encoding once,
     * here, is the only way to be certain how many times it happened.
     */
    public String forCommandLine() {
        if (url == null || isBlank(username) || !isHttp()) {
            return url;
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        String scheme = url.substring(0, schemeEnd + 3);
        String remainder = stripExistingUserInfo(url.substring(schemeEnd + 3));

        StringBuilder userInfo = new StringBuilder(encode(username));
        if (!isBlank(password)) {
            userInfo.append(':').append(encode(password));
        }
        return scheme + userInfo + "@" + remainder;
    }

    /** The URL as it should appear in a log or an error message. */
    public String forDisplay() {
        return url == null ? "" : url.replaceAll("(://)[^/@\\s]+(@)", "$1" + MASK + "$2");
    }

    @Override
    public Redactable redactFrom(Redactable toRedact) {
        String text = toRedact.value();
        if (text == null) {
            return toRedact;
        }
        String redacted = text;
        if (!isBlank(password)) {
            redacted = redacted.replace(password, MASK).replace(encode(password), MASK);
        }
        // Belt and braces: catch any userinfo that reached the text by another route, including a
        // credential this instance does not know about.
        redacted = redacted.replaceAll("(://)[^/@\\s]+(@)", "$1" + MASK + "$2");
        return toRedact.redactedTo(redacted);
    }

    private boolean isHttp() {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    /** Drops any userinfo already present, so a caller-supplied credential always wins. */
    private static String stripExistingUserInfo(String authorityAndPath) {
        int at = authorityAndPath.indexOf('@');
        int firstSlash = authorityAndPath.indexOf('/');
        boolean atIsInAuthority = at > -1 && (firstSlash == -1 || at < firstSlash);
        return atIsInAuthority ? authorityAndPath.substring(at + 1) : authorityAndPath;
    }

    /**
     * Percent-encodes only the characters that would otherwise change how the userinfo parses.
     *
     * <p>{@code %} goes first, so the escapes introduced afterwards are not themselves re-escaped.
     */
    private static String encode(String value) {
        return value.replace("%", "%25")
                .replace(":", "%3A")
                .replace("@", "%40")
                .replace("/", "%2F");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
