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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code keys.md} names, in order: known keys of every shape, a starter key, a package and Maven properties, then a
 * wrong prefix, a misspelt property and a property missing from a nested object.
 */
class TestDocumentedPropertyKeys {

    private static final Path RESOURCES = Path.of("src/test/resources");
    private static final Path DOCUMENT = Path.of("documentation/keys.md");
    private static final String UNKNOWN = " is no property of the starter or of a component's Spring Boot module";

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void reportsOnlyTheKeysNoModuleBinds() throws IOException {
        ObjectNode stores =
                (ObjectNode) json.readTree(RESOURCES.resolve("expected/FixtureStoreProps.spring.json").toFile());
        ObjectNode senders = (ObjectNode) json.readTree("{\"prefix\": \"staffix.fixture-senders\", \"properties\": {"
                + "\"properties\": {\"headers\": {\"type\": \"object\","
                + " \"additionalProperties\": {\"type\": \"string\"}}}}}");
        DocumentedPropertyKeys keys = DocumentedPropertyKeys.of(List.of(stores, senders),
                List.of("staffix.initiators", "staffix.engine.executor-bean"), List.of("staffix.version"));

        assertThat(keys.problems(RESOURCES, DOCUMENT)).containsExactly(
                DOCUMENT + ":9: staffix.fixture-store.instances.prod.flush-delay" + UNKNOWN,
                DOCUMENT + ":10: staffix.fixture-stores.instances.prod.flush-dealy" + UNKNOWN,
                DOCUMENT + ":11: staffix.fixture-stores.instances.prod.queue.writers" + UNKNOWN);
    }
}
