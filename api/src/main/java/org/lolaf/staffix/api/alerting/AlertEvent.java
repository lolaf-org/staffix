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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A change in the alert state of a probe, as handed to a {@link FixAlertsPublisher}. A plain value, so a publisher can
 * queue or forward it.
 */
@Value
@Builder
public class AlertEvent {

    @NonNull
    Type type;
    /**
     * Who evaluated the probe, so a system collecting alerts from several sources can tell them apart: a probe id is
     * only unique within its source. Such as an engine instance id.
     */
    @NonNull
    String source;
    @NonNull
    ProbeId probeId;
    /**
     * The {@link Probe#getDescription() description} of the probe, carried so a publisher needs nothing else.
     */
    @NonNull
    String probeDescription;
    /**
     * The probe's {@link Probe#getLabels() labels}, empty when it has none.
     */
    @Singular
    Map<String, String> probeLabels;
    @Getter(AccessLevel.NONE)
    URI probeRunbookUrl;
    /**
     * The alert as it stands; for {@link Type#CLEARED}, the last one before the probe went back to normal.
     */
    @NonNull
    Alert alert;
    /**
     * When the alert was first raised, kept across level changes.
     */
    @NonNull
    Instant since;
    /**
     * When the evaluation that produced this event ran.
     */
    @NonNull
    Instant time;
    /**
     * Whether an operator cleared it from the admin, for a {@link Type#CLEARED}; always false otherwise.
     */
    boolean clearedManually;

    /**
     * The probe's {@link Probe#getRunbookUrl() runbook}, empty when it has none.
     */
    public Optional<URI> getProbeRunbookUrl() {
        return Optional.ofNullable(probeRunbookUrl);
    }

    /**
     * Every label of the alert: the probe's, its scope's and the {@link AlertLabel}s, in that order.
     */
    public Map<String, String> getLabels() {
        Map<String, String> labels = new LinkedHashMap<>(probeLabels);
        labels.putAll(probeId.getScope().getLabels());
        for (AlertLabel label : AlertLabel.values()) {
            labels.put(label.getLabelName(), label.valueFor(this));
        }
        return labels;
    }

    /**
     * What changed in the alert state of the probe.
     */
    public enum Type {
        /**
         * The probe went from normal to an alert.
         */
        RAISED,
        /**
         * The level changed; a change of message alone is not an event.
         */
        CHANGED,
        /**
         * Still ongoing, published again because the probe has a reminder interval.
         */
        REMINDED,
        /**
         * The probe went back to normal.
         */
        CLEARED
    }
}
