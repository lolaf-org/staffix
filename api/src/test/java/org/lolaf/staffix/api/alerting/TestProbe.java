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

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestProbe {

    private static final ProbeEvaluator HEALTHY = Optional::empty;

    @Test
    void defaultsToOneSecondEvaluationAndNoReminder() {
        Probe probe = Probe.builder().name("p").description("d").evaluator(HEALTHY).build();

        assertThat(probe.getEvaluationInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(probe.getReminderInterval()).isNull();
    }

    @Test
    void keepsGivenIntervals() {
        Probe probe = Probe.builder().name("p").description("d").evaluator(HEALTHY)
                .evaluationInterval(Duration.ofSeconds(10))
                .reminderInterval(Duration.ofMinutes(1))
                .build();

        assertThat(probe.getEvaluationInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(probe.getReminderInterval()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void rejectsIntervalsBelowOneSecond() {
        assertThatThrownBy(() -> Probe.builder().name("p").description("d").evaluator(HEALTHY)
                .evaluationInterval(Duration.ofMillis(500)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evaluationInterval");
        assertThatThrownBy(() -> Probe.builder().name("p").description("d").evaluator(HEALTHY)
                .reminderInterval(Duration.ofMillis(999)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reminderInterval");
    }

    @Test
    void requiresNameDescriptionAndEvaluator() {
        assertThatThrownBy(() -> Probe.builder().description("d").evaluator(HEALTHY).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Probe.builder().name("p").evaluator(HEALTHY).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Probe.builder().name("p").description("d").build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void levelsAreOrderedBySeverity() {
        assertThat(AlertLevel.values()).containsExactly(AlertLevel.WARNING, AlertLevel.MAJOR, AlertLevel.CRITICAL);
        assertThat(Alert.critical("x").getLevel()).isGreaterThan(Alert.major("x").getLevel());
    }
}
