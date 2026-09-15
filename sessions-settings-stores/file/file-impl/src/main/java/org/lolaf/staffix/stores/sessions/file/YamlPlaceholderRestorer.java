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
import lombok.experimental.UtilityClass;
import org.lolaf.staffix.api.session.ConfigValueResolverChain;

import java.util.Iterator;

/**
 * Puts the placeholders back into a session file about to be written, so a file deployed with
 * {@code ${...}} in it still has them after the store writes the session out.
 *
 * <p>Needed because the write path rebuilds the file from the runtime settings, which hold resolved
 * values and no memory of the text they came from. The unresolved tree read from that same file is kept
 * for the store's lifetime and consulted here.
 *
 * <p>A placeholder is restored only where the value being written still equals what it resolved to when
 * the file was read. **Where it differs the write is rejected**: the value is owned by its external
 * source, so overwriting it with a literal would silently sever that binding. Comparison is against the
 * value resolved at read time rather than a fresh resolution, so a source that changed underneath does
 * not read as an edit.
 */
@UtilityClass
final class YamlPlaceholderRestorer {

    /**
     * @param raw      the file as it was read, placeholders intact
     * @param resolved the same tree with placeholders replaced, as at read time
     * @param written  the tree about to be written, which is modified in place
     * @param file     named in the rejection message
     */
    static void restore(JsonNode raw, JsonNode resolved, JsonNode written, String file) {
        restoreInto(raw, resolved, written, file, "");
    }

    private static void restoreInto(JsonNode raw, JsonNode resolved, JsonNode written, String file,
                                    String path) {
        if (raw instanceof ObjectNode && written instanceof ObjectNode) {
            for (Iterator<String> names = raw.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                JsonNode writtenChild = written.get(name);
                if (writtenChild != null) {
                    restoreChild(raw.get(name), resolved.path(name), writtenChild, (ObjectNode) written,
                            name, file, path.isEmpty() ? name : path + "." + name);
                }
            }
        } else if (raw instanceof ArrayNode && written instanceof ArrayNode
                && raw.size() == written.size()) {
            // Only index-wise, and only when the sizes match: a reordered or resized list cannot be
            // matched up, and guessing would restore a placeholder onto the wrong element.
            for (int i = 0; i < raw.size(); i++) {
                restoreElement(raw.get(i), resolved.path(i), written.get(i), (ArrayNode) written, i, file,
                        path + "[" + i + "]");
            }
        }
    }

    private static void restoreChild(JsonNode raw, JsonNode resolved, JsonNode written, ObjectNode parent,
                                     String name, String file, String path) {
        if (holdsPlaceholder(raw)) {
            requireUnchanged(raw, resolved, written, file, path);
            parent.set(name, raw);
        } else {
            restoreInto(raw, resolved, written, file, path);
        }
    }

    private static void restoreElement(JsonNode raw, JsonNode resolved, JsonNode written, ArrayNode parent,
                                       int index, String file, String path) {
        if (holdsPlaceholder(raw)) {
            requireUnchanged(raw, resolved, written, file, path);
            parent.set(index, raw);
        } else {
            restoreInto(raw, resolved, written, file, path);
        }
    }

    private static void requireUnchanged(JsonNode raw, JsonNode resolved, JsonNode written, String file,
                                         String path) {
        if (!resolved.asText().equals(written.asText())) {
            throw new IllegalStateException("Refusing to write " + file + ": " + path + " is configured as "
                    + raw.textValue() + ", which resolved to '" + resolved.asText() + "', but the value to"
                    + " write is '" + written.asText() + "'. A value backed by a placeholder is owned by"
                    + " its source and cannot be changed through the store.");
        }
    }

    private static boolean holdsPlaceholder(JsonNode node) {
        return node.isTextual() && ConfigValueResolverChain.holdsPlaceholder(node.textValue());
    }
}
