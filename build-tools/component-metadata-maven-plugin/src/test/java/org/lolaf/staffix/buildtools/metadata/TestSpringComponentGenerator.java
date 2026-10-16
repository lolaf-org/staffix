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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lolaf.staffix.buildtools.metadata.fixture.generate.WrapperSettings;
import org.lolaf.staffix.buildtools.metadata.fixture.spring.FixtureStoreProps;
import org.lolaf.staffix.buildtools.metadata.fixture.spring.UnlinkedProps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code FixtureStoreProps} covers a top-level property beside the instances, an instance's property types and
 * defaults, and a nested properties class.
 */
class TestSpringComponentGenerator {

    private final ObjectMapper json = new ObjectMapper();
    private final SpringComponentGenerator generator =
            new SpringComponentGenerator(new SourceIndex(List.of(Path.of("src/test/java"))));

    @Test
    void describesThePropertiesAsWrittenAndEmbedsTheComponentSchema() throws IOException {
        ObjectNode component = json.createObjectNode().put("title", "WrapperSettings");

        assertThat(generator.generate(FixtureStoreProps.class, component, "org.lolaf.staffix", "fixture-spring-boot")
                .toPrettyString())
                .isEqualTo(json.readTree(Path.of("src/test/resources/expected/FixtureStoreProps.spring.json").toFile())
                        .toPrettyString());
    }

    @Test
    void onlyAnAnnotatedPropertiesClassConfiguresAComponent() {
        assertThat(SpringComponentGenerator.configuredComponent(FixtureStoreProps.class)).isEqualTo(WrapperSettings.class);
        assertThat(SpringComponentGenerator.configuredComponent(UnlinkedProps.class)).isNull();
        assertThat(SpringComponentGenerator.configuredComponent(WrapperSettings.class)).isNull();
    }

    @ParameterizedTest
    @CsvSource({"wraps, wraps", "dataSourceBean, data-source-bean", "w3cTraceFieldCode, w3c-trace-field-code",
            "useMDC, use-mdc"})
    void namesAreWrittenAsSpringBindsThem(String field, String property) {
        assertThat(SpringComponentGenerator.kebabCase(field)).isEqualTo(property);
    }
}
