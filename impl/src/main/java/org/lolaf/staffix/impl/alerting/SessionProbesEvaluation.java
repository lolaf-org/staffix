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
import org.lolaf.staffix.api.session.FixSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The probes of one session as the probes thread sees them. The evaluations are rebuilt only when the session's probe
 * array is replaced, so a tick where nothing was added or removed allocates nothing.
 */
@Slf4j
final class SessionProbesEvaluation {

    private static final Probe[] NO_PROBES = new Probe[0];
    private static final ProbeEvaluation[] NO_EVALUATIONS = new ProbeEvaluation[0];

    @Getter
    private final FixSession fixSession;
    private final FixSessionProbesImpl probes;
    private final FixAlertsPublisher publisher;
    private Probe[] evaluatedProbes = NO_PROBES;
    private volatile ProbeEvaluation[] evaluations = NO_EVALUATIONS;

    SessionProbesEvaluation(FixSession fixSession, FixSessionProbesImpl probes, FixAlertsPublisher publisher) {
        this.fixSession = fixSession;
        this.probes = probes;
        this.publisher = publisher;
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
        for (ProbeEvaluation probeEvaluation : local) {
            publish(probeEvaluation.evaluateIfDue(tick, nowMillis));
        }
    }

    void clearAll(long nowMillis) {
        ProbeEvaluation[] local = evaluations;
        for (ProbeEvaluation probeEvaluation : local) {
            publish(probeEvaluation.clear(nowMillis));
        }
        evaluations = NO_EVALUATIONS;
        evaluatedProbes = NO_PROBES;
    }

    /**
     * Readable from any thread; a probe appears once it has been evaluated.
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
     * Matches by instance: a probe removed then added again under the same name between two ticks is a different
     * probe, so it is cleared and starts over like any new one.
     */
    private void reconcile(Probe[] current, long tick, long nowMillis) {
        ProbeEvaluation[] previous = evaluations;
        ProbeEvaluation[] updated = new ProbeEvaluation[current.length];
        for (int i = 0; i < current.length; i++) {
            ProbeEvaluation kept = find(previous, current[i]);
            updated[i] = kept != null ? kept : new ProbeEvaluation(fixSession.getFixSessionId(), current[i], tick);
        }
        for (ProbeEvaluation probeEvaluation : previous) {
            if (find(updated, probeEvaluation.getProbe()) == null) {
                publish(probeEvaluation.clear(nowMillis));
            }
        }
        evaluatedProbes = current;
        evaluations = updated;
    }

    private void publish(AlertEvent event) {
        if (event == null) {
            return;
        }
        try {
            publisher.publish(event);
        } catch (Exception e) {
            log.warn("Alerts publisher {} failed to publish {}", publisher.getInstanceId(), event, e);
        }
    }
}
