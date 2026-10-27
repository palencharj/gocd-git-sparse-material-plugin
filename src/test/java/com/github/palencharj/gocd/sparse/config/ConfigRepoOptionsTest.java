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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a YAML author sees when an {@code options:} block is wrong, and how forgiving the plugin is
 * about the way YAML delivers values.
 */
class ConfigRepoOptionsTest {

    // ------------------------------------------------------------------ error messages

    @Test
    void shouldSuggestTheDeclaredPropertyForATypo() {
        assertThat(ScmProperty.suggestionFor("sparse_path")).contains("sparse_paths");
        assertThat(ScmProperty.suggestionFor("Sparse-Paths")).contains("sparse_paths");
        assertThat(ScmProperty.suggestionFor("shalow")).contains("shallow");
        assertThat(ScmProperty.suggestionFor("filter_by_path")).contains("filter_by_paths");
        assertThat(ScmProperty.suggestionFor("partialclone")).contains("partial_clone");
        assertThat(ScmProperty.suggestionFor("URL")).contains("url");
    }

    @Test
    void shouldSuggestFromKnownAliases() {
        assertThat(ScmProperty.suggestionFor("paths")).contains("sparse_paths");
        assertThat(ScmProperty.suggestionFor("token")).contains("password");
        assertThat(ScmProperty.suggestionFor("repository")).contains("url");
    }

    @Test
    void shouldNotSuggestAnythingForUnrelatedNames() {
        assertThat(ScmProperty.suggestionFor("destination")).isEmpty();
        assertThat(ScmProperty.suggestionFor("zzzzzz")).isEmpty();
    }

    @Test
    void shouldNameTheKeyAndOfferTheSuggestionWhenRejectingAnUnknownOption() {
        List<MaterialConfiguration.ValidationError> errors =
                configuration("url", "u", "sparse_path", "src").validate();

        assertThat(errors).filteredOn(error -> error.key().equals("sparse_path"))
                .singleElement()
                .satisfies(error -> assertThat(error.message())
                        .contains("'sparse_path'")
                        .contains("Did you mean 'sparse_paths'?")
                        .contains("under 'options:'"));
    }

    @Test
    void shouldStillRejectTheMisspeltOptionRatherThanAcceptingItsSuggestion() {
        // Advice only: guessing would change which material the pipeline points at.
        assertThat(configuration("url", "u", "sparse_paths", "src", "shalow", "true").validate())
                .extracting(MaterialConfiguration.ValidationError::key).containsExactly("shalow");
    }

    @Test
    void shouldTellAYamlAuthorWhereTheMissingOptionsBelong() {
        assertThat(configuration("sparse_paths", "src").validate()).singleElement()
                .satisfies(error -> {
                    assertThat(error.key()).isEqualTo("url");
                    assertThat(error.message()).contains("'options:'");
                });
        assertThat(configuration("url", "u").validate()).singleElement()
                .satisfies(error -> {
                    assertThat(error.key()).isEqualTo("sparse_paths");
                    assertThat(error.message()).contains("'|' block");
                });
    }

    @Test
    void shouldListEachRuntimeProblemOnItsOwnLineWithItsKey() {
        assertThatThrownBy(() -> configuration("pathz", "x").assertUsable())
                .isInstanceOf(MaterialException.class)
                .hasMessageContaining("(3 problems):")
                .satisfies(e -> assertThat(e.getMessage().lines().filter(l -> l.startsWith("  - ")))
                        .extracting(l -> l.substring(4, l.indexOf(':')))
                        .containsExactly("pathz", "url", "sparse_paths"));
    }

    @Test
    void shouldRaiseAtRuntimeExactlyWhatValidateReports() {
        // Both paths run the same routine, so they cannot disagree about what is acceptable.
        MaterialConfiguration configuration = configuration("pathz", "x", "sparse_paths", "!a");

        assertThatThrownBy(configuration::assertUsable).satisfies(e -> {
            for (MaterialConfiguration.ValidationError error : configuration.validate()) {
                assertThat(e.getMessage()).contains(error.key() + ": " + error.message());
            }
        });
    }

    // ------------------------------------------------------------------ how YAML delivers values

    @Test
    void shouldTolerateEveryShapeAYamlBlockScalarProduces() {
        // '|' keeps a trailing newline, '|-' drops it, '|+' keeps all trailing blank lines, and a
        // file saved on Windows brings CRLF. All must mean the same two patterns.
        for (String block : List.of(
                "services/billing\nlibs/shared/*\n",
                "services/billing\nlibs/shared/*",
                "services/billing\nlibs/shared/*\n\n\n",
                "services/billing\r\nlibs/shared/*\r\n",
                "services/billing\rlibs/shared/*\r",
                "  services/billing  \n\tlibs/shared/*\t\n",
                "\n\nservices/billing\n\nlibs/shared/*\n")) {
            assertThat(configuration("sparse_paths", block).sparsePaths())
                    .as("block %s", block.replace("\r", "\\r").replace("\n", "\\n"))
                    .containsExactly("services/billing", "libs/shared/*");
        }
    }

    @Test
    void shouldReadBooleansCaseInsensitivelyAndIgnoringSurroundingWhitespace() {
        for (String truthy : List.of("true", "True", "TRUE", " true ", "true\n")) {
            assertThat(configuration("shallow", truthy).isShallow()).as(truthy).isTrue();
            assertThat(configuration("partial_clone", truthy).isPartialClone()).as(truthy).isTrue();
            assertThat(configuration("filter_by_paths", truthy).isFilteredByPaths()).as(truthy).isTrue();
        }
        for (String falsy : List.of("false", "False", "", "   ")) {
            assertThat(configuration("shallow", falsy).isShallow()).as(falsy).isFalse();
        }
    }

    @Test
    void shouldTreatAnyOtherFlagTextAsFalseAsItAlwaysHas() {
        // Pinned, not endorsed: 'yes' and '1' are silently off. Rejecting them would turn
        // configurations that are accepted today into failing polls, so the options schema is
        // where that is caught instead (flags are enum true/false there).
        for (String other : List.of("yes", "1", "on", "y")) {
            assertThat(configuration("shallow", other).isShallow()).as(other).isFalse();
            assertThat(configuration("shallow", other, "url", "u", "sparse_paths", "src").validate())
                    .as(other).isEmpty();
        }
    }

    @Test
    void shouldFoldAYamlFoldedBlockIntoOnePatternWhichIsWhyTheDocsSayUsePipe() {
        // '>' joins lines with spaces. A space is legal inside a path, so this cannot be rejected;
        // it is documented instead.
        assertThat(configuration("sparse_paths", "services/billing libs/shared/*\n").sparsePaths())
                .containsExactly("services/billing libs/shared/*");
    }

    private static MaterialConfiguration configuration(String... keysAndValues) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            raw.put(keysAndValues[i], Map.of("value", keysAndValues[i + 1]));
        }
        return MaterialConfiguration.from(raw);
    }
}
