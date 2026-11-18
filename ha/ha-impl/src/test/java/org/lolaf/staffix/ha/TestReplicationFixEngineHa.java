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
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.alerting.AlertLevel;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.ha.FixEngineHa.ReplicatingStore;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;
import org.lolaf.staffix.ha.follower.RecordingEngine;
import org.lolaf.staffix.ha.follower.RecordingMeters;
import org.lolaf.staffix.ha.follower.RecordingProbes;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

@ParameterizedClass
@ValueSource(booleans = {false, true})
class TestReplicationFixEngineHa extends HaPairFixture {

    TestReplicationFixEngineHa(boolean storeWriterThreads) {
        super(storeWriterThreads);
    }

    @Test
    void thereIsAtLeastOneReplicationLane() {
        ReplicationHaSettings settings = ReplicationHaSettings.builder().replicationLanes(0).build();

        assertThatThrownBy(() -> new ReplicationFixEngineHa(settings)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replicationLanes");
    }

    @Test
    void aNodeWhoseRoleIsUnknownRunsNoSessionAndListensToNoFollower() {
        a.start();

        await().during(Duration.ofMillis(200)).until(() -> a.engine.activity.isEmpty());
        assertThat(listening(addressA)).isFalse();
    }

    @Test
    void theLeaderRunsItsSessionsAndItsFollowerReplicatesThemWithoutRunningAny() {
        aLeadsBFollows();

        a.store.storeNextIncomingSeqNum(7);

        await().untilAsserted(() -> assertThat(b.engine.store(MAIN).writes).endsWith("incoming 7"));
        assertThat(b.engine.activity).isEmpty();
    }

    @Test
    void promotionClosesTheReplicaBeforeRunningTheSessions() {
        aLeadsBFollows();
        a.store.storeNextIncomingSeqNum(7);
        await().until(() -> b.engine.store(MAIN) != null && b.engine.store(MAIN).writes.contains("incoming 7"));

        a.election.elect(Role.FOLLOWER, 4, "b");
        b.election.elect(Role.LEADER, 4, "b");

        await().until(() -> a.engine.activity.contains("deactivate"));
        await().until(() -> b.engine.activity.contains("activate, stores open 0"));
        assertThat(b.engine.store(MAIN).writes).as("moved past what a may have sent").endsWith("outgoing 10001");
        await().until(() -> listening(addressB));
        await().until(() -> !listening(addressA));
    }

    @Test
    void aFollowerTakesOverALeaderThatStoppedCleanlyWithoutMovingItsSessionsAhead() {
        aLeadsBFollows();
        a.store.storeNextIncomingSeqNum(7);

        a.ha.stop(Deadline.of(Duration.ofSeconds(2)));
        started.remove(a.ha);
        b.election.elect(Role.LEADER, 4, "b");

        await().until(() -> b.engine.activity.contains("activate, stores open 0"));
        assertThat(b.engine.store(MAIN).writes).as("the last write reached b before the handover").contains("incoming 7")
                .doesNotContain("outgoing 10001");
    }

    @Test
    void aLeaderReElectedAtANewEpochDoesNotMoveItsOwnSessionsAhead() {
        a.election.elect(Role.LEADER, 3, "a");
        a.start();
        await().until(() -> a.engine.activity.contains("activate, stores open 0"));
        int jumps = jumps(a);

        a.election.elect(Role.LEADER, 4, "a");

        await().until(() -> a.engine.activity.size() == 3);
        assertThat(a.engine.activity).containsExactly("activate, stores open 0", "deactivate", "activate, stores open 0");
        assertThat(jumps(a)).isEqualTo(jumps);
    }

    /**
     * A connector created after the promotion builds its session then: it used to start where it was, as if this node
     * had always led it, and whether a boot jumped depended on which came first.
     */
    @Test
    void aSessionStartedAfterThePromotionIsMovedAheadOncePerLeadership() {
        FixSessionSettings late = RecordingEngine.session("late", FixSessionType.ACCEPTOR);
        FixSessionsSettingsStore configured = settingsStore("files", Optional.empty());
        when(configured.getSettings()).thenReturn(List.of(late));
        a.engine.settingsStores.add(configured);
        a.election.elect(Role.LEADER, 3, "a");
        a.start();
        await().until(() -> a.engine.activity.contains("activate, stores open 0"));
        RecordingEngine.RecordingStore lateStore = new RecordingEngine.RecordingStore();

        a.ha.replicatingStore(late, lateStore, new RecordingProbes()).start();
        lateStore.stop(Deadline.immediate());
        a.ha.replicatingStore(late, lateStore, new RecordingProbes()).start();
        assertThat(lateStore.writes).as("rebuilt under the same leadership").containsOnlyOnce("outgoing 10001");

        a.election.elect(Role.LEADER, 4, "a");
        await().until(() -> a.engine.activity.size() == 3);
        a.ha.replicatingStore(late, lateStore, new RecordingProbes()).start();
        assertThat(lateStore.writes).as("a new epoch, the same leadership").containsOnlyOnce("outgoing 10001");

        a.election.elect(Role.FOLLOWER, 5, "b");
        await().until(() -> a.engine.activity.contains("deactivate") && a.engine.activity.size() == 4);
        a.election.elect(Role.LEADER, 6, "a");
        await().until(() -> a.engine.activity.size() == 5);
        a.ha.replicatingStore(late, lateStore, new RecordingProbes()).start();
        assertThat(lateStore.writes).as("a new leadership").filteredOn("outgoing 10001"::equals).hasSize(2);
    }

    @Test
    void aSessionRunUnreplicatedIsNotMovedAheadWhenReplicatedUnderTheSameLeadership() {
        a.election.elect(Role.LEADER, 3, "a");
        a.start();
        await().until(() -> a.engine.activity.contains("activate, stores open 0"));
        FixSessionSettings late = RecordingEngine.session("late", FixSessionType.ACCEPTOR);
        RecordingEngine.RecordingStore lateStore = new RecordingEngine.RecordingStore();

        a.ha.onSessionNotReplicated(late);
        a.ha.replicatingStore(late, lateStore, new RecordingProbes()).start();

        assertThat(lateStore.writes).noneMatch(write -> write.startsWith("outgoing"));
    }

    private static int jumps(Node node) {
        RecordingEngine.RecordingStore store = node.engine.store(MAIN);
        return store == null ? 0 : (int) store.writes.stream().filter(write -> write.startsWith("outgoing 10001")).count();
    }

    @Test
    void aLeaderThatCannotProveItLeadsStopsItsSessions() {
        a.election.elect(Role.LEADER, 3, "a");
        a.start();
        await().until(() -> a.engine.activity.contains("activate, stores open 0"));

        a.election.leadershipValid(false);

        await().until(() -> a.engine.activity.contains("deactivate"));
        await().until(() -> !listening(addressA));
    }

    @Test
    void aLeaderStopsItsSessionsWhenItsLeaseEndsNotOnItsNextPoll() {
        Node leader = new Node("a", new RecordingEngine().with(MAIN), Duration.ofSeconds(30));
        leader.election.elect(Role.LEADER, 3, "a");
        leader.election.leaseFor(Duration.ofSeconds(1));
        leader.start();
        await().until(() -> leader.engine.activity.contains("activate, stores open 0"));
        assertThat(leader.engine.activity).doesNotContain("deactivate");

        await().atMost(Duration.ofSeconds(3)).until(() -> leader.engine.activity.contains("deactivate"));
    }

    @Test
    void aHeartbeatIntervalShorterThanAMillisecondStillFollowsTheElection() {
        Node leader = new Node("a", new RecordingEngine().with(MAIN), Duration.ofNanos(500_000));
        leader.election.elect(Role.LEADER, 3, "a");

        leader.start();

        await().until(() -> leader.engine.activity.contains("activate, stores open 0"));
    }

    @Test
    void aSessionBuiltWithoutReplicationIsDroppedByTheFollowerThenCaughtUpBySnapshotOnceReplicatedAgain() {
        aLeadsBFollows();
        RecordingEngine.RecordingStore replicaStore = b.engine.store(MAIN);

        b.engine.replace(MAIN.toBuilder()
                .highAvailability(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.NONE).build())
                .build());
        a.ha.onSessionNotReplicated(MAIN);

        await().until(() -> !replicaStore.isStarted());
        assertThat(b.ha.getReplicaSessions().isInSync()).isTrue();
        assertThat(a.ha.getStatus().getFollowerConnections()).singleElement().satisfies(follower -> assertThat(follower.isInSync()).isTrue());

        b.engine.replace(MAIN);
        a.ha.replicatingStore(MAIN, new RecordingEngine.RecordingStore().start(), new RecordingProbes());

        await().until(() -> b.engine.store(MAIN) != replicaStore && b.ha.getSessionStatuses().get(0).isInSync());
        assertThat(a.engine.meters.read("ha.snapshots")).as("b caught up twice").isEqualTo(2);
    }

    @Test
    void aSessionTheLeaderStopsReplicatingIsDroppedBeforeTheFollowerSettingsCatchUpWithoutARefusal() {
        aLeadsBFollows();
        await().until(() -> b.ha.getSessionStatuses().get(0).isInSync());
        RecordingEngine.RecordingStore replicaStore = b.engine.store(MAIN);

        a.ha.onSessionNotReplicated(MAIN);

        await().until(() -> !replicaStore.isStarted());
        assertThat(b.ha.getSessionStatuses().get(0).getNotReplicatedReason()).isNotEqualTo("MODE_MISMATCH");
        assertThat(a.ha.getSessionStatuses().get(0).getNotReplicatedReason()).isNotEqualTo("MODE_MISMATCH");
    }

    @Test
    void aReplicationModeChangedOnTheLeaderAloneIsRefusedByTheFollower() {
        aLeadsBFollows();
        await().until(() -> b.ha.getSessionStatuses().get(0).isInSync());
        FixSessionSettings sync = MAIN.toBuilder()
                .highAvailability(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.SYNC_APPLIED).build())
                .build();

        a.ha.replicatingStore(sync, new RecordingEngine.RecordingStore().start(), new RecordingProbes());

        await().until(() -> "MODE_MISMATCH".equals(a.ha.getSessionStatuses().get(0).getNotReplicatedReason()));
        assertThat(b.ha.getSessionStatuses().get(0).getNotReplicatedReason()).isEqualTo("MODE_MISMATCH");
    }

    @Test
    void aSyncReceivedSendIsTimedUnderItsModeAndTheFollowerTimesTheWriteItAcknowledgedFirst() {
        aLeadsBFollows();
        FixSessionSettings syncReceived = MAIN.toBuilder()
                .highAvailability(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.SYNC_RECEIVED).build())
                .build();
        b.engine.replace(syncReceived);
        ReplicatingStore store = a.ha.replicatingStore(syncReceived, new RecordingEngine.RecordingStore().start(), new RecordingProbes());
        await().until(() -> a.ha.getSessionStatuses().get(0).isInSync());

        store.storeNextIncomingSeqNum(7);
        store.awaitReplicated();

        assertThat(a.engine.meters.recordings(HaMeters.SYNC_ACK_LATENCY, Map.of("mode", "sync_received"))).isEqualTo(1);
        assertThat(a.engine.meters.recordings(HaMeters.SYNC_ACK_LATENCY, Map.of("mode", "sync_applied"))).isZero();
        await().until(() -> b.engine.meters.recordings(HaMeters.REPLICA_RECEIVED_WRITE_LATENCY) > 0);
    }

    @Test
    void theMetersFollowTheReplicationOnBothNodes() {
        aLeadsBFollows();
        RecordingMeters leader = a.engine.meters;
        RecordingMeters follower = b.engine.meters;

        await().until(() -> leader.read("ha.followers.in.sync") == 1);
        assertThat(leader.read("ha.role")).isEqualTo(2);
        assertThat(leader.read("ha.epoch")).isEqualTo(3);
        assertThat(leader.read("ha.leadership.valid")).isEqualTo(1);
        assertThat(leader.read("ha.followers.connected")).isEqualTo(1);
        assertThat(leader.read("ha.snapshots")).as("b caught up by snapshot").isEqualTo(1);
        assertThat(leader.read("ha.replica.connected")).isZero();
        await().until(() -> follower.read("ha.replica.in.sync") == 1);
        assertThat(follower.read("ha.role")).isEqualTo(1);
        assertThat(follower.read("ha.replica.connected")).isEqualTo(1);
        assertThat(follower.read("ha.followers.connected")).isZero();
        assertThat(follower.read("ha.leadership.valid")).isZero();
        double written = leader.read("ha.log.written");

        a.store.storeNextIncomingSeqNum(7);
        a.store.awaitReplicated();

        assertThat(leader.read("ha.log.written")).isGreaterThan(written);
        assertThat(leader.recordings(HaMeters.SYNC_ACK_LATENCY, Map.of("mode", "sync_applied"))).isEqualTo(1);
        await().until(() -> leader.read("ha.follower.unsent") == 0);
        assertThat(leader.read("ha.sync.unacknowledged", Map.of("reason", "no_follower"))).isZero();
        await().until(() -> leader.recordings(HaMeters.FOLLOWER_ACK_LATENCY, Map.of("follower", "b", "lane", "0")) > 0);
        await().until(() -> leader.recordings(HaMeters.FOLLOWER_LAG, Map.of("follower", "b", "lane", "0")) > 0);
        await().until(() -> leader.read("ha.follower.lag.pending") == 0);
    }

    private Node node(String nodeId, int replicationLanes) {
        return new Node(nodeId, new RecordingEngine().with(MAIN).with(OTHER), Duration.ofMillis(50), replicationLanes).withOther();
    }

    private static void leads(Node leader, Node follower) {
        leader.election.elect(Role.LEADER, 3, leader.ha.getSettings().getNodeId());
        follower.election.elect(Role.FOLLOWER, 3, leader.ha.getSettings().getNodeId());
        leader.start();
        follower.start();
        await().until(() -> leader.engine.meters.read("ha.followers.in.sync") == 1
                && follower.engine.store(OTHER) != null && follower.ha.getReplicaSessions().isInSync());
    }

    @Test
    void aFollowerOverTwoLanesCountsOnceWithAStatusPerLane() {
        Node leader = node("a", 2);
        Node follower = node("b", 2);

        leads(leader, follower);

        assertThat(leader.engine.meters.read("ha.followers.connected")).isEqualTo(1);
        assertThat(leader.ha.getStatus().getFollowerConnections()).extracting(HaStatus.FollowerConnection::getNodeId, HaStatus.FollowerConnection::getLane)
                .containsExactlyInAnyOrder(tuple("b", 0), tuple("b", 1));
        leader.store.storeNextIncomingSeqNum(7);
        await().untilAsserted(() -> assertThat(follower.engine.store(MAIN).writes).endsWith("incoming 7"));
    }

    @Test
    void theFollowerOpensAsManyLanesAsTheLeaderHasWhateverItsOwnSetting() {
        Node leader = node("a", 2);
        Node follower = node("b", 1);

        leads(leader, follower);

        assertThat(follower.ha.getReplicaSessions().getLanes()).isEqualTo(2);
        assertThat(leader.ha.getStatus().getFollowerConnections()).extracting(HaStatus.FollowerConnection::getLane).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void aSnapshotStalledOnOneLaneDoesNotHoldBackTheSessionsOfAnother() {
        Node leader = node("a", 2);
        Node follower = node("b", 2);
        leads(leader, follower);
        CountDownLatch stalled = new CountDownLatch(1);
        follower.engine.store(OTHER).incomingWritesHeld = stalled;
        try {
            leader.ha.resync(OTHER.getFixSessionId(), OTHER.getFixSessionType());
            await().until(() -> !leader.ha.getStatus().getFollowerConnections().stream().allMatch(HaStatus.FollowerConnection::isInSync));

            leader.store.storeNextIncomingSeqNum(7);

            await().untilAsserted(() -> assertThat(follower.engine.store(MAIN).writes).endsWith("incoming 7"));
            assertThat(leader.ha.getStatus().getFollowerConnections()).filteredOn(HaStatus.FollowerConnection::isInSync)
                    .extracting(HaStatus.FollowerConnection::getLane).containsExactly(0);
        } finally {
            stalled.countDown();
        }
        await().until(() -> leader.engine.meters.read("ha.followers.in.sync") == 1);
    }

    @Test
    void aLeaderStoppingCleanlyHandsOverTheSessionsOfEveryLane() {
        Node leader = node("a", 2);
        Node follower = node("b", 2);
        leads(leader, follower);
        leader.store.storeNextIncomingSeqNum(7);
        leader.other.storeNextIncomingSeqNum(8);

        leader.ha.stop(Deadline.of(Duration.ofSeconds(2)));
        started.remove(leader.ha);
        follower.election.elect(Role.LEADER, 4, "b");

        await().until(() -> follower.engine.activity.contains("activate, stores open 0"));
        assertThat(follower.engine.store(MAIN).writes).contains("incoming 7").doesNotContain("outgoing 10001");
        assertThat(follower.engine.store(OTHER).writes).contains("incoming 8").doesNotContain("outgoing 10001");
    }

    @Test
    void aSyncSessionTimesItsAcksInItsOwnMetersOnlyWhenAsked() {
        RecordingMeters timedMeters = new RecordingMeters();
        RecordingMeters untimedMeters = new RecordingMeters();
        b.engine.replace(sync(MAIN, true));
        ReplicatingStore timed = a.ha.replicatingStore(sync(MAIN, true), new RecordingEngine.RecordingStore().start(), new RecordingProbes());
        aLeadsBFollows();

        timed.onSessionMeters(timedMeters);
        timed.storeNextIncomingSeqNum(7);
        timed.awaitReplicated();
        ReplicatingStore untimed = a.ha.replicatingStore(sync(MAIN, false), new RecordingEngine.RecordingStore().start(), new RecordingProbes());
        untimed.onSessionMeters(untimedMeters);
        untimed.storeNextIncomingSeqNum(8);
        untimed.awaitReplicated();

        assertThat(timedMeters.recordings(HaMeters.SESSION_SYNC_ACK_LATENCY)).isEqualTo(1);
        assertThat(untimedMeters.recordings(HaMeters.SESSION_SYNC_ACK_LATENCY)).isZero();
        assertThat(a.engine.meters.recordings(HaMeters.SYNC_ACK_LATENCY, Map.of("mode", "sync_applied")))
                .as("the engine's timer counts both").isEqualTo(2);
    }

    private static FixSessionSettings sync(FixSessionSettings settings, boolean syncAckLatencyEnabled) {
        return settings.toBuilder().highAvailability(FixSessionSettings.HaSessionSettings.builder()
                .replicationMode(ReplicationMode.SYNC_APPLIED).syncAckLatencyEnabled(syncAckLatencyEnabled).build()).build();
    }

    @Test
    void aResyncSendsEverySessionAgainToTheFollowerWhichHoldsItInSyncAgain() {
        aLeadsBFollows();
        await().until(() -> a.engine.meters.read("ha.followers.in.sync") == 1);

        a.ha.resync();

        await().until(() -> a.engine.meters.read("ha.snapshots") == 2 && a.engine.meters.read("ha.followers.in.sync") == 1);
        assertThat(b.engine.store(MAIN).writes).as("reset once per snapshot").filteredOn("reset"::equals).hasSize(2);
    }

    @Test
    void aResyncOfOneSessionNamesAReplicatedSession() {
        aLeadsBFollows();

        a.ha.resync(MAIN.getFixSessionId(), MAIN.getFixSessionType());

        await().until(() -> a.engine.meters.read("ha.snapshots") == 2);
        assertThatThrownBy(() -> a.ha.resync(MAIN.getFixSessionId(), FixSessionType.INITIATOR))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is not replicated");
    }

    @Test
    void onlyTheLeaderResyncs() {
        aLeadsBFollows();

        assertThatThrownBy(() -> b.ha.resync()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only the leader re-syncs its followers; node b is FOLLOWER");
    }

    @Test
    void aNodeWithdrawsFromElectionsOnlyThroughAnElectionThatCan() {
        a.start();
        assertThat(a.ha.getStatus().isElectableSupported()).isFalse();
        assertThatThrownBy(() -> a.ha.setElectable(false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FakeLeaderElection cannot decline election");

        a.election.electableSupported(true);
        a.ha.setElectable(false);

        HaStatus status = a.ha.getStatus();
        assertThat(status.isElectableSupported()).isTrue();
        assertThat(status.isElectable()).isFalse();
        assertThat(status.isTransferLeadershipSupported()).isFalse();
    }

    @Test
    void aSyncSendWithoutAFollowerIsCountedUnacknowledged() {
        a.election.elect(Role.LEADER, 3, "a");
        a.start();
        await().until(() -> a.engine.activity.contains("activate, stores open 0"));

        a.store.storeNextIncomingSeqNum(7);
        a.store.awaitReplicated();

        RecordingMeters meters = a.engine.meters;
        assertThat(meters.read("ha.sync.unacknowledged", Map.of("reason", "no_follower"))).isEqualTo(1);
        assertThat(meters.read("ha.sync.unacknowledged", Map.of("reason", "timed_out"))).isZero();
        assertThat(meters.recordings(HaMeters.SYNC_ACK_LATENCY, Map.of("mode", "sync_applied"))).isZero();
    }

    @Test
    void theNodeProbesItsReplicationThroughTheAlertsPublisherItNames() {
        a.start();

        assertThat(a.engine.probes.list()).extracting(Probe::getName)
                .containsExactlyInAnyOrder(HaProbes.FOLLOWERS, HaProbes.LEADER_CONNECTION, HaProbes.LEADERSHIP, HaProbes.SESSION_SETTINGS);
        assertThat(a.engine.probes.active.getAsBoolean()).isTrue();
        assertThat(a.sessionProbes.list()).extracting(Probe::getName).containsExactly(HaProbes.REPLICATION);

        a.ha.stop(Deadline.of(Duration.ofSeconds(2)));
        started.remove(a.ha);

        assertThat(a.engine.probes.active.getAsBoolean()).isFalse();
        a.start();
        assertThat(a.engine.probes.list()).as("replaced on restart").hasSize(4);
    }

    @Test
    void aLeaderAlertsOnAConnectedFollowerThatStoppedApplyingItsWrites() {
        aLeadsBFollows();
        await().until(() -> HaProbes.followersEvaluator(a.ha).evaluate().isEmpty());
        CountDownLatch stalled = new CountDownLatch(1);
        b.engine.store(MAIN).incomingWritesHeld = stalled;

        try {
            a.store.storeNextIncomingSeqNum(7);

            await().untilAsserted(() -> assertThat(HaProbes.followersEvaluator(a.ha).evaluate()).hasValueSatisfying(alert -> {
                assertThat(alert.getLevel()).isEqualTo(AlertLevel.MAJOR);
                assertThat(alert.getMessage()).startsWith("Follower b has not applied a write for");
            }));
            assertThat(a.ha.getStatus().getFollowerConnections()).singleElement().satisfies(follower -> {
                assertThat(follower.isConnected()).isTrue();
                assertThat(follower.isInSync()).as("as of its last ack, which is why the lag is watched").isTrue();
            });
        } finally {
            stalled.countDown();
        }
        await().until(() -> HaProbes.followersEvaluator(a.ha).evaluate().isEmpty());
    }

    @Test
    void aLeaderDropsAFollowerItsWritesWaitedTooLongForAndReportsItUntilItIsBackInSync() {
        aLeadsBFollows();
        await().until(() -> HaProbes.followersEvaluator(a.ha).evaluate().isEmpty());
        CountDownLatch stalled = new CountDownLatch(1);
        b.engine.store(MAIN).incomingWritesHeld = stalled;

        try {
            a.store.storeNextIncomingSeqNum(7);
            ByteBuffer message = ByteBuffer.allocate(4096);
            for (int seq = 1; a.ha.getStats().getDroppedTooSlow() == 0; seq++) {
                assertThat(seq).as("messages written before the follower was dropped").isLessThan(100_000);
                a.store.storeMessageSent(seq, message.clear());
            }

            assertThat(a.ha.getStatus().getDroppedFollowers()).singleElement()
                    .extracting(HaStatus.DroppedFollower::getNodeId).isEqualTo("b");
            assertThat(HaProbes.followersEvaluator(a.ha).evaluate()).hasValueSatisfying(alert -> {
                assertThat(alert.getLevel()).isEqualTo(AlertLevel.MAJOR);
                assertThat(alert.getMessage()).startsWith("Follower b dropped as too slow at");
            });
        } finally {
            stalled.countDown();
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> a.ha.getStatus().getDroppedFollowers().isEmpty());
        await().until(() -> HaProbes.followersEvaluator(a.ha).evaluate().isEmpty());
    }

    @Test
    void aLeaderAlertsUntilAFollowerIsConnectedAndInSync() {
        a.election.elect(Role.LEADER, 3, "a");
        a.start();
        await().until(() -> a.engine.activity.contains("activate, stores open 0"));

        assertThat(HaProbes.followersEvaluator(a.ha).evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.MAJOR);
            assertThat(alert.getMessage()).startsWith("No follower connected");
        });
        assertThat(HaProbes.leaderConnectionEvaluator(a.ha).evaluate()).as("a follower's probe").isEmpty();
        assertThat(HaProbes.leadershipEvaluator(a.ha).evaluate()).isEmpty();

        b.election.elect(Role.FOLLOWER, 3, "a");
        b.start();

        await().until(() -> HaProbes.followersEvaluator(a.ha).evaluate().isEmpty());
        await().until(() -> HaProbes.leaderConnectionEvaluator(b.ha).evaluate().isEmpty());
        assertThat(HaProbes.followersEvaluator(b.ha).evaluate()).as("a leader's probe").isEmpty();
    }

    @Test
    void aFollowerAlertsWhileNotConnectedToItsLeader() {
        b.election.elect(Role.FOLLOWER, 3, "a");
        b.start();
        await().until(() -> b.ha.getRole() == Role.FOLLOWER);

        assertThat(HaProbes.leaderConnectionEvaluator(b.ha).evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.MAJOR);
            assertThat(alert.getMessage()).startsWith("Not connected to the leader");
        });
    }

    @Test
    void aNodeWithoutRoleOrUnableToProveItLeadsAlertsCritical() {
        a.start();

        assertThat(HaProbes.leadershipEvaluator(a.ha).evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.CRITICAL);
            assertThat(alert.getMessage()).startsWith("No role");
        });

        a.election.elect(Role.LEADER, 3, "a");
        await().until(() -> HaProbes.leadershipEvaluator(a.ha).evaluate().isEmpty());
        a.election.leadershipValid(false);

        await().untilAsserted(() -> assertThat(HaProbes.leadershipEvaluator(a.ha).evaluate()).hasValueSatisfying(alert ->
                assertThat(alert.getMessage()).startsWith("Elected leader but unable to prove it")));
    }

    @Test
    void aSessionAlertsWhileItsFollowerRefusesToReplicateIt() {
        Node stranger = new Node("b", new RecordingEngine());
        a.election.elect(Role.LEADER, 3, "a");
        stranger.election.elect(Role.FOLLOWER, 3, "a");
        a.start();
        stranger.start();

        await().untilAsserted(() -> assertThat(a.sessionProbes.evaluate(HaProbes.REPLICATION)).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.MAJOR);
            assertThat(alert.getMessage()).isEqualTo("follower b refuses to replicate it: UNKNOWN_SESSION");
        }));
    }

    @Test
    void anUnknownAlertsPublisherIsRefused() {
        ReplicationFixEngineHa unknown = new ReplicationFixEngineHa(ReplicationHaSettings.builder()
                .nodeId("a").peer("a", addressA).alertsPublisherInstanceId("elsewhere")
                .leaderElection(FakeLeaderElection.Settings.builder().election(new FakeLeaderElection()).build())
                .build());
        unknown.bind(new RecordingEngine());

        assertThatThrownBy(unknown::start).hasMessageContaining("No alerts publisher elsewhere");
    }

    @Test
    void anUnknownMonitoringManagerIsRefused() {
        ReplicationFixEngineHa unknown = new ReplicationFixEngineHa(ReplicationHaSettings.builder()
                .nodeId("a").peer("a", addressA).monitoringInstanceId("elsewhere")
                .leaderElection(FakeLeaderElection.Settings.builder().election(new FakeLeaderElection()).build())
                .build());
        unknown.bind(new RecordingEngine());

        assertThatThrownBy(unknown::start).hasMessageContaining("No monitoring manager elsewhere");
    }

    @Test
    void namingASettingsStoreTheEngineDoesNotHaveIsRefused() {
        assertThatThrownBy(naming("absent", new RecordingEngine())::start)
                .hasMessageContaining("absent").hasMessageContaining("not a session settings store");
    }

    @Test
    void namingASettingsStoreWithoutAStorageIdentityIsRefused() {
        RecordingEngine engine = new RecordingEngine();
        engine.settingsStores.add(settingsStore("memory", Optional.empty()));

        assertThatThrownBy(naming("memory", engine)::start)
                .hasMessageContaining("memory").hasMessageContaining("cannot be replicated");
    }

    @Test
    void aSettingsStoreWithAStorageIdentityCanBeNamed() {
        RecordingEngine engine = new RecordingEngine();
        engine.settingsStores.add(settingsStore("files", Optional.of("file:1")));
        ReplicationFixEngineHa ha = naming("files", engine);

        ha.start();

        assertThat(ha.isStarted()).isTrue();
        ha.stop(Deadline.of(Duration.ofSeconds(2)));
    }

    private ReplicationFixEngineHa naming(String settingsStore, RecordingEngine engine) {
        ReplicationFixEngineHa ha = new ReplicationFixEngineHa(ReplicationHaSettings.builder()
                .nodeId("a").peer("a", addressA).replicatedSettingsStore(settingsStore)
                .leaderElection(FakeLeaderElection.Settings.builder().election(new FakeLeaderElection()).build())
                .build());
        ha.bind(engine);
        return ha;
    }

    private static FixSessionsSettingsStore settingsStore(String instanceId, Optional<String> storageIdentity) {
        FixSessionsSettingsStore store = mock(FixSessionsSettingsStore.class);
        when(store.getInstanceId()).thenReturn(instanceId);
        when(store.getStorageIdentity()).thenReturn(storageIdentity);
        return store;
    }

    @Test
    void aReplicatedSettingsStoreIsChangedOnlyWhereTheNodeLeads() {
        FixSessionsSettingsStore replicated = settingsStore("files", Optional.of("file:1"));
        a.start();

        assertThatThrownBy(() -> a.ha.requireSettingsStoreWritable(replicated))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("files").hasMessageContaining("leads");

        aLeadsBFollows();
        a.ha.requireSettingsStoreWritable(replicated);
    }

    @Test
    void aSettingsStoreOnlyComparedCanBeChangedAnywhere() {
        a.start();

        a.ha.requireSettingsStoreWritable(settingsStore("memory", Optional.empty()));
    }

    @Test
    void anEngineWithoutLeaderElectionIsRefused() {
        ReplicationFixEngineHa withoutElection = new ReplicationFixEngineHa(ReplicationHaSettings.builder()
                .nodeId("a").peer("a", addressA).build());
        withoutElection.bind(new RecordingEngine());

        assertThatThrownBy(withoutElection::start).hasMessageContaining("needs a leaderElection");
    }
}
