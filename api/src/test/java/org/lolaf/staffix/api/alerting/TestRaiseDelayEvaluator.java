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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRaiseDelayEvaluator {

    private static final Optional<Alert> WARNING = Optional.of(Alert.warning("slow"));
    private static final Optional<Alert> MAJOR = Optional.of(Alert.major("very slow"));

    private final AtomicReference<Optional<Alert>> condition = new AtomicReference<>(Optional.empty());
    private final AtomicLong nanos = new AtomicLong(-3_000_000_000L);
    private final RaiseDelayEvaluator evaluator = new RaiseDelayEvaluator(condition::get, Duration.ofSeconds(10), nanos::get);

    private void advance(int seconds) {
        nanos.addAndGet(Duration.ofSeconds(seconds).toNanos());
    }

    @Test
    void raisesOnceTheConditionHasHeldForTheDelay() {
        condition.set(WARNING);

        assertThat(evaluator.evaluate()).isEmpty();
        advance(9);
        assertThat(evaluator.evaluate()).isEmpty();
        advance(1);
        assertThat(evaluator.evaluate()).isEqualTo(WARNING);
    }

    @Test
    void aConditionShorterThanTheDelayNeverRaisesAndRestartsTheDelay() {
        condition.set(WARNING);
        evaluator.evaluate();
        advance(9);
        condition.set(Optional.empty());
        assertThat(evaluator.evaluate()).isEmpty();

        condition.set(WARNING);
        evaluator.evaluate();
        advance(9);
        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void onlyTheFirstRaiseWaits() {
        condition.set(WARNING);
        evaluator.evaluate();
        advance(10);
        evaluator.evaluate();

        condition.set(MAJOR);
        assertThat(evaluator.evaluate()).isEqualTo(MAJOR);
        condition.set(Optional.empty());
        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void clearIsTheWrappedEvaluatorsDecision() {
        RaisedAlertEvaluator raised = new RaisedAlertEvaluator();

        assertThat(evaluator.clear()).isFalse();
        assertThat(raised.withRaiseDelay(Duration.ofSeconds(1)).clear()).isTrue();
    }

    @Test
    void aZeroDelayIsNoWrapperAndANegativeOneIsRefused() {
        ProbeEvaluator healthy = Optional::empty;

        assertThat(healthy.withRaiseDelay(Duration.ZERO)).isSameAs(healthy);
        assertThatThrownBy(() -> healthy.withRaiseDelay(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
