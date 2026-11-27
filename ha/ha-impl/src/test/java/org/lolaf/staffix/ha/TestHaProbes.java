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
import org.lolaf.staffix.api.alerting.ProbeEvaluator;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.replicos.DroppedFollower;
import org.lolaf.replicos.FollowerLaneStatus;
import org.lolaf.replicos.LeaderStatus;
import org.lolaf.replicos.LeaderView;
import org.lolaf.replicos.ReplicationNode;
import org.lolaf.replicos.StreamWriter;
import org.lolaf.replicos.FollowersView;
import org.lolaf.replicos.wire.CloseReason;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestHaProbes {

    private static final InetSocketAddress ADDRESS = new InetSocketAddress("localhost", 1);
    /**
     * Node a leads, b is its standby and dr a disaster-recovery copy SYNC sends do not wait for.
     */
    private static final ReplicationHaSettings STANDBY_AND_COPY = settings(UnaryOperator.identity());

    private final ReplicatedSession session = new ReplicatedSessions().register(
            RecordingEngine.session("main", FixSessionType.ACCEPTOR), new RecordingEngine.RecordingStore());
    private final AtomicLong elapsedNanos = new AtomicLong();
    private final SessionReplicationEvaluator evaluator = new SessionReplicationEvaluator(session, mock(FollowersView.class),
            () -> System.nanoTime() + elapsedNanos.get(), Duration.ofMinutes(5).toNanos());
    private final ReplicationFixEngineHa ha = mock(ReplicationFixEngineHa.class);
    private final ReplicationNode node = mock(ReplicationNode.class);
    private final FollowersView followers = mock(FollowersView.class);
    private final LeaderView leader = mock(LeaderView.class);

    private static ReplicationHaSettings settings(UnaryOperator<ReplicationHaSettings.ReplicationHaSettingsBuilder> tuning) {
        return tuning.apply(ReplicationHaSettings.builder().nodeId("a").peer("a", ADDRESS).peer("b", ADDRESS).peer("dr", ADDRESS)
                .syncFollower("a").syncFollower("b")).build();
    }

    private static FollowerLaneStatus follower(String nodeId, boolean syncFollower, long lagMillis) {
        return FollowerLaneStatus.builder().nodeId(nodeId).address("/10.0.0.2:40000").connected(true).inSync(true)
                .syncFollower(syncFollower).lagMillis(lagMillis).build();
    }

    private void leading(ReplicationHaSettings settings, FollowerLaneStatus... lanes) {
        when(ha.getRole()).thenReturn(Role.LEADER);
        when(ha.getSettings()).thenReturn(settings);
        when(ha.getNode()).thenReturn(node);
        when(node.getLanes()).thenReturn(1);
        when(node.followers()).thenReturn(followers);
        when(followers.getLaneStatuses()).thenReturn(List.of(lanes));
        when(followers.getDroppedFollowers()).thenReturn(List.of());
    }

    @Test
    void aTimedOutSyncSendAlertsUntilNoneFollowsForTheExpiry() {
        assertThat(evaluator.evaluate()).isEmpty();

        session.recordAck(StreamWriter.Ack.TIMED_OUT);
        session.recordAck(StreamWriter.Ack.TIMED_OUT);

        assertThat(evaluator.evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.MAJOR);
            assertThat(alert.getMessage()).isEqualTo("2 SYNC sends went out without the followers' acks in time");
        });
        elapsedNanos.set(Duration.ofMinutes(5).toNanos());
        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void aSendWithNoFollowerInSyncIsLeftToTheNodesProbe() {
        session.recordAck(StreamWriter.Ack.NO_FOLLOWER_IN_SYNC);

        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void aSendWithFewerSyncFollowersInSyncThanItsQuorumAlertsUntilTheExpiry() {
        session.recordAck(StreamWriter.Ack.NOT_ENOUGH_FOLLOWERS);

        assertThat(evaluator.evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.MAJOR);
            assertThat(alert.getMessage()).isEqualTo("1 SYNC sends went out unacknowledged, fewer sync followers being in"
                    + " sync than the session's quorum");
        });
        elapsedNanos.set(Duration.ofMinutes(5).toNanos());
        assertThat(evaluator.evaluate()).isEmpty();
    }

    @Test
    void aStalledDisasterRecoveryCopyWarnsWhereAStalledSyncFollowerIsMajor() {
        leading(STANDBY_AND_COPY, follower("b", true, 0), follower("dr", false, 60_000));

        assertThat(HaProbes.followersEvaluator(ha).evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.WARNING);
            assertThat(alert.getMessage()).isEqualTo("Follower dr (async) has not applied a write for 60000 ms");
        });

        leading(STANDBY_AND_COPY, follower("b", true, 60_000), follower("dr", false, 0));

        assertThat(HaProbes.followersEvaluator(ha).evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.MAJOR);
            assertThat(alert.getMessage()).startsWith("Follower b has not applied a write for 60000 ms");
        });
    }

    @Test
    void aFollowerNotConnectedWarnsSayingWhetherSyncSendsWaitForIt() {
        leading(STANDBY_AND_COPY, follower("b", true, 0));

        assertThat(HaProbes.followersEvaluator(ha).evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.WARNING);
            assertThat(alert.getMessage()).isEqualTo("Follower dr (async) not connected");
        });

        leading(settings(builder -> builder.peer("c", ADDRESS).syncFollower("c")), follower("b", true, 0),
                follower("dr", false, 0));

        assertThat(HaProbes.followersEvaluator(ha).evaluate()).hasValueSatisfying(alert ->
                assertThat(alert.getMessage()).isEqualTo("Follower c not connected"));
    }

    @Test
    void aDroppedDisasterRecoveryCopyWarns() {
        leading(STANDBY_AND_COPY, follower("b", true, 0));
        when(followers.getDroppedFollowers()).thenReturn(List.of(
                DroppedFollower.builder().nodeId("dr").lane(0).droppedAt(Instant.parse("2026-11-27T10:00:00Z")).build()));

        assertThat(HaProbes.followersEvaluator(ha).evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.WARNING);
            assertThat(alert.getMessage()).isEqualTo("Follower dr (async) dropped as too slow at 2026-11-27T10:00:00Z");
        });
    }

    @Test
    void aFollowerRefusedForItsNodeIdSaysSoUntilItConnects() {
        when(ha.getRole()).thenReturn(Role.FOLLOWER);
        when(ha.getSettings()).thenReturn(STANDBY_AND_COPY);
        when(ha.getNode()).thenReturn(node);
        when(node.leader()).thenReturn(leader);
        when(leader.getCloses()).thenReturn(Map.of());
        LeaderStatus disconnected = LeaderStatus.builder().address("/10.0.0.1:17101").build();
        when(leader.getStatus()).thenReturn(disconnected);
        ProbeEvaluator probe = HaProbes.leaderConnectionEvaluator(ha);

        assertThat(probe.evaluate()).hasValueSatisfying(alert -> assertThat(alert.getMessage()).startsWith("Not connected"));

        when(leader.getCloses()).thenReturn(Map.of(CloseReason.DUPLICATE_NODE_ID.name(), 1L));
        assertThat(probe.evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.MAJOR);
            assertThat(alert.getMessage()).isEqualTo("Refused by the leader /10.0.0.1:17101: another node is connected"
                    + " under this node's id, a");
        });

        when(leader.getStatus()).thenReturn(LeaderStatus.builder().address("/10.0.0.1:17101").connected(true).inSync(true).build());
        assertThat(probe.evaluate()).isEmpty();
        when(leader.getStatus()).thenReturn(disconnected);
        assertThat(probe.evaluate()).as("refused before it last connected").hasValueSatisfying(alert ->
                assertThat(alert.getMessage()).startsWith("Not connected"));
    }

    @Test
    void anEventTooLongForTheLogAlertsUntilTheExpiry() {
        session.recordUnreplicatedEvent();

        assertThat(evaluator.evaluate()).hasValueSatisfying(alert -> assertThat(alert.getMessage())
                .isEqualTo("1 messages longer than half the replication log, missing on the followers"));
        elapsedNanos.set(Duration.ofMinutes(5).toNanos());
        assertThat(evaluator.evaluate()).isEmpty();
    }
}
