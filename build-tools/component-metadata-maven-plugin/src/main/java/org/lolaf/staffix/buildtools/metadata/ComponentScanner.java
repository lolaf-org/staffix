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

import lombok.experimental.UtilityClass;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds the components among a module's compiled classes. Annotations are matched by name, since the classes come from
 * the module's own class loader and this plugin does not depend on {@code staffix-api}.
 */
@UtilityClass
public class ComponentScanner {

    public static final String COMPONENT_KIND = "org.lolaf.staffix.api.metadata.ComponentKind";

    /**
     * @param loader loads the module's classes and their compile classpath
     * @return the concrete classes of the directory whose settings type carries {@code @ComponentKind}, by name
     */
    public static List<Component> scan(Path classesDirectory, ClassLoader loader) throws IOException {
        List<Component> components = new ArrayList<>();
        for (String className : classNames(classesDirectory)) {
            Class<?> type;
            try {
                type = Class.forName(className, false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }
            if (isConcrete(type)) {
                kind(type).ifPresent(kind -> components.add(new Component(type, kind)));
            }
        }
        return components;
    }

    static Optional<String> kind(Class<?> type) {
        Optional<String> declared = annotation(type, COMPONENT_KIND).map(kind -> (String) value(kind, "value"));
        if (declared.isPresent()) {
            return declared;
        }
        return Stream.concat(Stream.ofNullable(type.getSuperclass()), Stream.of(type.getInterfaces()))
                .map(ComponentScanner::kind)
                .flatMap(Optional::stream)
                .findFirst();
    }

    static Optional<Annotation> annotation(Class<?> type, String annotationName) {
        return Stream.of(type.getAnnotations())
                .filter(annotation -> annotation.annotationType().getName().equals(annotationName))
                .findFirst();
    }

    static Object value(Annotation annotation, String element) {
        try {
            return annotation.annotationType().getMethod(element).invoke(annotation);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(annotation.annotationType().getName() + " has no " + element, e);
        }
    }

    private static boolean isConcrete(Class<?> type) {
        return !type.isInterface() && !type.isAnnotation() && !type.isEnum()
                && !Modifier.isAbstract(type.getModifiers()) && !type.isAnonymousClass()
                && !type.isLocalClass();
    }

    private static List<String> classNames(Path classesDirectory) throws IOException {
        if (!Files.isDirectory(classesDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(classesDirectory)) {
            return files.filter(file -> file.toString().endsWith(".class"))
                    .map(file -> classesDirectory.relativize(file).toString())
                    .filter(name -> !name.endsWith("module-info.class") && !name.endsWith("package-info.class"))
                    .map(name -> name.substring(0, name.length() - ".class".length()).replace(File.separatorChar, '.'))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }
}
