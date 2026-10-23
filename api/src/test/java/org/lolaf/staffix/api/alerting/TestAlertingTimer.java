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
import org.lolaf.staffix.api.monitoring.Timer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAlertingTimer {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Map<AlertLevel, Duration> THRESHOLDS = Map.of(AlertLevel.WARNING, Duration.ofMillis(10),
            AlertLevel.MAJOR, Duration.ofMillis(100));

    private final List<Long> published = new ArrayList<>();
    private final Timer publishedTimer = new Timer() {
        @Override
        public void start() {
            throw new UnsupportedOperationException("the alerting timer measures itself");
        }

        @Override
        public void stop() {
            throw new UnsupportedOperationException("the alerting timer measures itself");
        }

        @Override
        public void record(long duration, TimeUnit unit) {
            published.add(unit.toNanos(duration));
        }
    };
    private final AtomicLong nanos = new AtomicLong(-7_300_000_000L);

    private AlertingTimer timer(Duration raiseDelay, Duration clearDelay) {
        return new AlertingTimer(publishedTimer, MINUTE, null, null, THRESHOLDS, raiseDelay, clearDelay, nanos::get);
    }

    private static void record(AlertingTimer timer, int times, long millis) {
        for (int i = 0; i < times; i++) {
            timer.record(millis, TimeUnit.MILLISECONDS);
        }
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    @Test
    void aShortSpikeStaysUnderTheShareAllowedAboveTheThreshold() {
        AlertingTimer timer = timer(null, null);
        record(timer, 95, 1);
        record(timer, 5, 500);

        assertThat(timer.evaluate()).isEmpty();
    }

    @Test
    void raisesOnceMoreThanTheAllowedShareIsSlower() {
        AlertingTimer timer = timer(null, null);
        record(timer, 93, 1);
        record(timer, 7, 20);

        assertThat(timer.evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.WARNING);
            assertThat(alert.getMessage()).isEqualTo("p95 above 10ms: 7% of 100 slower in the last 1m, WARNING");
        });
    }

    @Test
    void raisesTheHighestLevelWhosePercentileIsAboveItsThreshold() {
        AlertingTimer timer = timer(null, null);
        record(timer, 90, 1);
        record(timer, 10, 200);

        assertThat(timer.evaluate()).map(Alert::getLevel).contains(AlertLevel.MAJOR);
    }

    @Test
    void raisesNothingUnderTheMinimumRecordings() {
        AlertingTimer timer = timer(null, null);
        record(timer, 19, 500);
        assertThat(timer.evaluate()).isEmpty();

        record(timer, 1, 500);
        assertThat(timer.evaluate()).isPresent();
    }

    @Test
    void clearsOnceTheSlowRecordingsAgeOutOfTheWindow() {
        AlertingTimer timer = timer(null, null);
        record(timer, 20, 20);
        assertThat(timer.evaluate()).isPresent();

        advance(MINUTE);
        record(timer, 20, 1);
        assertThat(timer.evaluate()).isEmpty();
    }

    @Test
    void timesFromStartToStopAndPublishesEveryRecording() {
        AlertingTimer timer = timer(null, null);
        for (int i = 0; i < 20; i++) {
            timer.start();
            advance(Duration.ofMillis(20));
            timer.stop();
        }

        assertThat(published).hasSize(20).containsOnly(Duration.ofMillis(20).toNanos());
        assertThat(timer.evaluate()).isPresent();
    }

    @Test
    void honoursAConfiguredPercentileAndMinimum() {
        AlertingTimer p50 = new AlertingTimer(null, MINUTE, 0.5, 4, Map.of(AlertLevel.WARNING, Duration.ofMillis(10)),
                null, null, nanos::get);
        record(p50, 2, 1);
        record(p50, 2, 20);
        assertThat(p50.evaluate()).isEmpty();

        record(p50, 1, 20);
        assertThat(p50.evaluate()).map(Alert::getMessage).contains("p50 above 10ms: 60% of 5 slower in the last 1m, WARNING");
    }

    @Test
    void raisesOnlyOnceTheSlowdownHasHeldForTheRaiseDelay() {
        AlertingTimer timer = timer(Duration.ofSeconds(10), null);
        record(timer, 20, 20);

        assertThat(timer.evaluate()).isEmpty();
        advance(Duration.ofSeconds(10));
        assertThat(timer.evaluate()).isPresent();
    }

    @Test
    void keepsTheAlertForTheClearDelayOnceTheSlowdownHasAgedOut() {
        AlertingTimer timer = timer(null, Duration.ofSeconds(30));
        record(timer, 20, 20);
        assertThat(timer.evaluate()).isPresent();

        advance(MINUTE);
        assertThat(timer.evaluate()).isPresent();
        advance(Duration.ofSeconds(30));
        assertThat(timer.evaluate()).isEmpty();
    }

    @Test
    void clearForgetsTheRecordingsButNotThePublishedOnes() {
        AlertingTimer timer = timer(null, null);
        record(timer, 20, 20);

        assertThat(timer.clear()).isTrue();

        assertThat(timer.evaluate()).isEmpty();
        assertThat(published).hasSize(20);
    }

    @Test
    void resetForgetsTheRecordingsAndRestartsTheRaiseDelayButKeepsThePublishedOnes() {
        AlertingTimer timer = timer(Duration.ofSeconds(10), null);
        record(timer, 20, 20);
        timer.evaluate();
        advance(Duration.ofSeconds(10));
        assertThat(timer.evaluate()).isPresent();

        timer.reset();

        assertThat(timer.evaluate()).isEmpty();
        record(timer, 20, 20);
        assertThat(timer.evaluate()).isEmpty();
        assertThat(published).hasSize(40);
    }

    @Test
    void refusesAnInvalidPercentileMinimumOrThreshold() {
        assertThatThrownBy(() -> AlertingTimer.builder().window(MINUTE).percentile(1.0).threshold(AlertLevel.WARNING, Duration.ofMillis(1)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlertingTimer.builder().window(MINUTE).minRecordings(0).threshold(AlertLevel.WARNING, Duration.ofMillis(1)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlertingTimer.builder().window(MINUTE).threshold(AlertLevel.WARNING, Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlertingTimer.builder().window(MINUTE).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
