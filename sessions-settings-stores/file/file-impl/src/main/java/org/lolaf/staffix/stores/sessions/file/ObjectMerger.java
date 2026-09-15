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

import lombok.experimental.UtilityClass;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;

/**
 * Utility class for merging two instances of the same class.
 * Merges default values from a source object into null fields of a target object.
 * Supports nested objects through recursive reflection.
 */
@UtilityClass
public class ObjectMerger {

    /**
     * Merges fields from defaultInstance into targetInstance.
     * For each field in targetInstance that is null, if the corresponding field
     * in defaultInstance is non-null, it copies the value.
     * Handles nested objects recursively.
     *
     * @param defaultInstance The instance containing default values
     * @param targetInstance  The instance to be populated with defaults
     * @param <T>             The type of the objects being merged
     * @throws IllegalArgumentException if objects are not of the same class
     * @throws IllegalAccessException   if field access fails
     */
    public static <T> T merge(T defaultInstance, T targetInstance) throws IllegalAccessException {

        if (defaultInstance == null || targetInstance == null) {
            throw new IllegalArgumentException("Both instances must be non-null");
        }

        if (!defaultInstance.getClass().equals(targetInstance.getClass())) {
            throw new IllegalArgumentException(
                    "Objects must be of the same class. Got: " +
                            defaultInstance.getClass() + " and " + targetInstance.getClass()
            );
        }

        mergeFields(defaultInstance, targetInstance, defaultInstance.getClass());
        return targetInstance;
    }

    /**
     * Recursively merges fields, including inherited fields from superclasses.
     */
    private static void mergeFields(Object defaultInstance, Object targetInstance, Class<?> clazz)
            throws IllegalAccessException {

        if (clazz == null || clazz == Object.class) {
            return;
        }
        // Process fields declared in this class
        for (Field field : clazz.getDeclaredFields()) {
            // Skip static and final fields
            if (Modifier.isStatic(field.getModifiers()) ||
                    Modifier.isFinal(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);

            Object defaultValue = field.get(defaultInstance);
            Object targetValue = field.get(targetInstance);
            // If target field is null and default field is not null, copy the value
            if (targetValue == null && defaultValue != null) {
                if (isPrimitiveOrWrapper(field.getType()) ||
                        isCommonImmutable(field.getType())) {
                    // For primitives, wrappers, and common immutable types, direct assignment
                    field.set(targetInstance, defaultValue);
                } else {
                    // For complex objects, create a deep copy to avoid shared references
                    try {
                        Object copiedValue = deepCopy(defaultValue);
                        field.set(targetInstance, copiedValue);
                    } catch (Exception e) {
                        // If deep copy fails, fall back to direct assignment
                        field.set(targetInstance, defaultValue);
                    }
                }
            }
            // If both are non-null and the field is a map merge collection data
            else if (defaultValue != null && targetValue instanceof Map) {
                Map mapTarget = (Map) targetValue;
                Map mapDefault = (Map) defaultValue;
                mapTarget.putAll(mapDefault);
            }
            // If both are non-null and the field is a collection merge collection data
            else if (defaultValue != null && targetValue instanceof Collection) {
                Collection collectionTarget = (Collection) targetValue;
                Collection collectionDefault = (Collection) defaultValue;
                collectionTarget.addAll(collectionDefault);

            }
            // If both are non-null and the field is a complex object, merge recursively
            else if (targetValue != null && defaultValue != null &&
                    !isPrimitiveOrWrapper(field.getType()) &&
                    !isCommonImmutable(field.getType())) {
                mergeFields(defaultValue, targetValue, field.getType());
            }
        }
        // Process fields from superclass
        mergeFields(defaultInstance, targetInstance, clazz.getSuperclass());
    }

    /**
     * Creates a deep copy of an object using reflection.
     */
    private static Object deepCopy(Object source) throws Exception {
        if (source == null) {
            return null;
        }

        Class<?> clazz = source.getClass();
        Object copy = clazz.getDeclaredConstructor().newInstance();
        copyAllFields(source, copy, clazz);
        return copy;
    }

    /**
     * Copies all fields from source to destination, including superclass fields.
     */
    private static void copyAllFields(Object source, Object destination, Class<?> clazz)
            throws IllegalAccessException {

        if (clazz == null || clazz == Object.class) {
            return;
        }
        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) ||
                    Modifier.isFinal(field.getModifiers())) {
                continue;
            }

            field.setAccessible(true);
            Object value = field.get(source);
            if (value != null
                    && !isPrimitiveOrWrapper(field.getType())
                    && !isCommonImmutable(field.getType())) {
                try {
                    value = deepCopy(value);
                } catch (Exception e) {
                    // If deep copy fails, use the original reference
                }
            }
            field.set(destination, value);
        }
        copyAllFields(source, destination, clazz.getSuperclass());
    }

    /**
     * Checks if a class is a primitive type or its wrapper.
     */
    private static boolean isPrimitiveOrWrapper(Class<?> clazz) {
        return clazz.isPrimitive() ||
                clazz == Boolean.class ||
                clazz == Byte.class ||
                clazz == Character.class ||
                clazz == Short.class ||
                clazz == Integer.class ||
                clazz == Long.class ||
                clazz == Float.class ||
                clazz == Double.class;
    }

    /**
     * Checks if a class is a common immutable type.
     */
    private static boolean isCommonImmutable(Class<?> clazz) {
        return clazz == String.class ||
                clazz.isEnum() ||
                clazz == java.math.BigDecimal.class ||
                clazz == java.math.BigInteger.class ||
                clazz == java.time.LocalDate.class ||
                clazz == java.time.LocalDateTime.class ||
                clazz == java.time.LocalTime.class ||
                clazz == java.util.UUID.class;
    }
}