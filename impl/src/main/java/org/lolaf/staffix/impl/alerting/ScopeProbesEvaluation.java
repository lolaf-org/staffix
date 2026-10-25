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
import org.lolaf.staffix.api.alerting.AlertEvent;
import org.lolaf.staffix.api.alerting.AlertsPublisher;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.ProbeState;
import org.lolaf.staffix.api.alerting.ProbesListener;
import org.lolaf.staffix.api.alerting.ScopedProbeState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The probes of one scope as the probes thread sees them. The evaluations are rebuilt only when the scope's probe
 * array is replaced, so a tick where nothing was added or removed allocates nothing. A probe run only while active is
 * suspended and resumed as the scope goes inactive and active again; a scope without one never has its activity read.
 */
@Slf4j
final class ScopeProbesEvaluation {

    private static final Probe[] NO_PROBES = new Probe[0];
    private static final ProbeEvaluation[] NO_EVALUATIONS = new ProbeEvaluation[0];

    private final String source;
    @Getter
    private final ProbesImpl probes;
    private final AlertsPublisher publisher;
    private final BooleanSupplier active;
    private final ProbesListener listener;
    private Probe[] evaluatedProbes = NO_PROBES;
    private boolean anyOnlyWhileActive;
    private volatile ProbeEvaluation[] evaluations = NO_EVALUATIONS;

    ScopeProbesEvaluation(String source, ProbesImpl probes, AlertsPublisher publisher, BooleanSupplier active,
                          ProbesListener listener) {
        this.source = source;
        this.probes = probes;
        this.publisher = publisher;
        this.active = active;
        this.listener = listener;
    }

    private static ProbeEvaluation find(ProbeEvaluation[] evaluations, Probe probe) {
        for (ProbeEvaluation evaluation : evaluations) {
            if (evaluation.getProbe() == probe) {
                return evaluation;
            }
        }
        return null;
    }

    void tick(long tick, long nowMillis) {
        Probe[] current = probes.array();
        if (current != evaluatedProbes) {
            reconcile(current, tick, nowMillis);
        }
        ProbeEvaluation[] local = evaluations;
        if (anyOnlyWhileActive) {
            followActivity(local, active.getAsBoolean(), tick, nowMillis);
        }
        for (ProbeEvaluation probeEvaluation : local) {
            publish(probeEvaluation, probeEvaluation.evaluateIfDue(tick, nowMillis));
        }
    }

    private void followActivity(ProbeEvaluation[] local, boolean isActive, long tick, long nowMillis) {
        for (ProbeEvaluation probeEvaluation : local) {
            if (!probeEvaluation.getProbe().isOnlyWhileActive()) {
                continue;
            }
            if (isActive && probeEvaluation.isSuspended()) {
                probeEvaluation.resume(tick);
            } else if (!isActive && !probeEvaluation.isSuspended()) {
                publish(probeEvaluation, probeEvaluation.suspend(nowMillis));
            }
        }
    }

    void clearAll(long nowMillis) {
        ProbeEvaluation[] local = evaluations;
        for (ProbeEvaluation probeEvaluation : local) {
            publish(probeEvaluation, probeEvaluation.clear(nowMillis));
        }
        evaluations = NO_EVALUATIONS;
        evaluatedProbes = NO_PROBES;
    }

    /**
     * Readable from any thread; a probe appears once it has been evaluated or suspended.
     */
    List<ProbeState> getStates() {
        ProbeEvaluation[] local = evaluations;
        List<ProbeState> states = new ArrayList<>(local.length);
        for (ProbeEvaluation probeEvaluation : local) {
            ProbeState state = probeEvaluation.state();
            if (state != null) {
                states.add(state);
            }
        }
        return Collections.unmodifiableList(states);
    }

    /**
     * Readable from any thread. A probe added since the last tick is not evaluated yet, so it cannot be in alert.
     */
    boolean clearAlert(String probeName) {
        for (ProbeEvaluation probeEvaluation : evaluations) {
            if (probeEvaluation.getProbe().getName().equals(probeName)) {
                return probeEvaluation.requestClear();
            }
        }
        return false;
    }

    /**
     * Allocates nothing for a probe that is not in alert, so a console polling a quiet engine costs next to nothing.
     */
    List<ScopedProbeState> addActiveAlerts(List<ScopedProbeState> active) {
        ProbeEvaluation[] local = evaluations;
        for (ProbeEvaluation probeEvaluation : local) {
            if (probeEvaluation.isInAlert()) {
                ProbeState state = probeEvaluation.state();
                if (state != null && state.getAlert().isPresent()) {
                    if (active == null) {
                        active = new ArrayList<>();
                    }
                    active.add(new ScopedProbeState(probes.getScope(), state, probeEvaluation.getProbe().getRunbookUrl()));
                }
            }
        }
        return active;
    }

    /**
     * Matches by instance: a probe removed then added again under the same name between two ticks is a different
     * probe, so it is cleared and starts over like any new one.
     */
    private void reconcile(Probe[] current, long tick, long nowMillis) {
        ProbeEvaluation[] previous = evaluations;
        ProbeEvaluation[] updated = new ProbeEvaluation[current.length];
        for (int i = 0; i < current.length; i++) {
            ProbeEvaluation kept = find(previous, current[i]);
            updated[i] = kept != null ? kept : new ProbeEvaluation(source, probes.getScope(), current[i], tick);
        }
        for (ProbeEvaluation probeEvaluation : previous) {
            if (find(updated, probeEvaluation.getProbe()) == null) {
                publish(probeEvaluation, probeEvaluation.clear(nowMillis));
            }
        }
        evaluatedProbes = current;
        evaluations = updated;
        anyOnlyWhileActive = false;
        for (Probe probe : current) {
            anyOnlyWhileActive |= probe.isOnlyWhileActive();
        }
    }

    private void publish(ProbeEvaluation probeEvaluation, AlertEvent event) {
        if (event == null) {
            return;
        }
        try {
            publisher.publish(event);
        } catch (Exception e) {
            log.warn("Alerts publisher {} failed to publish {}", publisher, event, e);
        }
        if (event.getType() != AlertEvent.Type.REMINDED) {
            listener.onAlertTransition(probes.getScope(), probeEvaluation.state(event.isClearedManually()));
        }
    }
}
