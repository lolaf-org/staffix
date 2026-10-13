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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.lolaf.staffix.sessions.settings.document.FixSessionSettingsDocument.CURRENT_SCHEMA_VERSION;

class TestFixSessionSettingsDocumentUpgrader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void everyEarlierVersionHasAnUpgrade() {
        assertThat(FixSessionSettingsDocumentUpgrader.UPGRADES).hasSize(CURRENT_SCHEMA_VERSION - 1);
    }

    @Test
    void currentDocumentIsReturnedAsIs() throws Exception {
        JsonNode document = MAPPER.readTree("{\"schemaVersion\": " + CURRENT_SCHEMA_VERSION + "}");

        assertThat(FixSessionSettingsDocumentUpgrader.upgrade(document, "doc")).isSameAs(document);
    }

    @Test
    void documentWithoutSchemaVersionIsRefused() throws Exception {
        JsonNode document = MAPPER.readTree("{\"fixSessionType\": \"ACCEPTOR\"}");

        assertThatThrownBy(() -> FixSessionSettingsDocumentUpgrader.upgrade(document, "doc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("doc has no schemaVersion");
    }

    @Test
    void documentOfANewerVersionIsRefused() throws Exception {
        JsonNode document = MAPPER.readTree("{\"schemaVersion\": " + (CURRENT_SCHEMA_VERSION + 1) + "}");

        assertThatThrownBy(() -> FixSessionSettingsDocumentUpgrader.upgrade(document, "doc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("written by a newer staffix");
    }

    @Test
    void schemaVersionThatIsNotAPositiveIntegerIsRefused() throws Exception {
        for (String version : new String[]{"\"1\"", "1.5", "0", "-1"}) {
            JsonNode document = MAPPER.readTree("{\"schemaVersion\": " + version + "}");

            assertThatThrownBy(() -> FixSessionSettingsDocumentUpgrader.upgrade(document, "doc"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a schema version");
        }
    }
}
