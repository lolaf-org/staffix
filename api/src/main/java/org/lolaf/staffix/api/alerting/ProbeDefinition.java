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

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Singular;
import lombok.Value;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * What a probe is, without its evaluator: the part an admin tool shows, which changes only when the probe is added or
 * removed.
 */
@Value
@Builder
public class ProbeDefinition {

    /**
     * Identifies the probe within its session, and keys its {@link ProbeState}.
     */
    @NonNull
    String name;
    /**
     * What the probe watches, for an operator reading its alerts.
     */
    @NonNull
    String description;
    /**
     * How often the probe is evaluated, so a tool can tell a stale state from a slow probe.
     */
    @NonNull
    Duration evaluationInterval;
    /**
     * How often an ongoing alert is published again; {@code null} for a probe that publishes transitions only.
     */
    @Getter(AccessLevel.NONE)
    Duration reminderInterval;
    /**
     * The probe's {@link Probe#getLabels() labels}, carried on every alert for routing and grouping.
     */
    @Singular
    Map<String, String> labels;
    /**
     * Where an operator reads what to do about the probe's alerts; {@code null} when there is none.
     */
    @Getter(AccessLevel.NONE)
    URI runbookUrl;
    /**
     * See {@link Probe#isOnlyWhileLoggedIn()}; shown so a tool does not read a suspended probe as stale.
     */
    @Builder.Default
    boolean onlyWhileLoggedIn = true;

    /**
     * The definition of a probe as it was added; a probe never changes, so neither does its definition.
     */
    public static ProbeDefinition of(Probe probe) {
        return ProbeDefinition.builder()
                .name(probe.getName())
                .description(probe.getDescription())
                .evaluationInterval(probe.getEvaluationInterval())
                .reminderInterval(probe.getReminderInterval())
                .labels(probe.getLabels())
                .runbookUrl(probe.getRunbookUrl())
                .onlyWhileLoggedIn(probe.isOnlyWhileLoggedIn())
                .build();
    }

    /**
     * Empty for a probe that publishes transitions only.
     */
    public Optional<Duration> getReminderInterval() {
        return Optional.ofNullable(reminderInterval);
    }

    /**
     * Empty for a probe without a runbook.
     */
    public Optional<URI> getRunbookUrl() {
        return Optional.ofNullable(runbookUrl);
    }
}
