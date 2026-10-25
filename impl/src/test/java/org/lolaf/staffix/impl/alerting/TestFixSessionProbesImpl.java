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
package org.lolaf.staffix.impl.alerting;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.alerting.AlertLevel;
import org.lolaf.staffix.api.alerting.AlertingCounter;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.ProbeScope;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestFixSessionProbesImpl {

    private final FixSessionProbesImpl probes = new FixSessionProbesImpl(ProbeScope.builder().type("session").name("orders").label("desk", "fx").build());

    @Test
    void listsProbesInTheOrderTheyWereAdded() {
        probes.add(probe("b"));
        probes.add(probe("a"));

        assertThat(probes.list()).extracting(Probe::getName).containsExactly("b", "a");
    }

    @Test
    void rejectsADuplicateName() {
        probes.add(probe("a"));

        assertThatThrownBy(() -> probes.add(probe("a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a");
        assertThat(probes.list()).hasSize(1);
    }

    @Test
    void rejectsAProbeLabelItsScopeAlreadyHas() {
        Probe clashing = Probe.builder().name("a").description("a").evaluator(Optional::empty).label("desk", "eq").build();

        assertThatThrownBy(() -> probes.add(clashing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("desk");
        assertThat(probes.list()).isEmpty();
    }

    @Test
    void removesByName() {
        probes.add(probe("a"));
        probes.add(probe("b"));

        assertThat(probes.remove("a")).isTrue();
        assertThat(probes.remove("a")).isFalse();
        assertThat(probes.list()).extracting(Probe::getName).containsExactly("b");
    }

    @Test
    void watchAddsAProbeOnTheMeterAndReturnsIt() {
        AlertingCounter orders = AlertingCounter.builder().window(Duration.ofMinutes(1)).threshold(AlertLevel.WARNING, 1).build();

        AlertingCounter watched = probes.watch("order-rate", "Orders over the last minute", orders);

        assertThat(watched).isSameAs(orders);
        assertThat(probes.list()).singleElement().satisfies(probe -> {
            assertThat(probe.getName()).isEqualTo("order-rate");
            assertThat(probe.getDescription()).isEqualTo("Orders over the last minute");
            assertThat(probe.getEvaluator()).isSameAs(orders);
        });
        assertThatThrownBy(() -> probes.watch("order-rate", "again", orders)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSnapshotIsUnaffectedByLaterChanges() {
        probes.add(probe("a"));
        var snapshot = probes.list();

        probes.add(probe("b"));
        probes.remove("a");

        assertThat(snapshot).extracting(Probe::getName).containsExactly("a");
        assertThatThrownBy(() -> snapshot.add(probe("c"))).isInstanceOf(UnsupportedOperationException.class);
    }

    private static Probe probe(String name) {
        return Probe.builder().name(name).description(name).evaluator(Optional::empty).build();
    }
}
