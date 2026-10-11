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
package org.lolaf.staffix.impl.alerting;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.AlertEvent;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.ProbeState;
import org.lolaf.staffix.api.session.FixSessionId;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The alert state of one probe, evaluated on the probes thread and read from any thread through {@link #state()}.
 * Intervals are counted in ticks rather than read off the clock, so a tick firing a few milliseconds early or late
 * never skips or doubles an evaluation. Nothing is allocated unless the alert changes.
 */
@Slf4j
final class ProbeEvaluation {

    private static final long NOT_EVALUATED = Long.MIN_VALUE;

    private final String engineId;
    private final FixSessionId fixSessionId;
    @Getter
    private final Probe probe;
    private final long evaluationTicks;
    private final long reminderTicks;
    private long nextEvaluationTick;
    private long lastPublishedTick;
    /**
     * Written before {@link #alert} on a raise, and left in place on a clear, so a reader that sees an alert always
     * sees its since.
     */
    private volatile Instant since;
    private volatile Alert alert;
    private volatile long lastEvaluationMillis = NOT_EVALUATED;

    ProbeEvaluation(String engineId, FixSessionId fixSessionId, Probe probe, long firstTick) {
        this.engineId = engineId;
        this.fixSessionId = fixSessionId;
        this.probe = probe;
        this.evaluationTicks = toTicks(probe.getEvaluationInterval());
        this.reminderTicks = probe.getReminderInterval() == null ? 0 : toTicks(probe.getReminderInterval());
        this.nextEvaluationTick = firstTick;
    }

    private static long toTicks(Duration interval) {
        long tickMillis = ProbesManager.TICK.toMillis();
        return (interval.toMillis() + tickMillis - 1) / tickMillis;
    }

    /**
     * Returns the event to publish, or null when the evaluation changed nothing worth publishing.
     */
    AlertEvent evaluateIfDue(long tick, long nowMillis) {
        if (tick < nextEvaluationTick) {
            return null;
        }
        nextEvaluationTick = tick + evaluationTicks;
        lastEvaluationMillis = nowMillis;
        Alert previous = alert;
        Alert next = evaluate().orElse(null);
        if (previous == null && next == null) {
            return null;
        }
        if (previous == null) {
            since = Instant.ofEpochMilli(nowMillis);
            alert = next;
            return published(AlertEvent.Type.RAISED, tick, nowMillis);
        }
        alert = next;
        if (next == null) {
            return event(AlertEvent.Type.CLEARED, previous, nowMillis);
        }
        if (next.getLevel() != previous.getLevel()) {
            return published(AlertEvent.Type.CHANGED, tick, nowMillis);
        }
        if (reminderTicks > 0 && tick - lastPublishedTick >= reminderTicks) {
            return published(AlertEvent.Type.REMINDED, tick, nowMillis);
        }
        return null;
    }

    /**
     * Returns the cleared event of an ongoing alert, for a probe that is no longer evaluated, or null without one.
     */
    AlertEvent clear(long nowMillis) {
        Alert last = alert;
        if (last == null) {
            return null;
        }
        alert = null;
        return event(AlertEvent.Type.CLEARED, last, nowMillis);
    }

    boolean isInAlert() {
        return alert != null;
    }

    /**
     * Null until the first evaluation.
     */
    ProbeState state() {
        long lastEvaluation = lastEvaluationMillis;
        if (lastEvaluation == NOT_EVALUATED) {
            return null;
        }
        Alert current = alert;
        return ProbeState.builder()
                .probeName(probe.getName())
                .alert(current)
                .since(current == null ? null : since)
                .lastEvaluation(Instant.ofEpochMilli(lastEvaluation))
                .build();
    }

    private Optional<Alert> evaluate() {
        try {
            Optional<Alert> result = probe.getEvaluator().evaluate();
            return result != null ? result : Optional.of(Alert.major("Probe evaluator returned null instead of an Optional"));
        } catch (Exception e) {
            log.debug("Probe {} of session {} failed", probe.getName(), fixSessionId, e);
            return Optional.of(Alert.major("Probe evaluation failed: " + e));
        }
    }

    private AlertEvent published(AlertEvent.Type type, long tick, long nowMillis) {
        lastPublishedTick = tick;
        return event(type, alert, nowMillis);
    }

    private AlertEvent event(AlertEvent.Type type, Alert eventAlert, long nowMillis) {
        return AlertEvent.builder()
                .type(type)
                .engineId(engineId)
                .fixSessionId(fixSessionId)
                .probeName(probe.getName())
                .probeDescription(probe.getDescription())
                .alert(eventAlert)
                .since(since)
                .time(Instant.ofEpochMilli(nowMillis))
                .build();
    }
}
