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
import lombok.Value;

import java.time.Duration;

/**
 * Watches one thing of a FIX session and raises an alert while it is wrong. Immutable: changing a probe means removing
 * it and adding a new one.
 */
@Value
public class Probe {

    /**
     * The engine evaluates probes on a one-second tick, so no interval can be shorter.
     */
    public static final Duration MIN_INTERVAL = Duration.ofSeconds(1);

    /**
     * Identifies the probe within its session.
     */
    String name;
    /**
     * What the probe watches and why it matters, for an operator reading the alert.
     */
    String description;
    /**
     * Run at every {@link #evaluationInterval}; see {@link ProbeEvaluator} for its threading contract.
     */
    ProbeEvaluator evaluator;
    /**
     * How often the evaluator runs. Rounded up to whole seconds, since it runs on the first tick at or after it is due.
     */
    Duration evaluationInterval;
    /**
     * How often an ongoing alert is published again; {@code null}, the default, publishes transitions only.
     */
    Duration reminderInterval;

    @Builder
    private Probe(@NonNull String name, @NonNull String description, @NonNull ProbeEvaluator evaluator,
                  Duration evaluationInterval, Duration reminderInterval) {
        this.name = name;
        this.description = description;
        this.evaluator = evaluator;
        this.evaluationInterval = requireAtLeastMinInterval("evaluationInterval", evaluationInterval == null ? MIN_INTERVAL : evaluationInterval);
        this.reminderInterval = reminderInterval == null ? null : requireAtLeastMinInterval("reminderInterval", reminderInterval);
    }

    private static Duration requireAtLeastMinInterval(String name, Duration interval) {
        if (interval.compareTo(MIN_INTERVAL) < 0) {
            throw new IllegalArgumentException(name + " must be at least " + MIN_INTERVAL + ", was " + interval);
        }
        return interval;
    }
}
