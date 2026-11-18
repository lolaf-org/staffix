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
package org.lolaf.staffix.impl.session;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.alerting.AlertLevel;
import org.lolaf.staffix.api.alerting.ProbeEvaluator;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionStatus;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSessionDownEvaluator {

    private final FixSession session = mock(FixSession.class);
    private final AtomicLong nanoTime = new AtomicLong();
    private final ProbeEvaluator evaluator = SessionDownEvaluator.delayed(session, Duration.ofSeconds(10), nanoTime::get);

    @Test
    void raisesACriticalAlertOnceDownInsideSessionTimeForLongerThanTheDelay() {
        when(session.getStatus()).thenReturn(FixSessionStatus.LOGGED_OUT_INSIDE_SESSION_TIME);

        assertThat(evaluator.evaluate()).isEmpty();
        advance(Duration.ofSeconds(9));
        assertThat(evaluator.evaluate()).isEmpty();
        advance(Duration.ofSeconds(1));

        assertThat(evaluator.evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.CRITICAL);
            assertThat(alert.getMessage()).contains("10s");
        });
    }

    @Test
    void clearsAndRestartsTheDelayOnceLoggedIn() {
        when(session.getStatus()).thenReturn(FixSessionStatus.LOGGED_OUT_INSIDE_SESSION_TIME);
        evaluator.evaluate();
        advance(Duration.ofSeconds(10));
        assertThat(evaluator.evaluate()).isPresent();

        when(session.getStatus()).thenReturn(FixSessionStatus.LOGGED_IN);
        assertThat(evaluator.evaluate()).isEmpty();

        when(session.getStatus()).thenReturn(FixSessionStatus.LOGGED_OUT_INSIDE_SESSION_TIME);
        advance(Duration.ofSeconds(1));
        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void anIntendedLogoutIsNotAnOutage() {
        for (FixSessionStatus intended : new FixSessionStatus[]{FixSessionStatus.LOGGED_OUT_BY_OPERATOR, FixSessionStatus.LOGGED_OUT_OUTSIDE_SESSION_TIME}) {
            when(session.getStatus()).thenReturn(intended);
            evaluator.evaluate();
            advance(Duration.ofMinutes(1));

            assertThat(evaluator.evaluate()).as("%s", intended).isEmpty();
        }
    }

    @Test
    void aZeroDelayTurnsTheProbeOff() {
        assertThat(SessionDownEvaluator.probe(session, Duration.ZERO)).isEmpty();
        assertThat(SessionDownEvaluator.probe(session, Duration.ofSeconds(10)))
                .hasValueSatisfying(probe -> assertThat(probe.getName()).isEqualTo(SessionDownEvaluator.PROBE_NAME));
    }

    @Test
    void isEvaluatedWhileLoggedOut() {
        assertThat(SessionDownEvaluator.probe(session, Duration.ofSeconds(10)))
                .hasValueSatisfying(probe -> assertThat(probe.isOnlyWhileActive()).isFalse());
    }

    private void advance(Duration duration) {
        nanoTime.addAndGet(duration.toNanos());
    }
}
