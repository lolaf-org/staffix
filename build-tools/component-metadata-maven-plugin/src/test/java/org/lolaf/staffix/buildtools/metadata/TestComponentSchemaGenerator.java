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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lolaf.staffix.buildtools.metadata.fixture.generate.DelegatingPluginSettings;
import org.lolaf.staffix.buildtools.metadata.fixture.generate.StrictSettings;
import org.lolaf.staffix.buildtools.metadata.fixture.generate.TracingSettings;
import org.lolaf.staffix.buildtools.metadata.fixture.generate.WrapperSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code WrapperSettings} covers the property types, required and optional fields, defaults and the annotations;
 * {@code StrictSettings} a builder that refuses to build unset; {@code TracingSettings} a class that is not
 * {@code @NullMarked} and the plugin classes.
 */
class TestComponentSchemaGenerator {

    private final ComponentSchemaGenerator generator =
            new ComponentSchemaGenerator(new SourceIndex(List.of(Path.of("src/test/java"))));

    @ParameterizedTest
    @ValueSource(classes = {WrapperSettings.class, StrictSettings.class, TracingSettings.class})
    void generatesTheExpectedSchema(Class<?> type) throws IOException {
        ObjectMapper json = new ObjectMapper();

        JsonNode expected = json.readTree(Path.of("src/test/resources/expected", type.getSimpleName()
                + ".schema.json").toFile());

        assertThat(generator.generate(new Component(type, ComponentScanner.kind(type).orElseThrow()),
                "org.lolaf.staffix", "fixture-impl").toPrettyString())
                .as("compared as text: the order of a default's keys matters to a reproducible jar")
                .isEqualTo(expected.toPrettyString());
    }

    @Test
    void aWrappedComponentTypedWithTypeArgumentsKeepsItsKind() {
        ObjectNode schema = generator.generate(new Component(DelegatingPluginSettings.class, "session-plugin"),
                "org.lolaf.staffix", "fixture-impl");

        assertThat(schema.at("/properties/delegate/x-staffix-kind").asText()).isEqualTo("session-plugin");
        assertThat(schema.at("/x-staffix/instanceIdFrom").asText()).isEqualTo("delegate");
    }

    @Test
    void aStaffixClassWithoutSourceFailsRatherThanLosingItsDescriptions() {
        ComponentSchemaGenerator withoutSources = new ComponentSchemaGenerator(new SourceIndex(List.of()));

        assertThatThrownBy(() -> withoutSources.generate(new Component(TracingSettings.class, "session-plugin"),
                "org.lolaf.staffix", "fixture-impl"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("No source for " + TracingSettings.class.getName());
    }
}
