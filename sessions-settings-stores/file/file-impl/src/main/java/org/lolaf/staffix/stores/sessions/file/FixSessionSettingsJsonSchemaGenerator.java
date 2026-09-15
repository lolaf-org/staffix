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
package org.lolaf.staffix.stores.sessions.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import lombok.experimental.UtilityClass;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalTime;
import java.util.*;

/**
 * Generates a JSON Schema (Draft 2020-12) describing the on-disk session settings file format, derived from
 * {@link YamlFixSessionSettings} together with its Jackson and Jakarta Bean Validation annotations.
 * <p>
 * Because the schema is generated from the model rather than maintained by hand, it cannot drift from what the loader
 * actually accepts. It is intended to be dropped next to the {@code *.yaml} session files so editors (IntelliJ, or
 * VS Code with the Red Hat YAML extension) can offer autocomplete, inline documentation and validation when a file
 * declares {@code # yaml-language-server: $schema=<file>}.
 * <p>
 * This class runs at <em>build</em> time only: {@link #main(String[])} is invoked by the {@code exec-maven-plugin}
 * during {@code process-classes}, writing the schema into {@code target/classes} so it is packaged in this module's
 * jar. At runtime the schema is read back from that jar by {@link FixSessionSettingsJsonSchema#openPackagedSchema()}.
 * <p>
 * <strong>Nothing on the runtime path may reference this class.</strong> The {@code com.github.victools} dependencies
 * are declared {@code optional} and so are absent from a consuming application, and merely invoking a method here is
 * enough to make the JVM link the class and fail with {@link NoClassDefFoundError} — verifying {@link #generate()}
 * resolves {@code com.github.victools.jsonschema.generator.Module}, whether or not that method is ever called. That is
 * why the runtime-facing constant and accessor live in the dependency-free
 * {@link FixSessionSettingsJsonSchema} instead, and a test asserts this class stays out of its constant pool.
 */
@UtilityClass
public class FixSessionSettingsJsonSchemaGenerator {

    /**
     * Regex matching the ISO-8601 duration strings that {@link Duration#parse(CharSequence)} accepts (the wire format
     * Jackson reads/writes), e.g. {@code PT10S}, {@code PT1H30M}, {@code P2DT3H}, {@code PT0.0000015S}, {@code -PT5M}.
     * Deliberately tighter than the standard {@code "duration"} format, which also permits year/month/week components
     * ({@code P1Y}, {@code P3W}) that {@code java.time.Duration} rejects.
     */
    private static final String ISO8601_DURATION_PATTERN =
            "^[-+]?P(?:[-+]?\\d+D)?(?:T(?:[-+]?\\d+H)?(?:[-+]?\\d+M)?(?:[-+]?\\d+(?:[.,]\\d+)?S)?)?$";

    /**
     * Regex matching the {@code ISO_LOCAL_TIME} strings that {@link LocalTime#parse(CharSequence)} accepts (the wire
     * format Jackson reads/writes), e.g. {@code 09:30}, {@code 10:10:11}, {@code 10:10:10.099999999}. Note this is an
     * offset-less local time, which is why the standard {@code "time"} format (RFC 3339, requiring a zone offset) is
     * not used here.
     */
    private static final String LOCAL_TIME_PATTERN = "^([01]\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d(?:\\.\\d{1,9})?)?$";

    /**
     * @return the JSON Schema for {@link YamlFixSessionSettings} as a pretty-printed JSON string.
     * @throws NoClassDefFoundError if the {@code com.github.victools} artifacts, declared {@code optional} by this
     * module, are not on the classpath. Prefer {@link FixSessionSettingsJsonSchema#openPackagedSchema()}, which reads
     * the copy generated at build time and needs no extra dependency.
     */
    private static final Set<String> SCALAR_TYPES =
            new HashSet<>(Arrays.asList("string", "integer", "number", "boolean"));

    private static final String PLACEHOLDER_PATTERN = ".*\\$\\{[^}]+}.*";

    public static String generate() {
        JacksonModule jacksonModule = new JacksonModule(
                JacksonOption.RESPECT_JSONPROPERTY_ORDER,
                JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE);
        JakartaValidationModule validationModule = new JakartaValidationModule(
                JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS);

        SchemaGeneratorConfigBuilder configBuilder =
                new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                        .with(jacksonModule)
                        .with(validationModule);
        // java.time types are ISO strings on the wire; describe them as such and constrain them with a pattern so
        // editors flag malformed values (e.g. "10s", "25:00") instead of only failing later at load time.
        configBuilder.forTypesInGeneral().withCustomDefinitionProvider(FixSessionSettingsJsonSchemaGenerator::temporalDefinition);

        SchemaGeneratorConfig config = configBuilder.build();
        ObjectNode schema = (ObjectNode) new SchemaGenerator(config).generateSchema(YamlFixSessionSettings.class);
        widenScalarsForPlaceholders(schema);
        return schema.toPrettyString();
    }

    /**
     * Lets any non-enum scalar also be written as a {@code ${...}} placeholder, which is a string whatever
     * the field's declared type.
     *
     * <p>Applied to the generated schema rather than through a custom definition provider, which would
     * have to reproduce each declared type to wrap it. Enums keep their exact declaration so an editor
     * still completes them and says which constants are allowed on a typo - and an enum is not the kind of
     * value that varies by environment.
     */
    private static void widenScalarsForPlaceholders(ObjectNode schema) {
        for (String container : new String[]{"properties", "$defs", "definitions"}) {
            JsonNode nested = schema.get(container);
            if (nested instanceof ObjectNode) {
                ObjectNode object = (ObjectNode) nested;
                List<String> names = new ArrayList<>();
                object.fieldNames().forEachRemaining(names::add);
                names.forEach(name -> widenMember(object, name));
            }
        }
        JsonNode items = schema.get("items");
        if (items instanceof ObjectNode) {
            widenMember(schema, "items");
        }
    }

    private static void widenMember(ObjectNode parent, String name) {
        JsonNode member = parent.get(name);
        if (!(member instanceof ObjectNode)) {
            return;
        }
        ObjectNode node = (ObjectNode) member;
        if (isWidenableScalar(node)) {
            ObjectNode widened = node.objectNode();
            widened.putArray("anyOf").add(node.deepCopy()).add(placeholderSchema(node));
            parent.set(name, widened);
        } else {
            widenScalarsForPlaceholders(node);
        }
    }

    private static boolean isWidenableScalar(ObjectNode node) {
        JsonNode type = node.get("type");
        return type != null && type.isTextual() && !node.has("enum")
                && SCALAR_TYPES.contains(type.textValue());
    }

    private static ObjectNode placeholderSchema(ObjectNode sibling) {
        ObjectNode placeholder = sibling.objectNode();
        placeholder.put("type", "string");
        placeholder.put("pattern", PLACEHOLDER_PATTERN);
        placeholder.put("description", "A ${...} placeholder, resolved when the file is loaded");
        return placeholder;
    }

    private static CustomDefinition temporalDefinition(com.fasterxml.classmate.ResolvedType type, SchemaGenerationContext context) {
        if (type.isInstanceOf(Duration.class)) {
            return stringDefinition(context, "duration", ISO8601_DURATION_PATTERN,
                    "ISO-8601 duration, e.g. PT10S, PT1H30M, P2DT3H, PT0.0000015S");
        }
        if (type.isInstanceOf(LocalTime.class)) {
            // No "format": the standard "time" format is RFC 3339 (offset-required), which a local time is not.
            return stringDefinition(context, null, LOCAL_TIME_PATTERN,
                    "Local time of day (ISO-8601, no zone), e.g. 09:30, 10:10:11, 10:10:10.099999999");
        }
        return null;
    }

    private static CustomDefinition stringDefinition(SchemaGenerationContext context, String format, String pattern, String description) {
        ObjectNode node = context.getGeneratorConfig().createObjectNode();
        node.put("type", "string");
        if (format != null) {
            node.put("format", format);
        }
        node.put("pattern", pattern);
        node.put("description", description);
        return new CustomDefinition(node, CustomDefinition.DefinitionType.INLINE, CustomDefinition.AttributeInclusion.NO);
    }

    /**
     * Writes the generated schema to the given file, creating or overwriting it.
     */
    public static void writeTo(File file) throws IOException {
        Files.write(file.toPath(), generate().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Build-time entry point, invoked by {@code exec-maven-plugin} during {@code process-classes}.
     * <p>
     * The destination is passed in rather than derived, because the pom needs that same path a second time to attach
     * the schema as a standalone build artifact. To stop the two from drifting apart, the path is validated here: it
     * must be named {@link FixSessionSettingsJsonSchema#SCHEMA_FILE_NAME} and sit in this class's package, which is where
     * {@link FixSessionSettingsJsonSchema#openPackagedSchema()} and therefore {@link FileFixSessionsSettingsStore} look for it. Renaming the
     * constant without updating the pom (or the reverse) fails the build with the message below, instead of quietly
     * producing a jar whose schema cannot be found.
     *
     * @param args a single argument: the file to write, i.e. the pom's {@code fix-session-settings-schema-file}
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected a single argument: the schema file to write");
        }
        File file = new File(args[0]);
        String expectedPackagePath =
                FixSessionSettingsJsonSchemaGenerator.class.getPackage().getName().replace('.', File.separatorChar);
        if (!FixSessionSettingsJsonSchema.SCHEMA_FILE_NAME.equals(file.getName())
                || file.getParentFile() == null
                || !file.getParentFile().getPath().endsWith(expectedPackagePath)) {
            throw new IllegalArgumentException("Schema output path " + file + " is out of sync with the code: expected a"
                    + " file named " + FixSessionSettingsJsonSchema.SCHEMA_FILE_NAME + " under " + expectedPackagePath
                    + ". Update the pom's fix-session-settings-schema-file property or FixSessionSettingsJsonSchema.SCHEMA_FILE_NAME so they agree.");
        }
        File directory = file.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create schema output directory " + directory);
        }
        writeTo(file);
        System.out.println("Generated FIX session settings JSON schema: " + file);
    }
}
