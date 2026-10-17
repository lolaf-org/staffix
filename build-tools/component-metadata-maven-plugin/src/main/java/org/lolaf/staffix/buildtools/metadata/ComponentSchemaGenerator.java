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

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.buildtools.schema.ValueSchemas;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
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

    private static final JsonNodeFactory NODES = JsonTypes.NODES;

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
        JsonTypes.putIfPresent(schema, "description", sources.describe(type));
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
        for (Field field : JsonTypes.fields(type)) {
            if (!builderMethods.contains(field.getName())) {
                continue;
            }
            ObjectNode property = propertySchema(field.getGenericType(), definitions);
            JsonTypes.putIfPresent(property, "description", sources.describe(field.getDeclaringClass(), field.getName()));
            Object value = defaultValue(field, unset);
            if (value == null) {
                if (isRequired(field)) {
                    required.add(field.getName());
                }
            } else {
                JsonTypes.putDefault(property, value, isNestedSettings(field.getType()));
            }
            properties.set(field.getName(), property);
        }
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        schema.set("properties", properties);
    }

    private ObjectNode propertySchema(Type type, ObjectNode definitions) {
        ObjectNode schema = JsonTypes.schema(type, ValueSchemas.Syntax.SETTINGS, raw -> objectSchema(raw, definitions));
        if (type instanceof ParameterizedType) {
            ComponentScanner.kind((Class<?>) ((ParameterizedType) type).getRawType())
                    .ifPresent(kind -> schema.put("x-staffix-kind", kind));
        }
        return schema;
    }

    private ObjectNode objectSchema(Class<?> type, ObjectNode definitions) {
        Optional<String> kind = ComponentScanner.kind(type);
        if (kind.isPresent()) {
            return JsonTypes.javaType(type).put("x-staffix-kind", kind.get());
        }
        if (!isNestedSettings(type)) {
            return JsonTypes.javaType(type);
        }
        String name = type.getSimpleName();
        if (!definitions.has(name)) {
            ObjectNode definition = definitions.putObject(name);
            JsonTypes.putIfPresent(definition, "description", sources.describe(type));
            describeObject(definition, type, unsetInstance(type), definitions);
        }
        return NODES.objectNode().put("$ref", "#/$defs/" + name);
    }

    private ObjectNode staffix(Component component, Object unset, String groupId, String artifactId) {
        Class<?> type = component.getSettingsClass();
        ObjectNode staffix = NODES.objectNode();
        staffix.put("kind", component.getKind());
        staffix.put("artifact", groupId + ":" + artifactId);
        ComponentScanner.kindType(type).flatMap(kind -> ComponentScanner.annotation(kind, METADATA + "SelectedBy"))
                .ifPresent(selectedBy -> {
                    ObjectNode selection = staffix.putObject("selectedBy");
                    selection.put("sessionSetting", (String) ComponentScanner.value(selectedBy, "sessionSetting"));
                    selection.put("whenUnnamed", ((Enum<?>) ComponentScanner.value(selectedBy, "whenUnnamed")).name());
                    selection.put("whenUnknown", ((Enum<?>) ComponentScanner.value(selectedBy, "whenUnknown")).name());
                });
        JsonTypes.fields(type).stream().filter(field -> JsonTypes.annotation(field.getAnnotations(), METADATA + "Wrapped").isPresent())
                .findFirst().ifPresent(field -> staffix.put("instanceIdFrom", field.getName()));
        ComponentScanner.annotation(type, METADATA + "RequiresJvmFlags").ifPresent(requires -> {
            ArrayNode flags = staffix.putArray("jvmFlags");
            Stream.of((Object[]) ComponentScanner.value(requires, "value"))
                    .flatMap(set -> ((List<?>) invoke(set, "getFlags")).stream())
                    .map(String.class::cast)
                    .distinct()
                    .forEach(flags::add);
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

    private static Object invoke(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to call " + method + " on " + target, e);
        }
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

    /**
     * A field is required when its class is {@code @NullMarked}, it is not {@code @Nullable}, and nothing sets it.
     */
    private static boolean isRequired(Field field) {
        return !field.getType().isPrimitive() && isNullMarked(field.getDeclaringClass())
                && JsonTypes.annotation(field.getAnnotatedType().getAnnotations(), NULLABLE).isEmpty()
                && JsonTypes.annotation(field.getAnnotations(), NULLABLE).isEmpty();
    }

    private static boolean isNullMarked(Class<?> type) {
        for (Class<?> scope = type; scope != null; scope = scope.getEnclosingClass()) {
            if (ComponentScanner.annotation(scope, NULL_MARKED).isPresent()) {
                return true;
            }
        }
        return type.getPackage() != null && JsonTypes.annotation(type.getPackage().getAnnotations(), NULL_MARKED).isPresent();
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
}
