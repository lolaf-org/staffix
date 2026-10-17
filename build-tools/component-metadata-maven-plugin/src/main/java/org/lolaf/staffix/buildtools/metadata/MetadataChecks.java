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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The rules keeping a module's metadata complete, checked before anything is written so a forgotten guide or link fails
 * the build instead of shipping a jar that lacks it.
 */
@UtilityClass
public class MetadataChecks {

    /**
     * @param classes the module's public concrete classes
     * @return each rule the module breaks, empty when it keeps them all
     */
    public static List<String> problems(Path classesDirectory, List<Component> components, List<Class<?>> classes)
            throws IOException {
        List<String> problems = new ArrayList<>();
        for (Component component : components) {
            Class<?> type = component.getSettingsClass();
            if (!Files.isRegularFile(ComponentGuide.besideClass(type, classesDirectory))) {
                problems.add("No guide for " + type.getName() + ": write " + type.getSimpleName()
                        + ".guide.yaml in src/main/resources beside it");
            }
        }
        for (Class<?> type : classes) {
            if (ComponentScanner.annotation(type, SpringComponentGenerator.CONFIGURATION_PROPERTIES).isPresent()
                    && SpringComponentGenerator.configuredComponent(type) == null) {
                problems.add(type.getName() + " has @ConfigurationProperties but no @ConfiguresComponent");
            }
        }
        Set<String> described = Stream.concat(components.stream().map(Component::getSettingsClass),
                        classes.stream().filter(type -> SpringComponentGenerator.configuredComponent(type) != null))
                .map(Class::getName)
                .collect(Collectors.toSet());
        for (String owner : guideOwners(classesDirectory)) {
            if (!described.contains(owner)) {
                problems.add(owner + ".guide.yaml belongs to no component nor @ConfiguresComponent class");
            }
        }
        return problems;
    }

    private static List<String> guideOwners(Path classesDirectory) throws IOException {
        if (!Files.isDirectory(classesDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(classesDirectory)) {
            return files.map(file -> classesDirectory.relativize(file).toString())
                    .filter(name -> name.endsWith(ComponentGuide.SUFFIX))
                    .map(name -> name.substring(0, name.length() - ComponentGuide.SUFFIX.length())
                            .replace(File.separatorChar, '.'))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }
}
