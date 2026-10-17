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
import lombok.experimental.UtilityClass;
import org.lolaf.staffix.buildtools.schema.ValueSchemas;
import org.lolaf.staffix.buildtools.schema.ValueSchemas.Syntax;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The JSON Schema of a Java type and the JSON of a default value, shared by the component and the Spring schemas.
 */
@UtilityClass
class JsonTypes {

    static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Sets and maps from {@code Set.of} and {@code Map.of} iterate in an order that changes with each JVM, which would
     * change the jar from one build to the next.
     */
    private static final Comparator<Object> STABLE_ORDER = (left, right) -> left instanceof Enum && right instanceof Enum
            ? Integer.compare(((Enum<?>) left).ordinal(), ((Enum<?>) right).ordinal())
            : String.valueOf(left).compareTo(String.valueOf(right));

    /**
     * @param objects the schema of a class that is not plain data: a nested object, or only its Java type
     */
    static ObjectNode schema(Type type, Syntax syntax, Function<Class<?>, ObjectNode> objects) {
        if (type instanceof ParameterizedType) {
            return parameterizedSchema((ParameterizedType) type, syntax, objects);
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
        } else if (raw == URI.class || raw == URL.class) {
            schema.put("type", "string").put("format", "uri");
        } else if (raw == File.class || raw == Path.class) {
            schema.put("type", "string");
        } else {
            return ValueSchemas.of(raw, syntax).orElseGet(() -> objects.apply(raw));
        }
        return schema;
    }

    private static ObjectNode parameterizedSchema(ParameterizedType type, Syntax syntax,
                                                  Function<Class<?>, ObjectNode> objects) {
        Class<?> raw = (Class<?>) type.getRawType();
        Type[] arguments = type.getActualTypeArguments();
        if (raw == List.class || raw == Set.class || raw == Collection.class) {
            ObjectNode items = schema(arguments[0], syntax, objects);
            if (isData(items)) {
                ObjectNode schema = NODES.objectNode().put("type", "array");
                schema.set("items", items);
                return raw == Set.class ? schema.put("uniqueItems", true) : schema;
            }
        } else if (raw == Map.class && isMapKey(arguments[0])) {
            ObjectNode values = schema(arguments[1], syntax, objects);
            if (isData(values)) {
                ObjectNode schema = NODES.objectNode().put("type", "object");
                if (((Class<?>) arguments[0]).isEnum()) {
                    schema.set("propertyNames", schema(arguments[0], syntax, objects));
                }
                schema.set("additionalProperties", values);
                return schema;
            }
        }
        return javaType(type);
    }

    /**
     * Sets {@code default} when the value is data and not an empty collection; a value that is code is flagged
     * instead, unless {@code nestedObject}, whose defaults its own schema carries.
     */
    static void putDefault(ObjectNode property, Object value, boolean nestedObject) {
        JsonNode json = toJson(value);
        if (json != null) {
            if (!json.isContainerNode() || !json.isEmpty()) {
                property.set("default", json);
            }
        } else if (!nestedObject) {
            property.put("x-staffix-codeDefault", true);
        }
    }

    private static JsonNode toJson(Object value) {
        if (value instanceof String || value instanceof Character || value instanceof Duration
                || value instanceof LocalTime || value instanceof URI || value instanceof URL || value instanceof File
                || value instanceof Path) {
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
     * Superclass fields first, as a builder or a properties binder lists them.
     */
    static List<Field> fields(Class<?> type) {
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

    static Object read(Field field, Object instance) {
        try {
            field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to read " + field, e);
        }
    }

    static Optional<Annotation> annotation(Annotation[] annotations, String name) {
        return Stream.of(annotations).filter(annotation -> annotation.annotationType().getName().equals(name))
                .findFirst();
    }

    static ObjectNode javaType(Type type) {
        return NODES.objectNode().put("x-staffix-javaType", type.getTypeName().replace('$', '.'));
    }

    static void putIfPresent(ObjectNode node, String name, String value) {
        if (value != null) {
            node.put(name, value);
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
}
