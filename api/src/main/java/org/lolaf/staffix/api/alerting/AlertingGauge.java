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
import lombok.Singular;
import org.lolaf.staffix.api.monitoring.Gauge;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A {@link Gauge} that is also the evaluator of a probe on its value, so what the application sets is published and
 * alerted on from one call. Alerts as a {@link ThresholdAlertEvaluator} with the same thresholds would.
 *
 * <p>Two threads setting it at once may leave the published value and the alerted one apart until the next write.
 * For a gauge read from a supplier, use a {@link ThresholdAlertEvaluator} on that supplier instead.
 */
public final class AlertingGauge implements Gauge, ProbeEvaluator {

    private final Gauge gauge;
    private final AtomicLong valueBits;
    private final ProbeEvaluator evaluator;

    /**
     * @param gauge           the published gauge, such as one from the session's monitoring; without one the gauge only
     *                        alerts
     * @param initialValue    also set on the published gauge, so both start equal; 0 by default
     * @param valueLabel      follows the value in the alert message, such as {@code orders in flight}
     * @param aboveThresholds the value from which each level is raised as it goes up
     * @param belowThresholds the value from which each level is raised as it goes down
     * @param raiseDelay      see {@link ProbeEvaluator#withRaiseDelay(Duration)}; none by default
     * @param clearDelay      see {@link ProbeEvaluator#withClearDelay(Duration)}; none by default
     */
    @Builder
    private AlertingGauge(Gauge gauge, double initialValue, String valueLabel,
                          @Singular("above") Map<AlertLevel, Number> aboveThresholds,
                          @Singular("below") Map<AlertLevel, Number> belowThresholds,
                          Duration raiseDelay, Duration clearDelay) {
        this(gauge, initialValue, valueLabel, aboveThresholds, belowThresholds, raiseDelay, clearDelay, System::nanoTime);
    }

    AlertingGauge(Gauge gauge, double initialValue, String valueLabel, Map<AlertLevel, Number> aboveThresholds,
                  Map<AlertLevel, Number> belowThresholds, Duration raiseDelay, Duration clearDelay, LongSupplier nanoClock) {
        this.gauge = gauge == null ? Gauge.VoidGauge.getInstance() : gauge;
        this.valueBits = new AtomicLong(Double.doubleToRawLongBits(initialValue));
        this.gauge.set(initialValue);
        ThresholdAlertEvaluator thresholds = ThresholdAlertEvaluator.builder()
                .value(this::value)
                .valueLabel(valueLabel)
                .aboveThresholds(aboveThresholds)
                .belowThresholds(belowThresholds)
                .build();
        this.evaluator = DelayedEvaluators.withDelays(thresholds, raiseDelay, clearDelay, nanoClock);
    }

    @Override
    public void set(double value) {
        valueBits.set(Double.doubleToRawLongBits(value));
        gauge.set(value);
    }

    @Override
    public void add(double delta) {
        long current;
        do {
            current = valueBits.get();
        } while (!valueBits.compareAndSet(current, Double.doubleToRawLongBits(Double.longBitsToDouble(current) + delta)));
        gauge.add(delta);
    }

    @Override
    public Optional<Alert> evaluate() {
        return evaluator.evaluate();
    }

    /**
     * Refused, as for any polled value, unless a clear delay is holding the alert.
     */
    @Override
    public boolean clear() {
        return evaluator.clear();
    }

    private double value() {
        return Double.longBitsToDouble(valueBits.get());
    }
}
