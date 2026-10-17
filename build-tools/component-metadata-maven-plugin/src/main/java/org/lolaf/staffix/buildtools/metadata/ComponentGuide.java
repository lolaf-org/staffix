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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Reads a component's {@code <settings class>.guide.yaml}, checked against {@value #SCHEMA_RESOURCE}, which is the
 * format's only definition.
 */
@UtilityClass
public class ComponentGuide {

    public static final String SCHEMA_RESOURCE = "/component-guide.v1.schema.json";
    public static final String SUFFIX = ".guide.yaml";

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final Schema SCHEMA = loadSchema();

    /**
     * @throws IllegalArgumentException naming the file and each way it breaks the format
     */
    public static ObjectNode read(Path file) throws IOException {
        String text = Files.readString(file);
        JsonNode guide = YAML.readTree(text);
        List<Error> errors = guide == null ? List.of() : SCHEMA.validate(text, InputFormat.YAML);
        if (!(guide instanceof ObjectNode) || !errors.isEmpty()) {
            throw new IllegalArgumentException(file + " is not a component guide: " + (guide instanceof ObjectNode
                    ? errors.stream().map(ComponentGuide::describe).collect(Collectors.joining("; "))
                    : "expected a mapping"));
        }
        return (ObjectNode) guide;
    }

    /**
     * The guide written beside a class, removed from the classes directory once read so the jar leaves it out.
     */
    public static Optional<ObjectNode> readBeside(Class<?> type, Path classesDirectory) throws IOException {
        Path guide = besideClass(type, classesDirectory);
        if (!Files.isRegularFile(guide)) {
            return Optional.empty();
        }
        ObjectNode content = read(guide);
        Files.delete(guide);
        return Optional.of(content);
    }

    static Path besideClass(Class<?> type, Path classesDirectory) {
        return classesDirectory.resolve(type.getName().replace('.', '/') + SUFFIX);
    }

    private static String describe(Error error) {
        String location = error.getInstanceLocation().toString();
        return (location.isEmpty() ? "top level" : location) + ": " + error.getMessage();
    }

    private static Schema loadSchema() {
        try (InputStream schema = ComponentGuide.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (schema == null) {
                throw new IllegalStateException(SCHEMA_RESOURCE + " is missing from the plugin");
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(new String(schema.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
