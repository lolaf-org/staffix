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
import org.lolaf.staffix.api.session.FixSessionStatus;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Watches one thing of its {@link ProbeScope} and raises an alert while it is wrong. Immutable: changing a probe means
 * removing it and adding a new one.
 */
@Value
public class Probe {

    /**
     * The engine evaluates probes on a one-second tick, so no interval can be shorter.
     */
    public static final Duration MIN_INTERVAL = Duration.ofSeconds(1);

    /**
     * Identifies the probe within its scope.
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
    /**
     * Carried on every alert for routing and grouping, such as the owning team. Names follow Prometheus' label syntax,
     * since Alertmanager refuses a whole post over one invalid name, and cannot be an {@link AlertLabel} or a label of
     * the probe's {@link ProbeScope}.
     */
    Map<String, String> labels;
    /**
     * Where an operator reads what to do about this probe's alerts; {@code null} when there is none.
     */
    URI runbookUrl;
    /**
     * Evaluate the probe only while the session is {@link FixSessionStatus#LOGGED_IN}; {@code true} by default, since an
     * application probe usually watches business traffic, which stops outside a logon. Suspended in every other status,
     * {@link FixSessionStatus#LOGGED_OUT_INSIDE_SESSION_TIME} included: the built-in {@code session-down} probe alerts
     * then, and this probe would only report a consequence of it. Suspending clears an ongoing alert, resuming
     * {@link ProbeEvaluator#reset() resets} the evaluator. Set {@code false} for a probe whose subject outlives the
     * logon, such as a queue or a disk.
     */
    boolean onlyWhileLoggedIn;

    @Builder
    private Probe(@NonNull String name, @NonNull String description, @NonNull ProbeEvaluator evaluator,
                  Duration evaluationInterval, Duration reminderInterval, @Singular Map<String, String> labels, URI runbookUrl,
                  Boolean onlyWhileLoggedIn) {
        this.name = name;
        this.description = description;
        this.evaluator = evaluator;
        this.evaluationInterval = requireAtLeastMinInterval("evaluationInterval", evaluationInterval == null ? MIN_INTERVAL : evaluationInterval);
        this.reminderInterval = reminderInterval == null ? null : requireAtLeastMinInterval("reminderInterval", reminderInterval);
        this.labels = Collections.unmodifiableMap(new LinkedHashMap<>(Labels.requireValid(labels)));
        this.runbookUrl = runbookUrl;
        this.onlyWhileLoggedIn = onlyWhileLoggedIn == null || onlyWhileLoggedIn;
    }

    private static Duration requireAtLeastMinInterval(String name, Duration interval) {
        if (interval.compareTo(MIN_INTERVAL) < 0) {
            throw new IllegalArgumentException(name + " must be at least " + MIN_INTERVAL + ", was " + interval);
        }
        return interval;
    }
}
