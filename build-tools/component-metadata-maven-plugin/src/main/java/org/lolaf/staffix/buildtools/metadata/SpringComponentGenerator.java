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

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * How a component is configured in Spring Boot properties: the prefix, a JSON Schema of the properties as written
 * under it, and the component's own schema, so a skill working on a Spring Boot application reads one file.
 */
@RequiredArgsConstructor
public class SpringComponentGenerator {

    public static final String CONFIGURATION_PROPERTIES =
            "org.springframework.boot.context.properties.ConfigurationProperties";
    public static final String CONFIGURES_COMPONENT = "org.lolaf.staffix.api.metadata.ConfiguresComponent";

    private static final String INSTANCES = "instances";

    private final SourceIndex sources;

    /**
     * @return the settings class configured by a properties class, or null when it configures no component
     */
    public static Class<?> configuredComponent(Class<?> propertiesClass) {
        return ComponentScanner.annotation(propertiesClass, CONFIGURATION_PROPERTIES).isEmpty() ? null
                : ComponentScanner.annotation(propertiesClass, CONFIGURES_COMPONENT)
                .map(configures -> (Class<?>) ComponentScanner.value(configures, "value"))
                .orElse(null);
    }

    /**
     * @param componentSchema the configured component's schema, from its jar
     */
    public ObjectNode generate(Class<?> propertiesClass, ObjectNode componentSchema, String groupId, String artifactId) {
        Class<?> settings = configuredComponent(propertiesClass);
        String prefix = prefix(ComponentScanner.annotation(propertiesClass, CONFIGURATION_PROPERTIES).orElseThrow());
        ObjectNode file = JsonTypes.NODES.objectNode();
        file.put("$id", "staffix:spring-component:" + settings.getName() + ":v" + ComponentSchemaGenerator.FORMAT_VERSION);
        file.put("title", settings.getSimpleName());
        file.put("artifact", groupId + ":" + artifactId);
        file.put("prefix", prefix);
        if (JsonTypes.fields(propertiesClass).stream()
                .anyMatch(field -> field.getName().equals(INSTANCES) && Map.class.isAssignableFrom(field.getType()))) {
            file.put("instances", prefix + "." + INSTANCES + ".<id>");
        }
        ObjectNode properties = file.putObject("properties");
        JsonTypes.putIfPresent(properties, "description", sources.describe(propertiesClass));
        ObjectNode definitions = JsonTypes.NODES.objectNode();
        describeObject(properties, propertiesClass, definitions);
        if (!definitions.isEmpty()) {
            properties.set("$defs", definitions);
        }
        file.set("component", componentSchema);
        return file;
    }

    private void describeObject(ObjectNode schema, Class<?> type, ObjectNode definitions) {
        schema.put("type", "object");
        Object unset = newInstance(type);
        ObjectNode properties = JsonTypes.NODES.objectNode();
        for (Field field : JsonTypes.fields(type)) {
            ObjectNode property = JsonTypes.schema(field.getGenericType(), raw -> objectSchema(raw, definitions));
            JsonTypes.putIfPresent(property, "description", sources.describe(field.getDeclaringClass(), field.getName()));
            Object value = unset == null ? null : JsonTypes.read(field, unset);
            if (value != null) {
                JsonTypes.putDefault(property, value, isNestedProperties(field.getType()));
            }
            properties.set(kebabCase(field.getName()), property);
        }
        schema.set("properties", properties);
    }

    private ObjectNode objectSchema(Class<?> type, ObjectNode definitions) {
        if (!isNestedProperties(type)) {
            return JsonTypes.javaType(type);
        }
        String name = type.getSimpleName();
        if (!definitions.has(name)) {
            ObjectNode definition = definitions.putObject(name);
            JsonTypes.putIfPresent(definition, "description", sources.describe(type));
            describeObject(definition, type, definitions);
        }
        return JsonTypes.NODES.objectNode().put("$ref", "#/$defs/" + name);
    }

    private static String prefix(Annotation configurationProperties) {
        String prefix = (String) ComponentScanner.value(configurationProperties, "prefix");
        return prefix.isEmpty() ? (String) ComponentScanner.value(configurationProperties, "value") : prefix;
    }

    /**
     * Spring binds {@code fooBar} from {@code foo-bar}, the form the properties are written in.
     */
    static String kebabCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }

    private static boolean isNestedProperties(Class<?> type) {
        return type.getName().startsWith("org.lolaf.staffix.") && !type.isInterface() && !type.isEnum()
                && !Modifier.isAbstract(type.getModifiers()) && publicNoArgConstructor(type) != null;
    }

    private static Object newInstance(Class<?> type) {
        Constructor<?> constructor = publicNoArgConstructor(type);
        try {
            return constructor == null ? null : constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to create " + type.getName() + " for its defaults", e);
        }
    }

    private static Constructor<?> publicNoArgConstructor(Class<?> type) {
        try {
            return type.getConstructor();
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
