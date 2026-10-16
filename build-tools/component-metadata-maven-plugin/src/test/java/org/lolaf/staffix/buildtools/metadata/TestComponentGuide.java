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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestComponentGuide {

    private static final Path GUIDES = Path.of("src/test/resources/guides");

    @Test
    void readsAGuideAsWritten() throws IOException {
        assertThat(ComponentGuide.read(GUIDES.resolve("valid.guide.yaml")).toString()).isEqualTo(
                "{\"summary\":\"Keeps messages in a table.\","
                        + "\"proving\":[\"Await rows: a failing store never stops the session.\"],"
                        + "\"failures\":[{\"symptom\":\"The table stays empty.\",\"fix\":\"Await rows in the test.\"}]}");
    }

    @ParameterizedTest
    @CsvSource({
            "misspelled.guide.yaml, prooving",
            "failure-without-fix.guide.yaml, fix",
            "no-summary.guide.yaml, summary",
            "blank-entry.guide.yaml, production"})
    void aGuideBreakingTheSchemaFailsNamingTheFileAndTheField(String file, String field) {
        assertThatThrownBy(() -> ComponentGuide.read(GUIDES.resolve(file)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(GUIDES.resolve(file) + " is not a component guide: ")
                .hasMessageContaining(field);
    }
}
