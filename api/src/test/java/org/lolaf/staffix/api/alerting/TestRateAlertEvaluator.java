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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRateAlertEvaluator {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Map<AlertLevel, Integer> THRESHOLDS = Map.of(AlertLevel.WARNING, 5, AlertLevel.MAJOR, 50);

    private final AtomicLong nanos = new AtomicLong(-7_300_000_000L);
    private final RateAlertEvaluator evaluator = new RateAlertEvaluator(MINUTE, THRESHOLDS, nanos::get);

    private void record(int times) {
        for (int i = 0; i < times; i++) {
            evaluator.record();
        }
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    @Test
    void isNormalBelowTheLowestThreshold() {
        record(4);

        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void raisesTheHighestLevelReached() {
        record(5);
        assertThat(evaluator.evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.WARNING);
            assertThat(alert.getMessage()).isEqualTo("5 in the last 1m, WARNING from 5");
        });

        record(45);
        assertThat(evaluator.evaluate()).hasValueSatisfying(alert -> assertThat(alert.getLevel()).isEqualTo(AlertLevel.MAJOR));
    }

    @Test
    void countsEventsSpreadOverTheWindow() {
        for (int i = 0; i < 5; i++) {
            evaluator.record();
            advance(Duration.ofSeconds(10));
        }

        assertThat(evaluator.evaluate()).isPresent();
    }

    @Test
    void clearsOnceTheBurstAgesOutOfTheWindow() {
        record(5);
        advance(Duration.ofSeconds(58));
        assertThat(evaluator.evaluate()).isPresent();

        advance(Duration.ofSeconds(2));
        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void aStaleBucketRestartsInsteadOfAddingToOldEvents() {
        record(4);
        advance(MINUTE);
        record(1);

        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void recordsSeveralEventsAtOnce() {
        evaluator.record(3);
        evaluator.record(0);
        evaluator.record();
        assertThat(evaluator.evaluate()).isEmpty();

        evaluator.record(46);
        assertThat(evaluator.evaluate()).hasValueSatisfying(alert -> assertThat(alert.getMessage()).isEqualTo("50 in the last 1m, MAJOR from 50"));
    }

    @Test
    void refusesANegativeEventCount() {
        assertThatThrownBy(() -> evaluator.record(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resetForgetsTheCountedEvents() {
        record(50);

        evaluator.reset();

        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void clearForgetsTheCountedEvents() {
        record(50);

        assertThat(evaluator.clear()).isTrue();
        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void usesAboutSixtyBucketsForALongWindow() {
        RateAlertEvaluator hourly = new RateAlertEvaluator(Duration.ofHours(1), Map.of(AlertLevel.WARNING, 2), nanos::get);
        hourly.record();
        advance(Duration.ofMinutes(50));
        hourly.record();

        assertThat(hourly.evaluate()).hasValueSatisfying(alert -> assertThat(alert.getMessage()).startsWith("2 in the last 1h"));
    }

    @Test
    void losesNoEventRecordedConcurrently() throws InterruptedException {
        RateAlertEvaluator counter = new RateAlertEvaluator(MINUTE, Map.of(AlertLevel.WARNING, 40_000), nanos::get);
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 10_000; i++) {
                    counter.record();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(counter.evaluate()).hasValueSatisfying(alert -> assertThat(alert.getMessage()).startsWith("40000 in"));
    }

    @Test
    void refusesAWindowUnderASecondAndMissingOrNonPositiveThresholds() {
        assertThatThrownBy(() -> RateAlertEvaluator.builder().window(Duration.ofMillis(500)).threshold(AlertLevel.WARNING, 1).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RateAlertEvaluator.builder().window(MINUTE).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RateAlertEvaluator.builder().window(MINUTE).threshold(AlertLevel.WARNING, 0).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
