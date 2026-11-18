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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestFixSessionSettingsPlaceholders {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String json) throws JsonProcessingException {
        return mapper.readTree(json);
    }

    @Test
    void findsPlaceholdersInObjectsListsAndKeysThatNeedEscaping() throws JsonProcessingException {
        assertThat(FixSessionSettingsPlaceholders.find(json(
                "{\"plain\":\"x\",\"timeout\":\"${a}\",\"list\":[\"y\",\"p-${b}\"],\"map\":{\"a/b~c\":\"${c}\"},\"n\":1}")))
                .containsOnly(Map.entry("/timeout", "${a}"), Map.entry("/list/1", "p-${b}"), Map.entry("/map/a~1b~0c", "${c}"));
    }

    @Test
    void anOverlayGivesBackTheDocumentAsWrittenAndLeavesTheResolvedOneAlone() throws JsonProcessingException {
        JsonNode unresolved = json("{\"timeout\":\"${a}\",\"list\":[\"y\",\"${b}\"],\"map\":{\"a/b\":\"${c}\"},\"n\":1}");
        JsonNode resolved = json("{\"timeout\":\"PT1S\",\"list\":[\"y\",\"z\"],\"map\":{\"a/b\":\"v\"},\"n\":1}");
        JsonNode untouched = resolved.deepCopy();

        assertThat(FixSessionSettingsPlaceholders.overlay(resolved, FixSessionSettingsPlaceholders.find(unresolved))).isEqualTo(unresolved);
        assertThat(resolved).isEqualTo(untouched);
    }

    @Test
    void thePlaceholdersFoundCannotBeChanged() throws JsonProcessingException {
        Map<String, String> found = FixSessionSettingsPlaceholders.find(json("{\"timeout\":\"${a}\"}"));

        assertThatThrownBy(() -> found.put("/other", "${b}")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aPointerTheDocumentDoesNotHoldIsLeftOut() throws JsonProcessingException {
        JsonNode resolved = json("{\"n\":1,\"list\":[\"y\"]}");

        assertThat(FixSessionSettingsPlaceholders.overlay(resolved, Map.of("/gone", "${a}", "/list/4", "${b}", "/n/x", "${c}"))).isEqualTo(resolved);
    }
}
