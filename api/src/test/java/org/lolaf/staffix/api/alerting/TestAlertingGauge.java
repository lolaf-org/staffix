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
import org.lolaf.staffix.api.monitoring.Gauge;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TestAlertingGauge {

    private final double[] published = new double[1];
    private final Gauge publishedGauge = new Gauge() {
        @Override
        public void set(double value) {
            published[0] = value;
        }

        @Override
        public void add(double delta) {
            published[0] += delta;
        }
    };
    private final AtomicLong nanos = new AtomicLong(-7_300_000_000L);

    private AlertingGauge gauge(Map<AlertLevel, Number> above, Map<AlertLevel, Number> below, Duration raiseDelay, Duration clearDelay) {
        return new AlertingGauge(publishedGauge, 0, "orders in flight", above, below, raiseDelay, clearDelay, nanos::get);
    }

    private void advance(int seconds) {
        nanos.addAndGet(Duration.ofSeconds(seconds).toNanos());
    }

    @Test
    void publishesAndAlertsOnTheSameValue() {
        AlertingGauge gauge = gauge(Map.of(AlertLevel.WARNING, 10), Map.of(), null, null);
        gauge.set(8);
        assertThat(gauge.evaluate()).isEmpty();

        gauge.add(3);

        assertThat(published[0]).isEqualTo(11);
        assertThat(gauge.evaluate()).map(Alert::getMessage).contains("11 orders in flight, WARNING from 10");
    }

    @Test
    void alertsBelowAThreshold() {
        AlertingGauge gauge = gauge(Map.of(), Map.of(AlertLevel.MAJOR, 2), null, null);
        gauge.set(5);
        assertThat(gauge.evaluate()).isEmpty();

        gauge.add(-3);

        assertThat(gauge.evaluate()).map(Alert::getLevel).contains(AlertLevel.MAJOR);
    }

    @Test
    void alertsOutsideABand() {
        AlertingGauge gauge = gauge(Map.of(AlertLevel.MAJOR, 90), Map.of(AlertLevel.WARNING, 10), null, null);
        gauge.set(50);
        assertThat(gauge.evaluate()).isEmpty();

        gauge.set(5);
        assertThat(gauge.evaluate()).map(Alert::getLevel).contains(AlertLevel.WARNING);
        gauge.set(95);
        assertThat(gauge.evaluate()).map(Alert::getLevel).contains(AlertLevel.MAJOR);
    }

    @Test
    void startsBothValuesAtTheInitialValue() {
        AlertingGauge gauge = new AlertingGauge(publishedGauge, 50, null, Map.of(), Map.of(AlertLevel.WARNING, 10),
                null, null, nanos::get);

        assertThat(published[0]).isEqualTo(50);
        assertThat(gauge.evaluate()).isEmpty();
    }

    @Test
    void raisesOnlyOnceTheValueHasHeldForTheRaiseDelay() {
        AlertingGauge gauge = gauge(Map.of(AlertLevel.WARNING, 10), Map.of(), Duration.ofSeconds(30), null);
        gauge.set(12);

        assertThat(gauge.evaluate()).isEmpty();
        advance(29);
        assertThat(gauge.evaluate()).isEmpty();
        advance(1);
        assertThat(gauge.evaluate()).isPresent();
    }

    @Test
    void keepsTheAlertForTheClearDelayOnceTheValueIsBack() {
        AlertingGauge gauge = gauge(Map.of(AlertLevel.WARNING, 10), Map.of(), null, Duration.ofSeconds(30));
        gauge.set(12);
        assertThat(gauge.evaluate()).isPresent();

        gauge.set(5);
        assertThat(gauge.evaluate()).isPresent();
        advance(30);
        assertThat(gauge.evaluate()).isEmpty();
    }

    @Test
    void resetEndsAClearDelayHoldButKeepsTheValue() {
        AlertingGauge gauge = gauge(Map.of(AlertLevel.WARNING, 10), Map.of(), null, Duration.ofSeconds(30));
        gauge.set(12);
        gauge.evaluate();
        gauge.set(5);
        assertThat(gauge.evaluate()).isPresent();

        gauge.reset();

        assertThat(gauge.evaluate()).isEmpty();
        assertThat(published[0]).isEqualTo(5);
    }

    @Test
    void alertsWithoutAPublishedGauge() {
        AlertingGauge gauge = AlertingGauge.builder().above(AlertLevel.WARNING, 1).build();

        gauge.add(1);

        assertThat(gauge.evaluate()).isPresent();
    }
}
