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
import org.lolaf.staffix.api.monitoring.Counter;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAlertingCounter {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Map<AlertLevel, Integer> THRESHOLDS = Map.of(AlertLevel.WARNING, 5, AlertLevel.MAJOR, 50);

    private final AtomicLong published = new AtomicLong();
    private final Counter publishedCounter = new Counter() {
        @Override
        public void increment() {
            published.incrementAndGet();
        }

        @Override
        public void increment(long amount) {
            published.addAndGet(amount);
        }
    };
    private final AtomicLong nanos = new AtomicLong(-7_300_000_000L);

    private AlertingCounter counter(Duration raiseDelay, Duration clearDelay) {
        return new AlertingCounter(publishedCounter, MINUTE, THRESHOLDS, raiseDelay, clearDelay, nanos::get);
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    @Test
    void publishesAndAlertsOnTheSameIncrements() {
        AlertingCounter counter = counter(null, null);
        counter.increment();
        counter.increment(3);
        assertThat(counter.evaluate()).isEmpty();

        counter.increment();

        assertThat(published).hasValue(5);
        assertThat(counter.evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.WARNING);
            assertThat(alert.getMessage()).isEqualTo("5 in the last 1m, WARNING from 5");
        });
    }

    @Test
    void refusesANegativeAmountWithoutPublishingIt() {
        AlertingCounter counter = counter(null, null);

        assertThatThrownBy(() -> counter.increment(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(published).hasValue(0);
    }

    @Test
    void alertsWithoutAPublishedCounter() {
        AlertingCounter counter = AlertingCounter.builder().window(MINUTE).threshold(AlertLevel.WARNING, 1).build();

        counter.increment();

        assertThat(counter.evaluate()).isPresent();
    }

    @Test
    void raisesOnlyOnceTheRateHasHeldForTheRaiseDelay() {
        AlertingCounter counter = counter(Duration.ofSeconds(10), null);
        counter.increment(5);

        assertThat(counter.evaluate()).isEmpty();
        advance(Duration.ofSeconds(10));
        assertThat(counter.evaluate()).isPresent();
    }

    @Test
    void keepsTheAlertForTheClearDelayOnceTheBurstHasAgedOut() {
        AlertingCounter counter = counter(null, Duration.ofSeconds(30));
        counter.increment(5);
        assertThat(counter.evaluate()).isPresent();

        advance(MINUTE);
        assertThat(counter.evaluate()).isPresent();
        advance(Duration.ofSeconds(30));
        assertThat(counter.evaluate()).isEmpty();
    }

    @Test
    void clearForgetsTheCountedIncrementsButNotThePublishedTotal() {
        AlertingCounter counter = counter(null, null);
        counter.increment(50);

        assertThat(counter.clear()).isTrue();

        assertThat(counter.evaluate()).isEmpty();
        assertThat(published).hasValue(50);
    }
}
