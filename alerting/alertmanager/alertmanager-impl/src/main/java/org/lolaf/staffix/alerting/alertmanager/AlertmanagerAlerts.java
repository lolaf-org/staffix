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
package org.lolaf.staffix.alerting.alertmanager;

import lombok.Value;
import org.lolaf.staffix.api.alerting.AlertEvent;
import org.lolaf.staffix.api.session.FixSessionId;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The alerts learnt from events, as they are posted: the firing ones, and the resolved ones for
 * {@link #RESOLVED_RETENTION}, so a lost post or a restarted Alertmanager still learns they ended. Not thread safe.
 */
final class AlertmanagerAlerts {

    /**
     * Prometheus' factor and retention, so an alert behaves as it would coming from a Prometheus rule.
     */
    static final int ENDS_AT_RESEND_FACTOR = 4;
    static final Duration RESOLVED_RETENTION = Duration.ofMinutes(15);

    private final Map<String, String> staticLabels;
    private final Duration endsAtLead;
    private final Map<ProbeKey, PostableAlert> firing = new HashMap<>();
    private final Map<Map<String, String>, PostableAlert> resolved = new HashMap<>();

    AlertmanagerAlerts(Map<String, String> staticLabels, Duration resendInterval) {
        this.staticLabels = staticLabels;
        this.endsAtLead = resendInterval.multipliedBy(ENDS_AT_RESEND_FACTOR);
    }

    /**
     * Returns the alerts the events changed, to post now. A level change is a new alert to Alertmanager, since the
     * severity is a label, so the alert at the previous level is resolved alongside.
     */
    List<PostableAlert> apply(List<AlertEvent> events, Instant now) {
        Map<Map<String, String>, PostableAlert> changed = new LinkedHashMap<>();
        for (AlertEvent event : events) {
            ProbeKey key = ProbeKey.of(event);
            PostableAlert alert = toAlert(event);
            PostableAlert previous;
            if (event.getType() == AlertEvent.Type.CLEARED) {
                previous = firing.remove(key);
                resolve(alert, event.getTime(), changed);
            } else {
                previous = firing.put(key, alert);
                resolved.remove(alert.getLabels());
                changed.put(alert.getLabels(), alert);
            }
            if (previous != null && !previous.getLabels().equals(alert.getLabels())) {
                resolve(previous, event.getTime(), changed);
            }
        }
        return withFiringEndsAt(changed.values(), now);
    }

    /**
     * Returns every alert to post on a resend, dropping the resolved ones past their retention.
     */
    List<PostableAlert> all(Instant now) {
        Instant retainedSince = now.minus(RESOLVED_RETENTION);
        Iterator<PostableAlert> iterator = resolved.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getEndsAt().isBefore(retainedSince)) {
                iterator.remove();
            }
        }
        List<PostableAlert> alerts = new ArrayList<>(firing.values());
        alerts.addAll(resolved.values());
        return withFiringEndsAt(alerts, now);
    }

    private void resolve(PostableAlert alert, Instant endsAt, Map<Map<String, String>, PostableAlert> changed) {
        PostableAlert ended = alert.endingAt(endsAt);
        resolved.put(ended.getLabels(), ended);
        changed.put(ended.getLabels(), ended);
    }

    private List<PostableAlert> withFiringEndsAt(Collection<PostableAlert> alerts, Instant now) {
        Instant firingEndsAt = now.plus(endsAtLead);
        List<PostableAlert> posted = new ArrayList<>(alerts.size());
        for (PostableAlert alert : alerts) {
            posted.add(alert.getEndsAt() == null ? alert.endingAt(firingEndsAt) : alert);
        }
        return posted;
    }

    private PostableAlert toAlert(AlertEvent event) {
        Map<String, String> labels = new LinkedHashMap<>(staticLabels);
        labels.put("alertname", event.getProbeName());
        labels.put("engine", event.getEngineId());
        labels.put("fixSessionGroup", event.getFixSessionId().getGroup());
        labels.put("fixSessionName", event.getFixSessionId().getName());
        labels.put("severity", event.getAlert().getLevel().name().toLowerCase(Locale.ROOT));
        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put("summary", event.getAlert().getMessage());
        annotations.put("description", event.getProbeDescription());
        return new PostableAlert(Collections.unmodifiableMap(labels), Collections.unmodifiableMap(annotations),
                event.getSince(), null);
    }

    @Value
    private static class ProbeKey {
        String engineId;
        FixSessionId fixSessionId;
        String probeName;

        static ProbeKey of(AlertEvent event) {
            return new ProbeKey(event.getEngineId(), event.getFixSessionId(), event.getProbeName());
        }
    }
}
