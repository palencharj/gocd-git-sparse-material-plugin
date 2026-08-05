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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScmConfigTest {

    private static Map<String, Object> config(String... keysAndValues) {
        Map<String, Object> config = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            Map<String, Object> holder = new LinkedHashMap<>();
            holder.put("value", keysAndValues[i + 1]);
            config.put(keysAndValues[i], holder);
        }
        return config;
    }

    @Test
    void readsTheNestedValueShapeGoCdSends() {
        ScmConfig parsed = ScmConfig.from(config(
                ScmConfig.URL, "https://example.com/r.git",
                ScmConfig.BRANCH, "main"));

        assertThat(parsed.url()).isEqualTo("https://example.com/r.git");
        assertThat(parsed.branch()).isEqualTo("main");
    }

    @Test
    void defaultsTheBranchWhenAbsentOrBlank() {
        assertThat(ScmConfig.from(config()).branch()).isEqualTo("master");
        assertThat(ScmConfig.from(config(ScmConfig.BRANCH, "   ")).branch()).isEqualTo("master");
    }

    @Test
    void survivesAnEmptyOrMissingConfiguration() {
        ScmConfig parsed = ScmConfig.from(new LinkedHashMap<>());

        assertThat(parsed.url()).isNull();
        assertThat(parsed.sparsePaths()).isEmpty();
        assertThat(parsed.shallow()).isFalse();
    }

    @Test
    void splitsPathsOnLinesDroppingBlanksAndWhitespace() {
        ScmConfig parsed = ScmConfig.from(config(
                ScmConfig.SPARSE_PATHS, "\n  services/billing  \n\n\tlibs/shared/*\n"));

        assertThat(parsed.sparsePaths()).containsExactly("services/billing", "libs/shared/*");
    }

    @Test
    void treatsPathsWithSpacesAsSinglePatterns() {
        // Line-delimited precisely so that this works; splitting on spaces or commas would break it.
        ScmConfig parsed = ScmConfig.from(config(ScmConfig.SPARSE_PATHS, "dir with space/f.txt"));

        assertThat(parsed.sparsePaths()).containsExactly("dir with space/f.txt");
    }

    @Test
    void pathspecsDropNegationsBecauseGitLogCannotUseThem() {
        ScmConfig parsed = ScmConfig.from(config(ScmConfig.SPARSE_PATHS, "src\n!src/generated\ndocs"));

        assertThat(parsed.sparsePaths()).containsExactly("src", "!src/generated", "docs");
        assertThat(parsed.pathspecs()).containsExactly("src", "docs");
    }

    @Test
    void pathspecsAreRelativeSoLeadingSlashIsStripped() {
        ScmConfig parsed = ScmConfig.from(config(ScmConfig.SPARSE_PATHS, "/build.gradle"));

        assertThat(parsed.pathspecs()).containsExactly("build.gradle");
    }

    @Test
    void pathspecsAreEmptyWhenEveryPatternExcludes() {
        // The plugin uses this to reject a configuration that would check out nothing at all.
        ScmConfig parsed = ScmConfig.from(config(ScmConfig.SPARSE_PATHS, "!a\n!b"));

        assertThat(parsed.sparsePaths()).hasSize(2);
        assertThat(parsed.pathspecs()).isEmpty();
    }

    @Test
    void booleanFlagsParseFromStrings() {
        ScmConfig on = ScmConfig.from(config(ScmConfig.SHALLOW, "true", ScmConfig.FILTER_BY_PATHS, "true"));
        ScmConfig off = ScmConfig.from(config(ScmConfig.SHALLOW, "false", ScmConfig.FILTER_BY_PATHS, ""));

        assertThat(on.shallow()).isTrue();
        assertThat(on.filterByPaths()).isTrue();
        assertThat(off.shallow()).isFalse();
        assertThat(off.filterByPaths()).isFalse();
    }
}
