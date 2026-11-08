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
package org.lolaf.staffix.sessions.settings.document;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.experimental.UtilityClass;
import org.lolaf.staffix.api.session.ConfigValueResolverChain;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Between a settings document's tree and {@link org.lolaf.staffix.api.session.FixSessionSettings#getPlaceholders()},
 * whose keys are JSON pointers into that tree.
 */
@UtilityClass
public class FixSessionSettingsPlaceholders {

    /**
     * @param unresolved a document as written, before its placeholders are resolved
     * @return each placeholder's expression by its JSON pointer
     */
    public static Map<String, String> find(JsonNode unresolved) {
        Map<String, String> placeholders = new HashMap<>();
        collect(unresolved, "", placeholders);
        return Map.copyOf(placeholders);
    }

    /**
     * Leaves out a pointer the document does not hold, as a field the document cannot write back, such as a parsed
     * certificate, has nothing to overlay.
     *
     * @param resolved a document, not modified
     * @return a copy with the expressions in place of the values
     */
    public static JsonNode overlay(JsonNode resolved, Map<String, String> placeholders) {
        JsonNode overlaid = resolved.deepCopy();
        placeholders.forEach((pointer, expression) -> replace(overlaid, JsonPointer.compile(pointer), expression));
        return overlaid;
    }

    private static void collect(JsonNode node, String pointer, Map<String, String> placeholders) {
        if (node.isTextual() && ConfigValueResolverChain.holdsPlaceholder(node.textValue())) {
            placeholders.put(pointer, node.textValue());
        } else if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> fields = node.fields(); fields.hasNext(); ) {
                Map.Entry<String, JsonNode> field = fields.next();
                collect(field.getValue(), pointer + "/" + escape(field.getKey()), placeholders);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collect(node.get(i), pointer + "/" + i, placeholders);
            }
        }
    }

    private static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    private static void replace(JsonNode root, JsonPointer pointer, String expression) {
        if (root.at(pointer).isMissingNode()) {
            return;
        }
        JsonNode parent = root.at(pointer.head());
        if (parent instanceof ObjectNode) {
            ((ObjectNode) parent).set(pointer.last().getMatchingProperty(), TextNode.valueOf(expression));
        } else if (parent instanceof ArrayNode) {
            ((ArrayNode) parent).set(pointer.last().getMatchingIndex(), TextNode.valueOf(expression));
        }
    }
}
