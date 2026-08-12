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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both halves of credential handling, each of which fails quietly when it is wrong: embed the
 * credential badly and every clone fails with what looks like a bad token, forget to redact and the
 * token lands in a build log.
 */
class RemoteUrlTest {

    private static final String MASK = "******";

    @Test
    void shouldEmbedCredentialsInAnHttpUrl() {
        assertThat(RemoteUrl.of("https://example.com/r.git", "alice", "s3cr3t").forCommandLine())
                .isEqualTo("https://alice:s3cr3t@example.com/r.git");
    }

    @Test
    void shouldLeaveAnSshRemoteAlone() {
        // ssh authenticates with a key. Injecting a password produces a URL git cannot use at all.
        assertThat(RemoteUrl.of("git@example.com:org/r.git", "alice", "s3cr3t").forCommandLine())
                .isEqualTo("git@example.com:org/r.git");
        assertThat(RemoteUrl.of("ssh://git@example.com/org/r.git", "alice", "s").forCommandLine())
                .isEqualTo("ssh://git@example.com/org/r.git");
    }

    @Test
    void shouldLeaveTheUrlAloneWhenThereIsNoUsername() {
        assertThat(RemoteUrl.of("https://example.com/r.git", null, null).forCommandLine())
                .isEqualTo("https://example.com/r.git");
        assertThat(RemoteUrl.of("https://example.com/r.git", "  ", "s3cr3t").forCommandLine())
                .isEqualTo("https://example.com/r.git");
    }

    @Test
    void shouldSendAUsernameWithNoPassword() {
        // A GitHub app token is often supplied as the username alone.
        assertThat(RemoteUrl.of("https://example.com/r.git", "ghs_token", null).forCommandLine())
                .isEqualTo("https://ghs_token@example.com/r.git");
    }

    @Test
    void shouldEncodeCredentialCharactersThatWouldOtherwiseRepointTheUrl() {
        // An unencoded '@' in the username ends the userinfo early, and git then resolves a host
        // that is not the one configured.
        assertThat(RemoteUrl.of("https://example.com/r.git", "alice@corp", "p@ss:word/x")
                .forCommandLine())
                .isEqualTo("https://alice%40corp:p%40ss%3Aword%2Fx@example.com/r.git");
    }

    @Test
    void shouldEncodeAPercentOnlyOnce() {
        // '%' has to be escaped before the escapes introduced afterwards, or they get escaped too.
        // Encoding twice turned 'alice@corp' into 'alice%2540corp' and every clone failed
        // authentication — the reason this is not built with URI's multi-argument constructor.
        assertThat(RemoteUrl.of("https://example.com/r.git", "a%40b", "p").forCommandLine())
                .isEqualTo("https://a%2540b:p@example.com/r.git");
    }

    @Test
    void shouldReplaceUserinfoAlreadyPresentInTheUrl() {
        assertThat(RemoteUrl.of("https://old:creds@example.com/r.git", "alice", "s3cr3t")
                .forCommandLine())
                .isEqualTo("https://alice:s3cr3t@example.com/r.git");
    }

    @Test
    void shouldNotMistakeAnAtSignInThePathForUserinfo() {
        assertThat(RemoteUrl.of("https://example.com/r@2x.git", "alice", "s3cr3t").forCommandLine())
                .isEqualTo("https://alice:s3cr3t@example.com/r@2x.git");
    }

    @Test
    void shouldMaskCredentialsWhenShowingTheUrlToAPerson() {
        assertThat(RemoteUrl.of("https://alice:s3cr3t@example.com/r.git", null, null).forDisplay())
                .isEqualTo("https://" + MASK + "@example.com/r.git");
        assertThat(RemoteUrl.of("https://example.com/r.git", "alice", "s3cr3t").forDisplay())
                .isEqualTo("https://example.com/r.git");
    }

    @Test
    void shouldRedactThePasswordFromCommandOutput() {
        RemoteUrl remote = RemoteUrl.of("https://example.com/r.git", "alice", "s3cr3t");

        String redacted = remote.redactFrom(SecretRedactor.Redactable
                .of("fatal: authentication failed for 'https://alice:s3cr3t@example.com/r.git'")).value();

        assertThat(redacted).doesNotContain("s3cr3t").contains(MASK);
    }

    @Test
    void shouldRedactThePasswordEvenInItsEncodedForm() {
        // The password reaches git percent-encoded, so that is the form git echoes back.
        RemoteUrl remote = RemoteUrl.of("https://example.com/r.git", "alice", "p@ss");

        String redacted = remote.redactFrom(SecretRedactor.Redactable.of("failed: p%40ss")).value();

        assertThat(redacted).doesNotContain("p%40ss").doesNotContain("p@ss");
    }

    @Test
    void shouldRedactUserinfoItWasNeverToldAbout() {
        // Belt and braces: a credential can reach the output by a route this instance knows nothing
        // about, for instance from a submodule or a redirect.
        RemoteUrl remote = RemoteUrl.of("https://example.com/r.git", null, null);

        String redacted = remote
                .redactFrom(SecretRedactor.Redactable.of("cloning https://bob:other@elsewhere/x.git"))
                .value();

        assertThat(redacted).doesNotContain("other").contains(MASK);
    }

    @Test
    void shouldToleratePassingNullThrough() {
        assertThat(RemoteUrl.of(null, "alice", "s").forCommandLine()).isNull();
        assertThat(RemoteUrl.of(null, "alice", "s").forDisplay()).isEmpty();
        assertThat(RemoteUrl.of("https://example.com", "alice", "s")
                .redactFrom(SecretRedactor.Redactable.of(null)).value()).isNull();
    }
}
