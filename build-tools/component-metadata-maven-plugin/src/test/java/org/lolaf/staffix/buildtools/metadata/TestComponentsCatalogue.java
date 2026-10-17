/*
 * Copyright © 2024-2026 Lolaf.org
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
package org.lolaf.staffix.buildtools.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The expected component and Spring files of the generator tests stand for the jars' metadata.
 */
class TestComponentsCatalogue {

    private static final Path EXPECTED = Path.of("src/test/resources/expected");
    private static final String FIXTURES = "org.lolaf.staffix.buildtools.metadata.fixture.generate.";

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void indexesEachComponentWithItsSpringFileAndEachKindOnce() throws IOException {
        assertThat(ComponentsCatalogue.index("1.2.3", metadata()).toPrettyString())
                .isEqualTo(json.readTree(EXPECTED.resolve("catalogue.v1.json").toFile()).toPrettyString());
    }

    @Test
    void aComponentWithoutGuideSummaryIsRefused() throws IOException {
        Map<String, ObjectNode> files = metadata();
        ((ObjectNode) files.get(componentFile("StrictSettings")).get("x-staffix")).remove("guide");

        assertThatThrownBy(() -> ComponentsCatalogue.index("1.2.3", files))
                .hasMessage(FIXTURES + "StrictSettings has no guide summary");
    }

    @Test
    void aSpringFileWithoutItsComponentIsRefused() throws IOException {
        Map<String, ObjectNode> files = metadata();
        files.remove(componentFile("WrapperSettings"));

        assertThatThrownBy(() -> ComponentsCatalogue.index("1.2.3", files))
                .hasMessage("The Spring metadata of " + FIXTURES + "WrapperSettings has no component schema beside it");
    }

    @Test
    void componentsOfAKindSelectedDifferentlyAreRefused() throws IOException {
        Map<String, ObjectNode> files = metadata();
        ((ObjectNode) files.get(componentFile("TracingSettings")).get("x-staffix")).putObject("selectedBy")
                .put("sessionSetting", "other");

        assertThatThrownBy(() -> ComponentsCatalogue.index("1.2.3", files))
                .hasMessageStartingWith(FIXTURES + "TracingSettings is selected unlike the other session-plugin");
    }

    @Test
    void aFileOutsideTheMetadataLayoutIsRefused() throws IOException {
        Map<String, ObjectNode> files = metadata();
        files.put("notes.json", json.createObjectNode());

        assertThatThrownBy(() -> ComponentsCatalogue.index("1.2.3", files))
                .hasMessage("META-INF/staffix/notes.json is not component metadata");
    }

    @Test
    void reportsAModuleLeftOutAndADependencyWithoutMetadata() {
        assertThat(ComponentsCatalogue.coverageProblems(Set.of("store-impl", "store-spring-boot"),
                Set.of("store-impl", "staffix-api"), Map.of("store-impl", 1, "staffix-api", 0)))
                .containsExactly(
                        "store-spring-boot describes components but is not a dependency of the catalogue: add it to"
                                + " its pom",
                        "staffix-api is a dependency of the catalogue but holds no component metadata");
    }

    @Test
    void aCatalogueDependingOnEveryDescribingModuleIsComplete() {
        assertThat(ComponentsCatalogue.coverageProblems(Set.of("store-impl"), Set.of("store-impl"),
                Map.of("store-impl", 2, "staffix-api", 0))).isEmpty();
    }

    private Map<String, ObjectNode> metadata() throws IOException {
        Map<String, ObjectNode> files = new TreeMap<>();
        for (String settings : new String[]{"WrapperSettings", "StrictSettings", "TracingSettings"}) {
            ObjectNode schema = (ObjectNode) json.readTree(EXPECTED.resolve(settings + ".schema.json").toFile());
            ((ObjectNode) schema.get("x-staffix")).putObject("guide").put("summary", "Summary of " + settings + ".");
            files.put(componentFile(settings), schema);
        }
        files.put("spring/" + FIXTURES + "WrapperSettings.v1.json",
                (ObjectNode) json.readTree(EXPECTED.resolve("FixtureStoreProps.spring.json").toFile()));
        return files;
    }

    private static String componentFile(String settings) {
        return "components/" + FIXTURES + settings + ".v1.schema.json";
    }
}
