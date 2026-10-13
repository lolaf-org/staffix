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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.function.Consumer;

import static org.lolaf.staffix.sessions.settings.document.FixSessionSettingsDocument.CURRENT_SCHEMA_VERSION;

/**
 * Brings a document of an earlier schema version to the current one before it is bound, so
 * {@link FixSessionSettingsDocument} only ever describes the current shape.
 */
@UtilityClass
public class FixSessionSettingsDocumentUpgrader {

    public static final String SCHEMA_VERSION = "schemaVersion";

    /**
     * Index {@code i} upgrades a document of version {@code i + 1} to version {@code i + 2}, in place.
     */
    static final List<Consumer<ObjectNode>> UPGRADES = List.of();

    /**
     * @param source where the document came from, named in the error
     * @return the document at {@link FixSessionSettingsDocument#CURRENT_SCHEMA_VERSION}: itself when already
     * there, otherwise an upgraded copy
     * @throws IllegalArgumentException when the document has no schema version, or one this staffix cannot read
     */
    public static JsonNode upgrade(JsonNode document, String source) {
        int version = schemaVersion(document, source);
        if (version == CURRENT_SCHEMA_VERSION) {
            return document;
        }
        ObjectNode upgraded = document.deepCopy();
        for (int from = version; from < CURRENT_SCHEMA_VERSION; from++) {
            UPGRADES.get(from - 1).accept(upgraded);
        }
        upgraded.put(SCHEMA_VERSION, CURRENT_SCHEMA_VERSION);
        return upgraded;
    }

    private static int schemaVersion(JsonNode document, String source) {
        JsonNode version = document.path(SCHEMA_VERSION);
        if (version.isMissingNode() || version.isNull()) {
            throw new IllegalArgumentException(source + " has no " + SCHEMA_VERSION + "; a document written for this"
                    + " staffix declares " + SCHEMA_VERSION + ": " + CURRENT_SCHEMA_VERSION);
        }
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 1) {
            throw new IllegalArgumentException(source + " has " + SCHEMA_VERSION + " " + version
                    + ", which is not a schema version: expected a positive integer");
        }
        if (version.intValue() > CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(source + " is of " + SCHEMA_VERSION + " " + version.intValue()
                    + ", but this staffix reads up to " + CURRENT_SCHEMA_VERSION + ": it was written by a newer staffix");
        }
        return version.intValue();
    }
}
