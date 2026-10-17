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
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the staffix Spring Boot property keys a document names that no module binds: Spring ignores an unknown
 * property, so a wrong key in a guide or an example fails silently in the reader's application.
 */
@RequiredArgsConstructor
public class DocumentedPropertyKeys {

    /**
     * Not after a word, a dot or a {@code /} (a Java package or a path), nor after {@code <} or <code>{</code> (a
     * Maven property).
     */
    private static final Pattern KEY =
            Pattern.compile("(?<![\\w./<{$])staffix\\.[a-z][a-z0-9-]*(?:\\.[A-Za-z0-9_<>*-]+|\\[[0-9a-z*]+])*");
    private static final Pattern ROOT = Pattern.compile("^staffix\\.[a-z0-9-]+");

    private enum Shape { VALUE, OBJECT, LIST, VALUE_MAP, OBJECT_MAP }

    private final Map<String, Shape> keys;
    private final Set<String> prefixes;
    private final Set<String> starterRoots;
    private final Set<String> ignored;

    /**
     * @param springFiles       the {@code META-INF/staffix/spring/} files of every component
     * @param starterProperties the property names the starter declares, whose roots are the starter's own
     * @param ignored           keys written like a property that are something else, such as a Maven property
     */
    public static DocumentedPropertyKeys of(Collection<ObjectNode> springFiles, Collection<String> starterProperties,
                                            Collection<String> ignored) {
        Map<String, Shape> keys = new HashMap<>();
        Set<String> prefixes = new TreeSet<>();
        for (ObjectNode file : springFiles) {
            String prefix = file.get("prefix").asText();
            prefixes.add(prefix);
            keys.put(prefix, Shape.OBJECT);
            JsonNode properties = file.get("properties");
            describe(properties.path("$defs"), properties, prefix, keys);
        }
        Set<String> starterRoots = new TreeSet<>();
        for (String property : starterProperties) {
            Matcher root = ROOT.matcher(property);
            if (root.find()) {
                starterRoots.add(root.group());
            }
        }
        return new DocumentedPropertyKeys(keys, prefixes, starterRoots, new TreeSet<>(ignored));
    }

    /**
     * @param document relative to {@code root}, as the problems name it
     * @return each unknown key as {@code file:line: key}
     */
    public List<String> problems(Path root, Path document) throws IOException {
        List<String> problems = new ArrayList<>();
        List<String> lines = Files.readAllLines(root.resolve(document));
        for (int line = 0; line < lines.size(); line++) {
            Matcher key = KEY.matcher(lines.get(line));
            while (key.find()) {
                if (!ignored.contains(key.group()) && !isKnown(key.group())) {
                    problems.add(document + ":" + (line + 1) + ": " + key.group()
                            + " is no property of the starter or of a component's Spring Boot module");
                }
            }
        }
        return problems;
    }

    /**
     * @param key as a document writes it, instance ids and placeholders included
     */
    public boolean isKnown(String key) {
        String normalized = normalize(key);
        if (keys.containsKey(normalized) || isListItem(normalized) || isWildcard(normalized)
                || isInValueMap(normalized)) {
            return true;
        }
        Matcher root = ROOT.matcher(normalized);
        return root.find() && starterRoots.contains(root.group())
                && prefixes.stream().noneMatch(prefix -> normalized.startsWith(prefix + "."));
    }

    /**
     * Instance ids, indexes and placeholders such as {@code <id>} stand for any value.
     */
    private static String normalize(String key) {
        return key.replaceAll("\\.instances\\.[^.\\[]+", ".instances.*")
                .replaceAll("\\[[^]]*]", "[*]")
                .replaceAll("\\.<[^>]*>", ".*");
    }

    private boolean isListItem(String key) {
        return key.endsWith("[*]") && keys.get(key.substring(0, key.length() - 3)) == Shape.LIST;
    }

    /**
     * {@code instances.<key>.*}: every property of an object.
     */
    private boolean isWildcard(String key) {
        if (!key.endsWith(".*")) {
            return false;
        }
        Shape parent = keys.get(key.substring(0, key.length() - 2));
        return parent == Shape.OBJECT || parent == Shape.OBJECT_MAP;
    }

    private boolean isInValueMap(String key) {
        for (int dot = key.lastIndexOf('.'); dot > 0; dot = key.lastIndexOf('.', dot - 1)) {
            if (keys.get(key.substring(0, dot)) == Shape.VALUE_MAP) {
                return true;
            }
        }
        return false;
    }

    private static void describe(JsonNode definitions, JsonNode schema, String base, Map<String, Shape> keys) {
        schema.path("properties").fields().forEachRemaining(property -> {
            String key = base + "." + property.getKey();
            JsonNode type = resolve(definitions, property.getValue());
            if (type.has("properties")) {
                keys.put(key, Shape.OBJECT);
                describe(definitions, type, key, keys);
            } else if (type.has("additionalProperties")) {
                JsonNode values = resolve(definitions, type.get("additionalProperties"));
                if (values.has("properties")) {
                    keys.put(key, Shape.OBJECT_MAP);
                    keys.put(key + ".*", Shape.OBJECT);
                    describe(definitions, values, key + ".*", keys);
                } else {
                    keys.put(key, Shape.VALUE_MAP);
                }
            } else if ("array".equals(type.path("type").asText())) {
                keys.put(key, Shape.LIST);
                JsonNode items = resolve(definitions, type.path("items"));
                if (items.has("properties")) {
                    describe(definitions, items, key + "[*]", keys);
                }
            } else {
                keys.put(key, Shape.VALUE);
            }
        });
    }

    private static JsonNode resolve(JsonNode definitions, JsonNode schema) {
        String reference = schema.path("$ref").asText(null);
        return reference == null ? schema : definitions.path(reference.substring(reference.lastIndexOf('/') + 1));
    }
}
