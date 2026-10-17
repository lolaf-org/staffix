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
import lombok.experimental.UtilityClass;

import java.util.*;

/**
 * The index of every component's metadata files, holding only what a skill needs to pick the components it works on
 * before it opens their files.
 */
@UtilityClass
public class ComponentsCatalogue {

    public static final String ROOT = "META-INF/staffix/";
    public static final String COMPONENTS = "components/";
    public static final String SPRING = "spring/";
    public static final String SCHEMA_SUFFIX = ".v" + ComponentSchemaGenerator.FORMAT_VERSION + ".schema.json";
    public static final String SPRING_SUFFIX = ".v" + ComponentSchemaGenerator.FORMAT_VERSION + ".json";
    public static final String INDEX = "catalogue.v" + ComponentSchemaGenerator.FORMAT_VERSION + ".json";

    /**
     * @param files the metadata files of every component jar, keyed by their path under {@value #ROOT}
     * @throws IllegalArgumentException when a file is not component metadata, a component has no guide summary, a
     *                                  Spring file configures a component missing from the files, or two components
     *                                  of a kind disagree on how sessions select them
     */
    public static ObjectNode index(String version, Map<String, ObjectNode> files) {
        SortedMap<String, ObjectNode> schemas = new TreeMap<>();
        SortedMap<String, ObjectNode> springFiles = new TreeMap<>();
        files.forEach((path, content) -> {
            if (path.startsWith(COMPONENTS) && path.endsWith(SCHEMA_SUFFIX)) {
                schemas.put(settingsClass(path, COMPONENTS, SCHEMA_SUFFIX), content);
            } else if (path.startsWith(SPRING) && path.endsWith(SPRING_SUFFIX)) {
                springFiles.put(settingsClass(path, SPRING, SPRING_SUFFIX), content);
            } else {
                throw new IllegalArgumentException(ROOT + path + " is not component metadata");
            }
        });
        springFiles.keySet().stream().filter(settings -> !schemas.containsKey(settings)).findFirst()
                .ifPresent(settings -> {
                    throw new IllegalArgumentException("The Spring metadata of " + settings
                            + " has no component schema beside it");
                });

        SortedMap<String, ObjectNode> kinds = new TreeMap<>();
        SortedMap<String, ObjectNode> components = new TreeMap<>();
        schemas.forEach((settings, schema) -> {
            ObjectNode entry = component(settings, schema, springFiles.get(settings));
            components.put(entry.get("kind").asText() + " " + settings, entry);
            addKind(kinds, settings, schema.get("x-staffix"));
        });

        ObjectNode index = JsonTypes.NODES.objectNode();
        index.put("$id", "staffix:components-catalogue:v" + ComponentSchemaGenerator.FORMAT_VERSION);
        index.put("version", version);
        index.putObject("kinds").setAll(kinds);
        index.putArray("components").addAll(components.values());
        return index;
    }

    /**
     * @param describingModules the reactor modules generating component metadata
     * @param dependencies      the catalogue's own staffix dependencies
     * @param contributed       how many metadata files each staffix artifact on its class path holds
     * @return each module the catalogue leaves out, and each dependency that adds nothing to it
     */
    public static List<String> coverageProblems(Set<String> describingModules, Set<String> dependencies,
                                                Map<String, Integer> contributed) {
        List<String> problems = new ArrayList<>();
        describingModules.stream().filter(module -> !dependencies.contains(module)).forEach(module -> problems.add(
                module + " describes components but is not a dependency of the catalogue: add it to its pom"));
        dependencies.stream().filter(dependency -> contributed.getOrDefault(dependency, 0) == 0).forEach(dependency ->
                problems.add(dependency + " is a dependency of the catalogue but holds no component metadata"));
        return problems;
    }

    private static ObjectNode component(String settings, ObjectNode schema, ObjectNode springFile) {
        JsonNode staffix = schema.get("x-staffix");
        String summary = staffix.path("guide").path("summary").asText(null);
        if (summary == null) {
            throw new IllegalArgumentException(settings + " has no guide summary");
        }
        ObjectNode entry = JsonTypes.NODES.objectNode();
        entry.put("settingsClass", settings);
        entry.put("kind", staffix.get("kind").asText());
        entry.put("summary", summary);
        String wrappedField = staffix.path("instanceIdFrom").asText(null);
        if (wrappedField != null) {
            entry.put("wraps", schema.path("properties").path(wrappedField).path("x-staffix-kind").asText());
        }
        if (staffix.has("requiresOneOf")) {
            entry.set("requiresOneOf", staffix.get("requiresOneOf"));
        }
        entry.put("needsJvmFlags", !staffix.path("jvmFlags").isEmpty());
        ObjectNode java = entry.putObject("java");
        java.put("artifact", staffix.get("artifact").asText());
        java.put("file", COMPONENTS + settings + SCHEMA_SUFFIX);
        if (springFile != null) {
            ObjectNode spring = entry.putObject("spring");
            spring.put("artifact", springFile.get("artifact").asText());
            spring.put("prefix", springFile.get("prefix").asText());
            if (springFile.has("instances")) {
                spring.put("instances", springFile.get("instances").asText());
            }
            spring.put("file", SPRING + settings + SPRING_SUFFIX);
        }
        return entry;
    }

    private static void addKind(SortedMap<String, ObjectNode> kinds, String settings, JsonNode staffix) {
        ObjectNode kind = JsonTypes.NODES.objectNode();
        kind.set("cardinality", staffix.get("cardinality"));
        if (staffix.has("selectedBy")) {
            kind.set("selectedBy", staffix.get("selectedBy"));
        }
        ObjectNode known = kinds.putIfAbsent(staffix.get("kind").asText(), kind);
        if (known != null && !known.equals(kind)) {
            throw new IllegalArgumentException(settings + " is counted or selected unlike the other " + staffix.get("kind").asText()
                    + " components: " + kind + " against " + known);
        }
    }

    private static String settingsClass(String path, String directory, String suffix) {
        return path.substring(directory.length(), path.length() - suffix.length());
    }
}
