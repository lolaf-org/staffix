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

import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.ProbeEvaluator;
import org.lolaf.staffix.api.alerting.RaiseDelayEvaluator;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionStatus;

import java.time.Duration;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * A session logged out inside its session time, raised by its probe once that has lasted its delay. An operator
 * logout or the end of the schedule is intended, so it is not an outage.
 */
public final class SessionDownEvaluator implements ProbeEvaluator {

    public static final String PROBE_NAME = "session-down";

    private final FixSession fixSession;
    private final Optional<Alert> alert;

    private SessionDownEvaluator(FixSession fixSession, Duration delay) {
        this.fixSession = fixSession;
        this.alert = Optional.of(Alert.critical("Logged out inside its session time for more than " + delay.toSeconds() + "s"));
    }

    static ProbeEvaluator delayed(FixSession fixSession, Duration delay, LongSupplier nanoTime) {
        return new RaiseDelayEvaluator(new SessionDownEvaluator(fixSession, delay), delay, nanoTime);
    }

    /**
     * The probe for a session, or empty when its delay is zero, which turns it off.
     */
    public static Optional<Probe> probe(FixSession fixSession, Duration delay) {
        if (delay.isZero()) {
            return Optional.empty();
        }
        return Optional.of(Probe.builder()
                .name(PROBE_NAME)
                .description("The session is logged out while its schedule says it should be logged in")
                .evaluator(delayed(fixSession, delay, System::nanoTime))
                .onlyWhileLoggedIn(false)
                .build());
    }

    @Override
    public Optional<Alert> evaluate() {
        return fixSession.getStatus() == FixSessionStatus.LOGGED_OUT_INSIDE_SESSION_TIME ? alert : Optional.empty();
    }
}
