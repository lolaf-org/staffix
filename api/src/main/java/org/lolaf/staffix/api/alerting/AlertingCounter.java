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

import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import org.lolaf.staffix.api.monitoring.Counter;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * A {@link Counter} that is also the evaluator of a probe on its rate, so what the application counts is published and
 * alerted on from one call. Alerts as a {@link RateAlertEvaluator} with the same window and thresholds would.
 */
public final class AlertingCounter implements Counter, ProbeEvaluator {

    private final Counter counter;
    private final RateAlertEvaluator rate;
    private final ProbeEvaluator evaluator;

    /**
     * @param counter    the published counter, such as one from the session's monitoring; without one the counter only
     *                   alerts
     * @param window     how far back increments are counted, at least a second
     * @param thresholds the count from which each level is raised, at least one
     * @param raiseDelay see {@link ProbeEvaluator#withRaiseDelay(Duration)}; none by default
     * @param clearDelay see {@link ProbeEvaluator#withClearDelay(Duration)}; none by default
     */
    @Builder
    private AlertingCounter(Counter counter, @NonNull Duration window, @Singular Map<AlertLevel, Integer> thresholds,
                            Duration raiseDelay, Duration clearDelay) {
        this(counter, window, thresholds, raiseDelay, clearDelay, System::nanoTime);
    }

    AlertingCounter(Counter counter, Duration window, Map<AlertLevel, Integer> thresholds, Duration raiseDelay,
                    Duration clearDelay, LongSupplier nanoClock) {
        this.counter = counter == null ? Counter.VoidCounter.getInstance() : counter;
        this.rate = new RateAlertEvaluator(window, thresholds, nanoClock);
        this.evaluator = withDelays(rate, raiseDelay, clearDelay, nanoClock);
    }

    private static ProbeEvaluator withDelays(ProbeEvaluator evaluator, Duration raiseDelay, Duration clearDelay, LongSupplier nanoClock) {
        ProbeEvaluator delayed = evaluator;
        if (raiseDelay != null && !raiseDelay.isZero()) {
            delayed = new RaiseDelayEvaluator(delayed, raiseDelay, nanoClock);
        }
        if (clearDelay != null && !clearDelay.isZero()) {
            delayed = new ClearDelayEvaluator(delayed, clearDelay, nanoClock);
        }
        return delayed;
    }

    @Override
    public void increment() {
        rate.record();
        counter.increment();
    }

    /**
     * @throws IllegalArgumentException if {@code amount} is negative, before anything is counted
     */
    @Override
    public void increment(long amount) {
        rate.record(amount);
        counter.increment(amount);
    }

    @Override
    public Optional<Alert> evaluate() {
        return evaluator.evaluate();
    }

    /**
     * Forgets the increments counted for alerting; the published counter keeps its total.
     */
    @Override
    public boolean clear() {
        return evaluator.clear();
    }
}
