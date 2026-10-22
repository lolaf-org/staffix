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
import lombok.Value;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import java.util.stream.Stream;

/**
 * Raises the highest level whose threshold a value has reached, above or below: a queue too deep, a balance too low,
 * or both bounds of a band. Both bounds are inclusive, so a threshold is the first value that alerts.
 */
public final class ThresholdAlertEvaluator implements ProbeEvaluator {

    private final DoubleSupplier value;
    private final String valueLabel;
    private final Threshold[] highestFirst;

    /**
     * @param value           read at each evaluation, on the probes thread, so it must be thread-safe
     * @param valueLabel      follows the value in the alert message, such as {@code events queued}; none by default
     * @param aboveThresholds the value from which each level is raised as it goes up
     * @param belowThresholds the value from which each level is raised as it goes down
     */
    @Builder
    private ThresholdAlertEvaluator(@NonNull DoubleSupplier value, String valueLabel,
                                    @Singular("above") Map<AlertLevel, Number> aboveThresholds,
                                    @Singular("below") Map<AlertLevel, Number> belowThresholds) {
        if (aboveThresholds.isEmpty() && belowThresholds.isEmpty()) {
            throw new IllegalArgumentException("Threshold evaluator needs at least one threshold");
        }
        this.value = value;
        this.valueLabel = valueLabel == null ? "" : " " + valueLabel;
        this.highestFirst = Stream.concat(
                        aboveThresholds.entrySet().stream().map(threshold -> new Threshold(threshold.getKey(), threshold.getValue().doubleValue(), false)),
                        belowThresholds.entrySet().stream().map(threshold -> new Threshold(threshold.getKey(), threshold.getValue().doubleValue(), true)))
                .sorted(Comparator.comparing(Threshold::getLevel).reversed())
                .toArray(Threshold[]::new);
    }

    @Override
    public Optional<Alert> evaluate() {
        double current = value.getAsDouble();
        for (Threshold threshold : highestFirst) {
            if (threshold.isReachedBy(current)) {
                return Optional.of(new Alert(threshold.getLevel(), format(current) + valueLabel + ", " + threshold.describe()));
            }
        }
        return Optional.empty();
    }

    private static String format(double number) {
        return number == Math.rint(number) && Math.abs(number) < 1e15 ? Long.toString((long) number) : Double.toString(number);
    }

    @Value
    private static class Threshold {
        AlertLevel level;
        double value;
        boolean below;

        boolean isReachedBy(double current) {
            return below ? current <= value : current >= value;
        }

        String describe() {
            return below ? level + " at " + format(value) + " and below" : level + " from " + format(value);
        }
    }
}
