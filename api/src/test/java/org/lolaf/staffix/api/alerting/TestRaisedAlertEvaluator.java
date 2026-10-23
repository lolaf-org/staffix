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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TestRaisedAlertEvaluator {

    private static final Alert WARNING = Alert.warning("1 rejected");
    private static final Alert MAJOR = Alert.major("10 rejected");

    private final AtomicLong nanos = new AtomicLong(-5_000_000_000L);
    private final RaisedAlertEvaluator evaluator = new RaisedAlertEvaluator(nanos::get);

    @Test
    void isNormalUntilRaised() {
        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void keepsARaisedAlertUntilCleared() {
        evaluator.raise(WARNING);

        assertThat(evaluator.evaluate()).contains(WARNING);
        nanos.addAndGet(Duration.ofDays(1).toNanos());
        assertThat(evaluator.evaluate()).contains(WARNING);
        assertThat(evaluator.clear()).isTrue();
        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void resetDropsTheRaisedAlertAndAnUnreportedRaise() {
        evaluator.raise(WARNING);
        assertThat(evaluator.evaluate()).contains(WARNING);
        evaluator.raise(MAJOR);
        evaluator.clear();

        evaluator.reset();

        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void theLatestRaiseWinsEvenAtALowerLevel() {
        evaluator.raise(MAJOR);
        evaluator.raise(WARNING);

        assertThat(evaluator.evaluate()).contains(WARNING);
    }

    @Test
    void anAlertExpiresUnlessRaisedAgain() {
        evaluator.raise(WARNING, Duration.ofSeconds(10));
        nanos.addAndGet(Duration.ofSeconds(9).toNanos());
        assertThat(evaluator.evaluate()).contains(WARNING);

        evaluator.raise(WARNING, Duration.ofSeconds(10));
        nanos.addAndGet(Duration.ofSeconds(9).toNanos());
        assertThat(evaluator.evaluate()).contains(WARNING);

        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void aRaiseClearedBeforeAnEvaluationIsStillReportedOnce() {
        evaluator.raise(MAJOR);
        evaluator.clear();

        assertThat(evaluator.evaluate()).contains(MAJOR);
        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void aRaiseExpiredBeforeAnEvaluationIsStillReportedOnce() {
        evaluator.raise(MAJOR, Duration.ofMillis(1));
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());

        assertThat(evaluator.evaluate()).contains(MAJOR);
        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void evaluatingAnUnchangedAlertReturnsTheSameResult() {
        evaluator.raise(WARNING);

        Optional<Alert> first = evaluator.evaluate();

        assertThat(evaluator.evaluate()).isSameAs(first);
    }

    @Test
    void aPolledEvaluatorRefusesToBeCleared() {
        ProbeEvaluator polled = Optional::empty;

        assertThat(polled.clear()).isFalse();
    }
}
