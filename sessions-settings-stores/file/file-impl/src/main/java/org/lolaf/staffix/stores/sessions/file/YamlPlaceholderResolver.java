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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.experimental.UtilityClass;
import org.lolaf.staffix.api.session.ConfigValueResolverChain;

import java.util.Iterator;
import java.util.Map;

/**
 * Replaces the placeholders in a parsed session file, before it is bound to
 * {@link YamlFixSessionSettings}.
 *
 * <p>On the tree rather than on the bound object, so a placeholder works in a field of any type: a
 * resolved {@code "PT30S"} or {@code "8080"} is still a string when Jackson binds it, and Jackson coerces
 * it to the field's type as it would any scalar. Only a quoted YAML scalar can hold one, so only textual
 * nodes are visited.
 *
 * <p>The tree it is given is left untouched - the caller keeps it to restore the placeholders when the
 * file is written back.
 */
@UtilityClass
final class YamlPlaceholderResolver {

    /**
     * @param raw   the file as parsed, which is not modified
     * @param chain the resolvers to ask
     * @param file  named in the failure message when a placeholder cannot be resolved
     * @return a copy with every placeholder replaced
     */
    static JsonNode resolve(JsonNode raw, ConfigValueResolverChain chain, String file) {
        JsonNode resolved = raw.deepCopy();
        resolveInto(resolved, chain, file, "");
        return resolved;
    }

    private static void resolveInto(JsonNode node, ConfigValueResolverChain chain, String file, String path) {
        if (node instanceof ObjectNode) {
            ObjectNode object = (ObjectNode) node;
            for (Iterator<Map.Entry<String, JsonNode>> fields = object.fields(); fields.hasNext(); ) {
                Map.Entry<String, JsonNode> field = fields.next();
                String childPath = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
                JsonNode replacement = resolvedScalar(field.getValue(), chain, file, childPath);
                if (replacement != null) {
                    field.setValue(replacement);
                } else {
                    resolveInto(field.getValue(), chain, file, childPath);
                }
            }
        } else if (node instanceof ArrayNode) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                String childPath = path + "[" + i + "]";
                JsonNode replacement = resolvedScalar(array.get(i), chain, file, childPath);
                if (replacement != null) {
                    array.set(i, replacement);
                } else {
                    resolveInto(array.get(i), chain, file, childPath);
                }
            }
        }
    }

    private static JsonNode resolvedScalar(JsonNode node, ConfigValueResolverChain chain, String file,
                                           String path) {
        if (!node.isTextual() || !ConfigValueResolverChain.holdsPlaceholder(node.textValue())) {
            return null;
        }
        return TextNode.valueOf(chain.resolve(node.textValue(), file + " at " + path));
    }
}
