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
package org.lolaf.staffix.buildtools.javadoc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestSettingsJavadoc {

    private final SettingsJavadoc javadoc = new SettingsJavadoc();

    @BeforeEach
    void readFixture() throws IOException {
        javadoc.read(Path.of("src/test/resources/fixture/FixtureSettings.java"));
    }

    @Test
    void classJavadocIsPlainTextWithoutBlockTags() {
        assertThat(javadoc.describe("org.example.FixtureSettings")).isEqualTo("Where the fixture writes.\n\nSecond paragraph.");
    }

    @Test
    void fieldJavadocResolvesValueAndLinkTags() {
        assertThat(javadoc.describe("org.example.FixtureSettings", "directory"))
                .isEqualTo("Defaults to /var/data, see a duration.");
    }

    @Test
    void nestedTypesAreDotted() {
        assertThat(javadoc.describe("org.example.FixtureSettings.Nested")).isEqualTo("The nested settings.");
        assertThat(javadoc.describe("org.example.FixtureSettings.Nested", "wait"))
                .isEqualTo("How long to wait, as Duration.ofSeconds().");
        assertThat(javadoc.describe("org.example.FixtureSettings.Mode", "weight")).isEqualTo("A field of an enum.");
    }

    @Test
    void undocumentedIsNull() {
        assertThat(javadoc.describe("org.example.FixtureSettings", "undocumented")).isNull();
        assertThat(javadoc.describe("org.example.Unknown")).isNull();
    }
}
