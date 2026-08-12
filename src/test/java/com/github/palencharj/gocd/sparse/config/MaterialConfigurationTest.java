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

import com.github.palencharj.gocd.sparse.MaterialException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaterialConfigurationTest {

    @Test
    void shouldReadTheNestedValueShapeGoCdSends() {
        MaterialConfiguration configuration = configuration(
                "url", "https://example.com/r.git",
                "branch", "main",
                "sparse_paths", "src");

        assertThat(configuration.url()).isEqualTo("https://example.com/r.git");
        assertThat(configuration.branch()).isEqualTo("main");
        assertThat(configuration.sparsePaths()).containsExactly("src");
    }

    @Test
    void shouldDefaultTheBranchWhenAbsentOrBlank() {
        assertThat(configuration("url", "u").branch()).isEqualTo("master");
        assertThat(configuration("url", "u", "branch", "   ").branch()).isEqualTo("master");
    }

    @Test
    void shouldSurviveAnEmptyOrMissingConfiguration() {
        assertThat(MaterialConfiguration.from(null).sparsePaths()).isEmpty();
        assertThat(MaterialConfiguration.from(Map.of()).url()).isNull();
    }

    @Test
    void shouldSplitPathsOnLinesDroppingBlanksAndSurroundingWhitespace() {
        MaterialConfiguration configuration =
                configuration("sparse_paths", "  a/b  \n\n\t c/d \n   \n");

        assertThat(configuration.sparsePaths()).containsExactly("a/b", "c/d");
    }

    @Test
    void shouldTreatAPathWithSpacesAsOnePattern() {
        // Line-delimited rather than space-delimited precisely so this works.
        assertThat(configuration("sparse_paths", "My Documents/report.docx").sparsePaths())
                .containsExactly("My Documents/report.docx");
    }

    @Test
    void shouldNotTrimThePasswordBecauseWhitespaceCanBePartOfAToken() {
        assertThat(configuration("password", "  token  ").password()).isEqualTo("  token  ");
    }

    @Test
    void shouldParseBooleanFlagsFromTheirStringForm() {
        assertThat(configuration("shallow", "true").isShallow()).isTrue();
        assertThat(configuration("shallow", "false").isShallow()).isFalse();
        // Absent means the declared default, which is false for both flags.
        assertThat(configuration("url", "u").isShallow()).isFalse();
        assertThat(configuration("url", "u").isFilteredByPaths()).isFalse();
        assertThat(configuration("filter_by_paths", "true").isFilteredByPaths()).isTrue();
    }

    @Test
    void shouldDropNegationsFromPathspecsBecauseGitLogCannotUseThem() {
        MaterialConfiguration configuration = configuration("sparse_paths", "src\n!src/generated");

        assertThat(configuration.sparsePaths()).containsExactly("src", "!src/generated");
        assertThat(configuration.pathspecs()).containsExactly("src");
    }

    @Test
    void shouldStripALeadingSlashFromPathspecsBecauseTheyAreRepositoryRelative() {
        assertThat(configuration("sparse_paths", "/src/main").pathspecs()).containsExactly("src/main");
    }

    @Test
    void shouldReportNoPathspecsWhenEveryPatternExcludes() {
        assertThat(configuration("sparse_paths", "!a\n!b").pathspecs()).isEmpty();
    }

    @Test
    void shouldListPropertiesItDoesNotDeclare() {
        MaterialConfiguration configuration = configuration("url", "u", "pathz", "src");

        assertThat(configuration.unknownKeys()).containsExactly("pathz");
    }

    // ------------------------------------------------------------------ validation

    @Test
    void shouldAcceptAUsableConfiguration() {
        MaterialConfiguration configuration =
                configuration("url", "https://example.com/r.git", "sparse_paths", "src");

        assertThat(configuration.validate()).isEmpty();
    }

    @Test
    void shouldRequireAUrl() {
        assertThat(configuration("sparse_paths", "src").validate())
                .extracting(MaterialConfiguration.ValidationError::key).contains("url");
    }

    @Test
    void shouldRequireAtLeastOnePath() {
        assertThat(configuration("url", "u", "sparse_paths", "\n  \n").validate())
                .extracting(MaterialConfiguration.ValidationError::key).contains("sparse_paths");
    }

    @Test
    void shouldRejectAConfigurationThatWouldCheckOutNothing() {
        // git accepts an all-exclusions pattern set and quietly produces an empty working directory,
        // which then fails the build a long way from the cause.
        assertThat(configuration("url", "u", "sparse_paths", "!a\n!b").validate())
                .extracting(MaterialConfiguration.ValidationError::message)
                .anyMatch(message -> message.contains("nothing would be checked out"));
    }

    @Test
    void shouldRejectAPathThatEscapesTheRepository() {
        assertThat(configuration("url", "u", "sparse_paths", "../../etc/passwd").validate())
                .extracting(MaterialConfiguration.ValidationError::key).contains("sparse_paths");
    }

    @Test
    void shouldRejectAnUnknownPropertyBecauseGoCdFoldsItIntoTheFingerprint() {
        // SCM#getFingerprint includes any property the plugin has no metadata for, so a typo silently
        // changes which material a pipeline points at. Nothing upstream reports it.
        assertThat(configuration("url", "u", "sparse_paths", "src", "pathz", "src").validate())
                .extracting(MaterialConfiguration.ValidationError::key).contains("pathz");
    }

    @Test
    void shouldNameTheOffendingPropertyWhenRefusingToRunAtAll() {
        // The message goes to a build console, where there is no field to attach it to.
        assertThatThrownBy(() -> configuration("url", "u").assertUsable())
                .isInstanceOf(MaterialException.class)
                .hasMessageContaining("sparse_paths")
                .hasMessageContaining("not configured correctly");
    }

    @Test
    void shouldReportEveryProblemAtOnceRatherThanTheFirst() {
        assertThatThrownBy(() -> configuration("pathz", "x").assertUsable())
                .hasMessageContaining("url")
                .hasMessageContaining("sparse_paths")
                .hasMessageContaining("pathz");
    }

    @Test
    void shouldPassTheRuntimeCheckForAUsableConfiguration() {
        configuration("url", "u", "sparse_paths", "src").assertUsable();
    }

    private static MaterialConfiguration configuration(String... keysAndValues) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            raw.put(keysAndValues[i], Map.of("value", keysAndValues[i + 1]));
        }
        return MaterialConfiguration.from(raw);
    }
}
