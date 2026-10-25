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

package org.lolaf.staffix.api.alerting;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class TestProbeScope {

    @Test
    void identifiedByTypeAndNameOnly() {
        ProbeScope labelled = ProbeScope.builder().type("component").name("ha").label("team", "fix").build();

        assertThat(labelled).isEqualTo(ProbeScope.of("component", "ha")).hasSameHashCodeAs(ProbeScope.of("component", "ha"));
        assertThat(labelled).isNotEqualTo(ProbeScope.of("session", "ha"));
        assertThat(labelled.getQualifiedName()).isEqualTo("component/ha");
        assertThat(ProbeId.of(labelled, "followers")).hasToString("component/ha/followers");
    }

    @Test
    void refusesReservedAndInvalidLabelNames() {
        assertThatThrownBy(() -> ProbeScope.builder().type("t").name("n").label(AlertLabel.SOURCE.getLabelName(), "x").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reserved");
        assertThatThrownBy(() -> ProbeScope.builder().type("t").name("n").label("not-valid", "x").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must match");
    }

    @Test
    void anEventCarriesTheProbeScopeAndAlertLabels() {
        ProbeScope scope = ProbeScope.builder().type("component").name("ha").label("component", "ha").build();
        AlertEvent event = AlertEvent.builder()
                .type(AlertEvent.Type.RAISED)
                .source("engine-1")
                .probeId(ProbeId.of(scope, "followers"))
                .probeDescription("d")
                .probeLabel("team", "fix")
                .alert(Alert.major("no follower"))
                .since(Instant.EPOCH)
                .time(Instant.EPOCH)
                .build();

        assertThat(event.getLabels()).containsExactly(
                entry("team", "fix"), entry("component", "ha"),
                entry("alertname", "followers"), entry("source", "engine-1"), entry("severity", "major"));
    }
}
