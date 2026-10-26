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
package org.lolaf.staffix.ha;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.alerting.AlertLevel;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.ha.HaProbes.SessionReplicationEvaluator;
import org.lolaf.staffix.ha.follower.RecordingEngine;
import org.lolaf.staffix.ha.leader.Followers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TestHaProbes {

    private final ReplicatedSession session = new ReplicatedSessions().register(
            RecordingEngine.session("main", FixSessionType.ACCEPTOR), new RecordingEngine.RecordingStore());
    private final AtomicLong elapsedNanos = new AtomicLong();
    private final SessionReplicationEvaluator evaluator = new SessionReplicationEvaluator(session, new Followers(),
            () -> System.nanoTime() + elapsedNanos.get(), Duration.ofMinutes(5).toNanos());

    @Test
    void aTimedOutSyncSendAlertsUntilNoneFollowsForTheExpiry() {
        assertThat(evaluator.evaluate()).isEmpty();

        session.acknowledged(Followers.Ack.TIMED_OUT);
        session.acknowledged(Followers.Ack.TIMED_OUT);

        assertThat(evaluator.evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.MAJOR);
            assertThat(alert.getMessage()).isEqualTo("2 SYNC sends went out without the follower's ack in time");
        });
        elapsedNanos.set(Duration.ofMinutes(5).toNanos());
        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void aSendWithNoFollowerInSyncIsLeftToTheNodesProbe() {
        session.acknowledged(Followers.Ack.NO_FOLLOWER_IN_SYNC);

        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void anEventTooLongForTheLogAlertsUntilTheExpiry() {
        session.unreplicated();

        assertThat(evaluator.evaluate()).hasValueSatisfying(alert -> assertThat(alert.getMessage())
                .isEqualTo("1 messages longer than half the replication log, missing on the follower"));
        elapsedNanos.set(Duration.ofMinutes(5).toNanos());
        assertThat(evaluator.evaluate()).isEmpty();
    }
}
