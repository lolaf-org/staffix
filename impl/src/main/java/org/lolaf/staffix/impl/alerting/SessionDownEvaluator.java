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
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionStatus;

import java.time.Duration;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Raised once a session has stayed logged out inside its session time for longer than its delay. An operator logout
 * or the end of the schedule is intended, so it is not an outage.
 */
public final class SessionDownEvaluator implements ProbeEvaluator {

    public static final String PROBE_NAME = "session-down";
    private static final long NOT_DOWN = Long.MIN_VALUE;

    private final FixSession fixSession;
    private final long delayNanos;
    private final LongSupplier nanoTime;
    private final Optional<Alert> alert;
    private long downSinceNanos = NOT_DOWN;

    SessionDownEvaluator(FixSession fixSession, Duration delay, LongSupplier nanoTime) {
        this.fixSession = fixSession;
        this.delayNanos = delay.toNanos();
        this.nanoTime = nanoTime;
        this.alert = Optional.of(Alert.critical("Logged out inside its session time for more than " + delay.toSeconds() + "s"));
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
                .evaluator(new SessionDownEvaluator(fixSession, delay, System::nanoTime))
                .build());
    }

    @Override
    public Optional<Alert> evaluate() {
        if (fixSession.getStatus() != FixSessionStatus.LOGGED_OUT_INSIDE_SESSION_TIME) {
            downSinceNanos = NOT_DOWN;
            return Optional.empty();
        }
        long now = nanoTime.getAsLong();
        if (downSinceNanos == NOT_DOWN) {
            downSinceNanos = now;
        }
        return now - downSinceNanos >= delayNanos ? alert : Optional.empty();
    }
}
