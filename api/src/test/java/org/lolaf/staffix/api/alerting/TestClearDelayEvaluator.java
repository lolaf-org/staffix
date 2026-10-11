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

class TestClearDelayEvaluator {

    private static final Optional<Alert> WARNING = Optional.of(Alert.warning("slow"));
    private static final Optional<Alert> MAJOR = Optional.of(Alert.major("very slow"));

    private final AtomicReference<Optional<Alert>> condition = new AtomicReference<>(Optional.empty());
    private final AtomicLong nanos = new AtomicLong(-3_000_000_000L);
    private final ClearDelayEvaluator evaluator = new ClearDelayEvaluator(condition::get, Duration.ofSeconds(10), nanos::get);

    private void advance(int seconds) {
        nanos.addAndGet(Duration.ofSeconds(seconds).toNanos());
    }

    @Test
    void raisesAtOnceAndClearsOnceNormalForTheDelay() {
        condition.set(WARNING);
        assertThat(evaluator.evaluate()).isEqualTo(WARNING);

        condition.set(Optional.empty());
        assertThat(evaluator.evaluate()).isEqualTo(WARNING);
        advance(9);
        assertThat(evaluator.evaluate()).isEqualTo(WARNING);
        advance(1);
        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void aRaiseWithinTheDelayKeepsTheAlertAndRestartsTheDelay() {
        condition.set(WARNING);
        evaluator.evaluate();
        condition.set(Optional.empty());
        evaluator.evaluate();
        advance(9);

        condition.set(WARNING);
        assertThat(evaluator.evaluate()).isEqualTo(WARNING);
        condition.set(Optional.empty());
        evaluator.evaluate();
        advance(9);
        assertThat(evaluator.evaluate()).isEqualTo(WARNING);
    }

    @Test
    void levelChangesPassAtOnceAndTheLastLevelIsHeld() {
        condition.set(WARNING);
        evaluator.evaluate();

        condition.set(MAJOR);
        assertThat(evaluator.evaluate()).isEqualTo(MAJOR);
        condition.set(WARNING);
        assertThat(evaluator.evaluate()).isEqualTo(WARNING);
        condition.set(Optional.empty());
        assertThat(evaluator.evaluate()).isEqualTo(WARNING);
    }

    @Test
    void aManualClearSkipsTheDelayWhenTheWrappedEvaluatorAcceptsIt() {
        RaisedAlertEvaluator raised = new RaisedAlertEvaluator();
        ClearDelayEvaluator delayed = new ClearDelayEvaluator(raised, Duration.ofSeconds(10), nanos::get);
        raised.raise(WARNING.get());
        delayed.evaluate();

        assertThat(delayed.clear()).isTrue();
        assertThat(delayed.evaluate()).isEmpty();
    }

    @Test
    void aManualClearEndsAHoldButNotAnActiveCondition() {
        condition.set(WARNING);
        evaluator.evaluate();
        assertThat(evaluator.clear()).isFalse();
        assertThat(evaluator.evaluate()).isEqualTo(WARNING);

        condition.set(Optional.empty());
        evaluator.evaluate();
        assertThat(evaluator.clear()).isTrue();
        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void aZeroDelayIsNoWrapperAndANegativeOneIsRefused() {
        ProbeEvaluator healthy = Optional::empty;

        assertThat(healthy.withClearDelay(Duration.ZERO)).isSameAs(healthy);
        assertThatThrownBy(() -> healthy.withClearDelay(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
