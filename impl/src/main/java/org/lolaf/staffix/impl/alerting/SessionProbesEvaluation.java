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
import org.lolaf.staffix.api.alerting.FixAlertsPublisher;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.ProbeState;
import org.lolaf.staffix.api.alerting.ProbesListener;
import org.lolaf.staffix.api.alerting.SessionProbeState;
import org.lolaf.staffix.api.session.FixSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The probes of one session as the probes thread sees them. The evaluations are rebuilt only when the session's probe
 * array is replaced, so a tick where nothing was added or removed allocates nothing. A probe run only while logged in
 * is suspended and resumed as the session logs out and in; a session without one never has its logon read.
 */
@Slf4j
final class SessionProbesEvaluation {

    private static final Probe[] NO_PROBES = new Probe[0];
    private static final ProbeEvaluation[] NO_EVALUATIONS = new ProbeEvaluation[0];

    private final String engineId;
    @Getter
    private final FixSession fixSession;
    private final FixSessionProbesImpl probes;
    private final FixAlertsPublisher publisher;
    private final ProbesListener listener;
    private Probe[] evaluatedProbes = NO_PROBES;
    private boolean anyOnlyWhileLoggedIn;
    private volatile ProbeEvaluation[] evaluations = NO_EVALUATIONS;

    SessionProbesEvaluation(String engineId, FixSession fixSession, FixSessionProbesImpl probes,
                            FixAlertsPublisher publisher, ProbesListener listener) {
        this.engineId = engineId;
        this.fixSession = fixSession;
        this.probes = probes;
        this.publisher = publisher;
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
        if (anyOnlyWhileLoggedIn) {
            followLogon(local, fixSession.isLoggedIn(), tick, nowMillis);
        }
        for (ProbeEvaluation probeEvaluation : local) {
            publish(probeEvaluation, probeEvaluation.evaluateIfDue(tick, nowMillis));
        }
    }

    private void followLogon(ProbeEvaluation[] local, boolean loggedIn, long tick, long nowMillis) {
        for (ProbeEvaluation probeEvaluation : local) {
            if (!probeEvaluation.getProbe().isOnlyWhileLoggedIn()) {
                continue;
            }
            if (loggedIn && probeEvaluation.isSuspended()) {
                probeEvaluation.resume(tick);
            } else if (!loggedIn && !probeEvaluation.isSuspended()) {
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
     *
     * @throws IllegalArgumentException if the session has no such probe
     */
    boolean clearAlert(String probeName) {
        for (ProbeEvaluation probeEvaluation : evaluations) {
            if (probeEvaluation.getProbe().getName().equals(probeName)) {
                return probeEvaluation.requestClear();
            }
        }
        for (Probe probe : probes.array()) {
            if (probe.getName().equals(probeName)) {
                return false;
            }
        }
        throw new IllegalArgumentException("Session " + fixSession.getFixSessionId() + " has no probe named " + probeName);
    }

    /**
     * Allocates nothing for a probe that is not in alert, so a console polling a quiet engine costs next to nothing.
     */
    List<SessionProbeState> addActiveAlerts(List<SessionProbeState> active) {
        ProbeEvaluation[] local = evaluations;
        for (ProbeEvaluation probeEvaluation : local) {
            if (probeEvaluation.isInAlert()) {
                ProbeState state = probeEvaluation.state();
                if (state != null && state.getAlert().isPresent()) {
                    if (active == null) {
                        active = new ArrayList<>();
                    }
                    active.add(new SessionProbeState(fixSession.getFixSessionId(), state, probeEvaluation.getProbe().getRunbookUrl()));
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
            updated[i] = kept != null ? kept : new ProbeEvaluation(engineId, fixSession.getFixSessionId(), current[i], tick);
        }
        for (ProbeEvaluation probeEvaluation : previous) {
            if (find(updated, probeEvaluation.getProbe()) == null) {
                publish(probeEvaluation, probeEvaluation.clear(nowMillis));
            }
        }
        evaluatedProbes = current;
        evaluations = updated;
        anyOnlyWhileLoggedIn = false;
        for (Probe probe : current) {
            anyOnlyWhileLoggedIn |= probe.isOnlyWhileLoggedIn();
        }
    }

    private void publish(ProbeEvaluation probeEvaluation, AlertEvent event) {
        if (event == null) {
            return;
        }
        try {
            publisher.publish(event);
        } catch (Exception e) {
            log.warn("Alerts publisher {} failed to publish {}", publisher.getInstanceId(), event, e);
        }
        if (event.getType() != AlertEvent.Type.REMINDED) {
            listener.onAlertTransition(fixSession.getFixSessionId(), probeEvaluation.state(event.isClearedManually()));
        }
    }
}
