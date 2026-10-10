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
import org.lolaf.staffix.api.alerting.Probe;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestFixSessionProbesImpl {

    private final FixSessionProbesImpl probes = new FixSessionProbesImpl();

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
    void removesByName() {
        probes.add(probe("a"));
        probes.add(probe("b"));

        assertThat(probes.remove("a")).isTrue();
        assertThat(probes.remove("a")).isFalse();
        assertThat(probes.list()).extracting(Probe::getName).containsExactly("b");
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
