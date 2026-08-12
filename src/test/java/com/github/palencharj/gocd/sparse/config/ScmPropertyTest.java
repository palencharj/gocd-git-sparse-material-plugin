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
package com.github.palencharj.gocd.sparse.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the material's declared metadata.
 *
 * <p>These are not style assertions. {@code part-of-identity} decides the material fingerprint, so
 * changing one of these values re-fingerprints every existing material using this plugin and detaches
 * it from its build history. A failure here should be read as "this is a migration", not "update the
 * test".
 */
class ScmPropertyTest {

    @Test
    void shouldDeclareTheSevenPropertiesTheAdminFormBindsTo() {
        assertThat(ScmProperty.keys()).containsExactly("url", "branch", "sparse_paths", "username",
                "password", "shallow", "filter_by_paths");
    }

    @Test
    void shouldIdentifyAMaterialByItsRepositoryBranchAndPaths() {
        // The paths belong here: two materials on the same repository and branch restricted to
        // different paths are genuinely different materials, and sharing a fingerprint would let
        // fan-in resolve a pipeline against a working copy that never held the code it needs.
        assertThat(ScmProperty.URL.isPartOfIdentity()).isTrue();
        assertThat(ScmProperty.BRANCH.isPartOfIdentity()).isTrue();
        assertThat(ScmProperty.SPARSE_PATHS.isPartOfIdentity()).isTrue();
    }

    @Test
    void shouldKeepCredentialsAndBehaviourFlagsOutOfTheIdentity() {
        // Rotating a token or toggling either flag must leave the material's history intact.
        assertThat(ScmProperty.USERNAME.isPartOfIdentity()).isFalse();
        assertThat(ScmProperty.PASSWORD.isPartOfIdentity()).isFalse();
        assertThat(ScmProperty.SHALLOW.isPartOfIdentity()).isFalse();
        assertThat(ScmProperty.FILTER_BY_PATHS.isPartOfIdentity()).isFalse();
    }

    @Test
    void shouldMarkOnlyThePasswordSecure() {
        assertThat(ScmProperty.PASSWORD.isSecure()).isTrue();
        assertThat(ScmProperty.values()).filteredOn(ScmProperty::isSecure)
                .containsExactly(ScmProperty.PASSWORD);
    }

    @Test
    void shouldSendDisplayOrderAsAStringBecauseGoCdCastsItToOne() {
        // JsonMessageHandler1_0 does Integer.valueOf((String) value). A JSON number arrives as a
        // Double, the cast fails, and the entire scm-configuration response is rejected.
        for (Map.Entry<String, Object> property : ScmProperty.metadata().entrySet()) {
            assertThat(described(property).get("display-order"))
                    .as("display-order for " + property.getKey()).isInstanceOf(String.class);
        }
    }

    @Test
    void shouldSendTheThreeFlagsAsBooleansBecauseGoCdCastsThemToBooleans() {
        for (Map.Entry<String, Object> property : ScmProperty.metadata().entrySet()) {
            assertThat(described(property)).as("flags for " + property.getKey())
                    .containsKeys("part-of-identity", "secure", "required");
            assertThat(described(property).get("part-of-identity")).isInstanceOf(Boolean.class);
            assertThat(described(property).get("secure")).isInstanceOf(Boolean.class);
            assertThat(described(property).get("required")).isInstanceOf(Boolean.class);
        }
    }

    @Test
    void shouldDescribeEveryPropertyInDeclarationOrder() {
        assertThat(ScmProperty.metadata().keySet()).containsExactlyElementsOf(ScmProperty.keys());
        int order = 0;
        for (Map.Entry<String, Object> property : ScmProperty.metadata().entrySet()) {
            assertThat(described(property).get("display-order")).isEqualTo(String.valueOf(order++));
        }
    }

    @Test
    void shouldOmitDefaultValueForPropertiesThatHaveNone() {
        // GoCD prefills the form field from default-value; an empty string is not the same as absent.
        assertThat(described("url")).doesNotContainKey("default-value");
        assertThat(described("branch")).containsEntry("default-value", "master");
        assertThat(described("shallow")).containsEntry("default-value", "false");
    }

    @Test
    void shouldResolveAKeyBackToItsProperty() {
        assertThat(ScmProperty.forKey("sparse_paths")).contains(ScmProperty.SPARSE_PATHS);
        assertThat(ScmProperty.forKey("pathz")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> described(Map.Entry<String, Object> property) {
        return (Map<String, Object>) property.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> described(String key) {
        return (Map<String, Object>) ScmProperty.metadata().get(key);
    }
}
