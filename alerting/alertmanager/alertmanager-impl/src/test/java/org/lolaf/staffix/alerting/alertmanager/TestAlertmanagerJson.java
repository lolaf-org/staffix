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
package org.lolaf.staffix.alerting.alertmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestAlertmanagerJson {

    @Test
    void escapesWhatJsonRequires() throws Exception {
        Instant startsAt = Instant.parse("2026-10-11T10:00:00Z");
        PostableAlert alert = new PostableAlert(Map.of("alertname", "queue"),
                Map.of("summary", "a \"quoted\" \\ path\nand\ta control \u0001"), startsAt, startsAt.plusSeconds(60));

        JsonNode json = new ObjectMapper().readTree(AlertmanagerJson.encode(List.of(alert, alert)));

        assertThat(json).hasSize(2);
        assertThat(json.get(0).get("labels").get("alertname").asText()).isEqualTo("queue");
        assertThat(json.get(0).get("annotations").get("summary").asText()).isEqualTo("a \"quoted\" \\ path\nand\ta control \u0001");
        assertThat(json.get(0).get("startsAt").asText()).isEqualTo("2026-10-11T10:00:00Z");
        assertThat(json.get(0).get("endsAt").asText()).isEqualTo("2026-10-11T10:01:00Z");
    }
}
