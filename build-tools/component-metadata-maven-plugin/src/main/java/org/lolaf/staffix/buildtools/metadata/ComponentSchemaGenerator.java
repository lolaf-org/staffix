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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The JSON Schema of a component's settings as its builder sees them, with the facts the code cannot show in an
 * {@code x-staffix} block.
 */
@RequiredArgsConstructor
public class ComponentSchemaGenerator {

    public static final int FORMAT_VERSION = 1;

    private static final String METADATA = "org.lolaf.staffix.api.metadata.";
    private static final String NULLABLE = "org.jspecify.annotations.Nullable";
    private static final String NULL_MARKED = "org.jspecify.annotations.NullMarked";
    private static final String SESSION_PLUGIN = "session-plugin";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Sets and maps from {@code Set.of} and {@code Map.of} iterate in an order that changes with each JVM, which would
     * change the jar from one build to the next.
     */
    private static final Comparator<Object> STABLE_ORDER = (left, right) -> left instanceof Enum && right instanceof Enum
            ? Integer.compare(((Enum<?>) left).ordinal(), ((Enum<?>) right).ordinal())
            : String.valueOf(left).compareTo(String.valueOf(right));

    private final SourceIndex sources;

    /**
     * @param groupId prefixes a required artifact named without its group
     */
    public ObjectNode generate(Component component, String groupId, String artifactId) {
        Class<?> type = component.getSettingsClass();
        Object unset = unsetInstance(type);
        ObjectNode schema = NODES.objectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("$id", "staffix:component:" + type.getName() + ":v" + FORMAT_VERSION);
        schema.put("title", type.getSimpleName());
        putIfPresent(schema, "description", sources.describe(type));
        ObjectNode definitions = NODES.objectNode();
        describeObject(schema, type, unset, definitions);
        if (!definitions.isEmpty()) {
            schema.set("$defs", definitions);
        }
        schema.set("x-staffix", staffix(component, unset, groupId, artifactId));
        return schema;
    }

    private void describeObject(ObjectNode schema, Class<?> type, Object unset, ObjectNode definitions) {
        schema.put("type", "object");
        ArrayNode required = NODES.arrayNode();
        ObjectNode properties = NODES.objectNode();
        Set<String> builderMethods = builderMethodNames(type);
        for (Field field : fields(type)) {
            if (!builderMethods.contains(field.getName())) {
                continue;
            }
            ObjectNode property = propertySchema(field.getGenericType(), definitions);
            putIfPresent(property, "description", sources.describe(field.getDeclaringClass(), field.getName()));
            Object value = defaultValue(field, unset);
            if (value == null) {
                if (isRequired(field)) {
                    required.add(field.getName());
                }
            } else {
                putDefault(property, value, field.getType());
            }
            properties.set(field.getName(), property);
        }
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        schema.set("properties", properties);
    }

    private ObjectNode propertySchema(Type type, ObjectNode definitions) {
        if (type instanceof ParameterizedType) {
            return parameterizedSchema((ParameterizedType) type, definitions);
        }
        if (!(type instanceof Class)) {
            return javaType(type);
        }
        Class<?> raw = (Class<?>) type;
        ObjectNode schema = NODES.objectNode();
        if (raw == String.class) {
            schema.put("type", "string");
        } else if (raw == char.class || raw == Character.class) {
            schema.put("type", "string").put("minLength", 1).put("maxLength", 1);
        } else if (raw == boolean.class || raw == Boolean.class) {
            schema.put("type", "boolean");
        } else if (isInteger(raw)) {
            schema.put("type", "integer");
        } else if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class) {
            schema.put("type", "number");
        } else if (raw.isEnum()) {
            schema.put("type", "string");
            ArrayNode constants = schema.putArray("enum");
            Stream.of(raw.getEnumConstants()).map(constant -> ((Enum<?>) constant).name()).forEach(constants::add);
        } else if (raw == Duration.class) {
            schema.put("type", "string").put("format", "duration");
        } else if (raw == URI.class || raw == URL.class) {
            schema.put("type", "string").put("format", "uri");
        } else if (raw == File.class || raw == Path.class) {
            schema.put("type", "string");
        } else {
            Optional<String> kind = ComponentScanner.kind(raw);
            if (kind.isPresent()) {
                return javaType(raw).put("x-staffix-kind", kind.get());
            }
            if (!isNestedSettings(raw)) {
                return javaType(raw);
            }
            String name = raw.getSimpleName();
            if (!definitions.has(name)) {
                ObjectNode definition = definitions.putObject(name);
                putIfPresent(definition, "description", sources.describe(raw));
                describeObject(definition, raw, unsetInstance(raw), definitions);
            }
            schema.put("$ref", "#/$defs/" + name);
        }
        return schema;
    }

    private ObjectNode parameterizedSchema(ParameterizedType type, ObjectNode definitions) {
        Class<?> raw = (Class<?>) type.getRawType();
        Type[] arguments = type.getActualTypeArguments();
        if (raw == List.class || raw == Set.class || raw == Collection.class) {
            ObjectNode items = propertySchema(arguments[0], definitions);
            if (isData(items)) {
                ObjectNode schema = NODES.objectNode().put("type", "array");
                schema.set("items", items);
                return raw == Set.class ? schema.put("uniqueItems", true) : schema;
            }
        } else if (raw == Map.class && isMapKey(arguments[0])) {
            ObjectNode values = propertySchema(arguments[1], definitions);
            if (isData(values)) {
                ObjectNode schema = NODES.objectNode().put("type", "object");
                if (((Class<?>) arguments[0]).isEnum()) {
                    schema.set("propertyNames", propertySchema(arguments[0], definitions));
                }
                schema.set("additionalProperties", values);
                return schema;
            }
        }
        return javaType(type);
    }

    private ObjectNode staffix(Component component, Object unset, String groupId, String artifactId) {
        Class<?> type = component.getSettingsClass();
        ObjectNode staffix = NODES.objectNode();
        staffix.put("kind", component.getKind());
        staffix.put("artifact", groupId + ":" + artifactId);
        fields(type).stream().filter(field -> annotation(field.getAnnotations(), METADATA + "Wrapped").isPresent())
                .findFirst().ifPresent(field -> staffix.put("instanceIdFrom", field.getName()));
        ComponentScanner.annotation(type, METADATA + "RequiresJvmFlags").ifPresent(flags -> {
            ArrayNode sets = staffix.putArray("jvmFlags");
            Stream.of((Object[]) ComponentScanner.value(flags, "value")).map(set -> ((Enum<?>) set).name())
                    .forEach(sets::add);
        });
        ComponentScanner.annotation(type, METADATA + "RequiresArtifact").ifPresent(requires -> {
            ArrayNode oneOf = staffix.putArray("requiresOneOf");
            Stream.of((String[]) ComponentScanner.value(requires, "oneOf"))
                    .map(artifact -> artifact.contains(":") ? artifact : groupId + ":" + artifact)
                    .forEach(oneOf::add);
        });
        if (SESSION_PLUGIN.equals(component.getKind()) && unset != null) {
            pluginClasses(unset).ifPresent(classes -> {
                ArrayNode plugins = staffix.putArray("pluginClasses");
                classes.forEach(plugins::add);
            });
        }
        return staffix;
    }

    private static Optional<Set<String>> pluginClasses(Object settings) {
        try {
            Collection<?> types = (Collection<?>) settings.getClass().getMethod("getPluginTypes").invoke(settings);
            return Optional.of(types.stream().map(plugin -> ((Class<?>) plugin).getName())
                    .collect(Collectors.toCollection(TreeSet::new)));
        } catch (InvocationTargetException e) {
            return Optional.empty();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(settings.getClass().getName() + " has no getPluginTypes()", e);
        }
    }

    /**
     * Null when the builder refuses to build with nothing set, as Lombok's {@code @NonNull} does.
     */
    private static Object unsetInstance(Class<?> type) {
        try {
            Method builder = type.getMethod("builder");
            Method build = builder.getReturnType().getMethod("build");
            build.setAccessible(true);
            return build.invoke(builder.invoke(null));
        } catch (InvocationTargetException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getName() + " has no builder", e);
        }
    }

    /**
     * The unset instance's value, else what Lombok's {@code $default$} method for a {@code @Builder.Default} gives.
     */
    private static Object defaultValue(Field field, Object unset) {
        try {
            if (unset != null) {
                field.setAccessible(true);
                return field.get(unset);
            }
            Method lombokDefault = field.getDeclaringClass().getDeclaredMethod("$default$" + field.getName());
            lombokDefault.setAccessible(true);
            return lombokDefault.invoke(null);
        } catch (NoSuchMethodException e) {
            return primitiveZero(field.getType());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to read the default of " + field, e);
        }
    }

    private static Object primitiveZero(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return null;
        }
        return type.isPrimitive() ? 0 : null;
    }

    private static void putDefault(ObjectNode property, Object value, Class<?> declaredType) {
        JsonNode json = toJson(value);
        if (json != null) {
            if (!json.isContainerNode() || !json.isEmpty()) {
                property.set("default", json);
            }
        } else if (!isNestedSettings(declaredType)) {
            property.put("x-staffix-codeDefault", true);
        }
    }

    private static JsonNode toJson(Object value) {
        if (value instanceof String || value instanceof Character || value instanceof Duration
                || value instanceof URI || value instanceof URL || value instanceof File || value instanceof Path) {
            return NODES.textNode(value.toString());
        }
        if (value instanceof Boolean) {
            return NODES.booleanNode((Boolean) value);
        }
        if (value instanceof Number) {
            return MAPPER.valueToTree(value);
        }
        if (value instanceof Enum) {
            return NODES.textNode(((Enum<?>) value).name());
        }
        if (value instanceof Collection) {
            ArrayNode array = NODES.arrayNode();
            List<?> items = value instanceof List ? (List<?>) value
                    : ((Collection<?>) value).stream().sorted(STABLE_ORDER).collect(Collectors.toList());
            for (Object item : items) {
                JsonNode json = toJson(item);
                if (json == null) {
                    return null;
                }
                array.add(json);
            }
            return array;
        }
        if (value instanceof Map) {
            ObjectNode object = NODES.objectNode();
            List<Map.Entry<?, ?>> entries = new ArrayList<>(((Map<?, ?>) value).entrySet());
            entries.sort(Comparator.comparing(Map.Entry::getKey, STABLE_ORDER));
            for (Map.Entry<?, ?> entry : entries) {
                JsonNode json = toJson(entry.getValue());
                if (json == null) {
                    return null;
                }
                Object key = entry.getKey();
                object.set(key instanceof Enum ? ((Enum<?>) key).name() : String.valueOf(key), json);
            }
            return object;
        }
        return null;
    }

    /**
     * A field is required when its class is {@code @NullMarked}, it is not {@code @Nullable}, and nothing sets it.
     */
    private static boolean isRequired(Field field) {
        return !field.getType().isPrimitive() && isNullMarked(field.getDeclaringClass())
                && annotation(field.getAnnotatedType().getAnnotations(), NULLABLE).isEmpty()
                && annotation(field.getAnnotations(), NULLABLE).isEmpty();
    }

    private static boolean isNullMarked(Class<?> type) {
        for (Class<?> scope = type; scope != null; scope = scope.getEnclosingClass()) {
            if (ComponentScanner.annotation(scope, NULL_MARKED).isPresent()) {
                return true;
            }
        }
        return type.getPackage() != null && annotation(type.getPackage().getAnnotations(), NULL_MARKED).isPresent();
    }

    private static Optional<Annotation> annotation(Annotation[] annotations, String name) {
        return Stream.of(annotations).filter(annotation -> annotation.annotationType().getName().equals(name))
                .findFirst();
    }

    /**
     * Superclass fields first, as the builder lists them.
     */
    private static List<Field> fields(Class<?> type) {
        Deque<Class<?>> hierarchy = new ArrayDeque<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            hierarchy.push(current);
        }
        List<Field> fields = new ArrayList<>();
        hierarchy.forEach(declaring -> Stream.of(declaring.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
                .forEach(fields::add));
        return fields;
    }

    private static Set<String> builderMethodNames(Class<?> type) {
        try {
            return Stream.of(type.getMethod("builder").getReturnType().getMethods())
                    .filter(method -> method.getParameterCount() == 1)
                    .map(Method::getName)
                    .collect(Collectors.toSet());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(type.getName() + " has no builder", e);
        }
    }

    private static boolean isNestedSettings(Class<?> type) {
        if (!type.getName().startsWith("org.lolaf.staffix.") || type.isInterface()) {
            return false;
        }
        try {
            return Modifier.isStatic(type.getMethod("builder").getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean isInteger(Class<?> type) {
        return type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class || type == byte.class || type == Byte.class;
    }

    private static boolean isMapKey(Type type) {
        return type == String.class || type instanceof Class && ((Class<?>) type).isEnum();
    }

    private static boolean isData(ObjectNode schema) {
        return schema.has("type") || schema.has("$ref");
    }

    private static ObjectNode javaType(Type type) {
        return NODES.objectNode().put("x-staffix-javaType", type.getTypeName().replace('$', '.'));
    }

    private static void putIfPresent(ObjectNode node, String name, String value) {
        if (value != null) {
            node.put(name, value);
        }
    }
}
