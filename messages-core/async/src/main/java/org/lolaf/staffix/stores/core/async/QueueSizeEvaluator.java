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
package org.lolaf.staffix.stores.core.async;

import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.ProbeEvaluator;
import org.lolaf.staffix.api.alerting.ThresholdAlertEvaluator;

import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * Raises the highest level whose threshold the queue depth has reached, and clears below the lowest threshold.
 */
public final class QueueSizeEvaluator implements ProbeEvaluator {

    private final ThresholdAlertEvaluator thresholds;

    QueueSizeEvaluator(IntSupplier queueSize, AsyncStoreSettings.QueueSizeThresholdsSettings settings) {
        this.thresholds = ThresholdAlertEvaluator.builder()
                .value(queueSize::getAsInt)
                .valueLabel("events queued")
                .aboveThresholds(settings.getThresholds())
                .build();
    }

    /**
     * The probe watching a queue, evaluated at the thresholds' evaluation interval.
     */
    public static Probe probe(String name, String description, PersistentQueue<?> queue, AsyncStoreSettings.QueueSizeThresholdsSettings thresholds) {
        return Probe.builder()
                .name(name)
                .description(description)
                .evaluator(new QueueSizeEvaluator(queue::getSize, thresholds))
                .evaluationInterval(thresholds.getEvaluationInterval())
                .build();
    }

    @Override
    public Optional<Alert> evaluate() {
        return thresholds.evaluate();
    }
}
