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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.alerting.AlertLevel;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.ha.FixEngineHa.ReplicatingStore;
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.staffix.api.ha.SwitchoverOutcome;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.ha.follower.RecordingEngine;
import org.lolaf.staffix.ha.follower.RecordingMeters;
import org.lolaf.staffix.ha.follower.RecordingProbes;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Two nodes, each an engine's high availability with an election the test decides, replicating over TCP.
 */
class TestReplicationFixEngineHa {

    private static final FixSessionSettings MAIN = RecordingEngine.session("main", FixSessionType.ACCEPTOR);

    private final InetSocketAddress addressA = new InetSocketAddress("localhost", freePort());
    private final InetSocketAddress addressB = new InetSocketAddress("localhost", freePort());
    private final List<ReplicationFixEngineHa> started = new ArrayList<>();
    private final Node a = new Node("a");
    private final Node b = new Node("b");

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean listening(InetSocketAddress address) {
        try (Socket socket = new Socket(address.getHostString(), address.getPort())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private final class Node {

        final FakeLeaderElection election = new FakeLeaderElection();
        final RecordingEngine engine;
        final ReplicationFixEngineHa ha;
        final RecordingProbes sessionProbes = new RecordingProbes();
        final ReplicatingStore store;

        Node(String nodeId) {
            this(nodeId, new RecordingEngine().with(MAIN));
        }

        Node(String nodeId, RecordingEngine engine) {
            this(nodeId, engine, Duration.ofMillis(50));
        }

        Node(String nodeId, RecordingEngine engine, Duration heartbeatInterval) {
            this.engine = engine;
            ha = new ReplicationFixEngineHa(ReplicationHaSettings.builder()
                    .nodeId(nodeId)
                    .peer("a", addressA)
                    .peer("b", addressB)
                    .logCapacity(1 << 16)
                    .heartbeatInterval(heartbeatInterval)
                    .reconnectInterval(Duration.ofMillis(50))
                    .switchoverTimeout(Duration.ofSeconds(2))
                    .followerLagAlertThreshold(Duration.ofMillis(200))
                    .leaderElection(FakeLeaderElection.Settings.builder().election(election).build())
                    .monitoringInstanceId("monitoring")
                    .alertsPublisherInstanceId("alerts")
                    .build());
            ha.bind(engine);
            store = ha.replicatingStore(MAIN, new RecordingEngine.RecordingStore().start(), sessionProbes);
        }

        Node start() {
            ha.start();
            started.add(ha);
            return this;
        }
    }

    @AfterEach
    void stop() {
        started.forEach(ha -> ha.stop(Deadline.of(Duration.ofSeconds(2))));
    }

    /**
     * Until b is in sync, a write is not streamed to it: the snapshot carries the store's state instead.
     */
    private void aLeadsBFollows() {
        a.election.elect(Role.LEADER, 3, "a");
        b.election.elect(Role.FOLLOWER, 3, "a");
        a.start();
        b.start();
        await().until(() -> a.engine.activity.contains("activate, stores open 0"));
        await().until(() -> b.engine.store(MAIN) != null && b.ha.getReplica().isComplete());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 6})
    void sendBuffersMustBeAPowerOfTwoOfAtLeastTwo(int sendBuffers) {
        ReplicationHaSettings settings = ReplicationHaSettings.builder().sendBuffers(sendBuffers).build();

        assertThatThrownBy(() -> new ReplicationFixEngineHa(settings)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sendBuffers");
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
    void aSwitchoverLogsTheSessionsOutAndTheFollowerTakesThemOverWithoutMovingThemAhead() {
        aLeadsBFollows();
        await().until(() -> a.engine.meters.read("ha.followers.in.sync") == 1);
        a.store.storeNextIncomingSeqNum(7);
        a.election.transfersWith(() -> {
            a.election.elect(Role.FOLLOWER, 4, "b");
            b.election.elect(Role.LEADER, 4, "b");
        });

        assertThat(a.ha.switchover()).isEqualTo(SwitchoverOutcome.TRANSFERRED);

        assertThat(a.engine.activity).contains("deactivate with logout");
        await().until(() -> b.engine.activity.contains("activate, stores open 0"));
        assertThat(b.engine.store(MAIN).writes).as("every write reached b before the transfer").contains("incoming 7")
                .doesNotContain("outgoing 10001");
        await().until(() -> a.ha.getStatus().getRole() == Role.FOLLOWER);
    }

    @Test
    void aSwitchoverWhoseTransferNeverLandsResumesAndALaterFailoverStillMovesTheSessionsAhead() {
        aLeadsBFollows();
        await().until(() -> a.engine.meters.read("ha.followers.in.sync") == 1);
        a.election.transfersWith(() -> {
        });

        assertThat(a.ha.switchover()).isEqualTo(SwitchoverOutcome.NOT_TRANSFERRED);

        assertThat(a.engine.activity).endsWith("deactivate with logout", "activate, stores open 0");
        await().until(() -> listening(addressA));
        await().until(() -> a.engine.meters.read("ha.followers.in.sync") == 1);
        a.election.elect(Role.FOLLOWER, 4, "b");
        b.election.elect(Role.LEADER, 4, "b");
        await().until(() -> b.engine.activity.contains("activate, stores open 0"));
        assertThat(b.engine.store(MAIN).writes).as("b reconnected since the hand over, which no longer holds")
                .endsWith("outgoing 10001");
    }

    @Test
    void aSwitchoverWaitsForAFollowerInSyncAndChangesNothingWithoutOne() {
        a.election.elect(Role.LEADER, 3, "a");
        a.start();
        await().until(() -> a.engine.activity.contains("activate, stores open 0"));
        a.election.transfersWith(() -> {
        });

        assertThat(a.ha.switchover()).isEqualTo(SwitchoverOutcome.NO_FOLLOWER_IN_SYNC);
        assertThat(a.engine.activity).containsExactly("activate, stores open 0");
    }

    @Test
    void onlyALeaderWithAnElectionThatCanTransferSwitchesOver() {
        aLeadsBFollows();

        assertThatThrownBy(() -> a.ha.switchover()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot transfer leadership");
        assertThatThrownBy(() -> b.ha.switchover()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only the leader switches over; node b is FOLLOWER");
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
        a.election.elect(Role.LEADER, 3, "a");
        a.start();
        await().until(() -> a.engine.activity.contains("activate, stores open 0"));
        FixSessionSettings late = RecordingEngine.session("late", FixSessionType.ACCEPTOR);
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

        a.ha.notReplicated(late);
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
    void theLeaderSeesItsFollowerInSyncAndTheFollowerItsLeader() {
        aLeadsBFollows();

        await().untilAsserted(() -> {
            HaStatus leader = a.ha.getStatus();
            assertThat(leader.getRole()).isEqualTo(Role.LEADER);
            assertThat(leader.getEpoch()).isEqualTo(3);
            assertThat(leader.isLeadershipValid()).isTrue();
            assertThat(leader.getRoleSince()).isNotNull();
            assertThat(leader.getLeader()).isNull();
            assertThat(leader.getFollowers()).singleElement().satisfies(follower -> {
                assertThat(follower.getNodeId()).isEqualTo("b");
                assertThat(follower.isConnected()).isTrue();
                assertThat(follower.isInSync()).isTrue();
                assertThat(follower.isSnapshotInProgress()).isFalse();
                assertThat(follower.getUnsentBytes()).isZero();
                assertThat(follower.getLagMillis()).isZero();
            });
        });
        HaStatus follower = b.ha.getStatus();
        assertThat(follower.getRole()).isEqualTo(Role.FOLLOWER);
        assertThat(follower.isLeadershipValid()).isFalse();
        assertThat(follower.getFollowers()).isEmpty();
        assertThat(follower.getLeader()).isNotNull();
        assertThat(follower.getLeader().getLeaderId()).isEqualTo("a");
        assertThat(follower.getLeader().isConnected()).isTrue();
        assertThat(follower.getLeader().isInSync()).isTrue();
    }

    @Test
    void eachNodeDescribesTheReplicatedSessionFromItsSide() {
        aLeadsBFollows();

        await().untilAsserted(() -> assertThat(a.ha.getSessionsStatus()).singleElement().satisfies(session -> {
            assertThat(session.getFixSessionId()).isEqualTo(MAIN.getFixSessionId());
            assertThat(session.isInSync()).isTrue();
            assertThat(session.isSharedStorage()).isFalse();
            assertThat(session.getNotReplicatedReason()).isNull();
            assertThat(session.getNextOutgoingSeqNum()).isEqualTo(-1);
        }));
        HaSessionStatus replicated = b.ha.getSessionsStatus().get(0);
        assertThat(replicated.isInSync()).isTrue();
        assertThat(replicated.getNextIncomingSeqNum()).isEqualTo(1);
        assertThat(replicated.getNextOutgoingSeqNum()).isEqualTo(1);
        assertThat(replicated.isHandedOver()).isFalse();
    }

    @Test
    void aNodeWhoseRoleIsUnknownDescribesItsSessionsWithoutReplication() {
        a.start();

        assertThat(a.ha.getStatus().getRole()).isEqualTo(Role.UNKNOWN);
        assertThat(a.ha.getStatus().getRoleSince()).isNull();
        assertThat(a.ha.getSessionsStatus()).singleElement().satisfies(session -> assertThat(session.isInSync()).isFalse());
    }

    @Test
    void aSessionBuiltWithoutReplicationIsDroppedByTheFollowerThenCaughtUpBySnapshotOnceReplicatedAgain() {
        aLeadsBFollows();
        RecordingEngine.RecordingStore replicaStore = b.engine.store(MAIN);

        b.engine.replace(MAIN.toBuilder()
                .haSessionSettings(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.NONE).build())
                .build());
        a.ha.notReplicated(MAIN);

        await().until(() -> !replicaStore.isStarted());
        assertThat(b.ha.getReplica().isComplete()).isTrue();
        assertThat(a.ha.getStatus().getFollowers()).singleElement().satisfies(follower -> assertThat(follower.isInSync()).isTrue());

        b.engine.replace(MAIN);
        a.ha.replicatingStore(MAIN, new RecordingEngine.RecordingStore().start(), new RecordingProbes());

        await().until(() -> b.engine.store(MAIN) != replicaStore && b.ha.getSessionsStatus().get(0).isInSync());
        assertThat(a.engine.meters.read("ha.snapshots")).as("b caught up twice").isEqualTo(2);
    }

    @Test
    void aReplicationModeChangedOnTheLeaderAloneIsRefusedByTheFollower() {
        aLeadsBFollows();
        await().until(() -> b.ha.getSessionsStatus().get(0).isInSync());
        FixSessionSettings sync = MAIN.toBuilder()
                .haSessionSettings(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.SYNC).build())
                .build();

        a.ha.replicatingStore(sync, new RecordingEngine.RecordingStore().start(), new RecordingProbes());

        await().until(() -> "MODE_MISMATCH".equals(a.ha.getSessionsStatus().get(0).getNotReplicatedReason()));
        assertThat(b.ha.getSessionsStatus().get(0).getNotReplicatedReason()).isEqualTo("MODE_MISMATCH");
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
        assertThat(leader.recordings(HaMeters.SYNC_ACK_LATENCY)).isEqualTo(1);
        await().until(() -> leader.read("ha.follower.unsent") == 0);
        assertThat(leader.read("ha.sync.unacknowledged", Map.of("reason", "no_follower"))).isZero();
        await().until(() -> leader.recordings(HaMeters.FOLLOWER_ACK_LATENCY, Map.of("follower", "b")) > 0);
        await().until(() -> leader.recordings(HaMeters.FOLLOWER_LAG, Map.of("follower", "b")) > 0);
        await().until(() -> leader.read("ha.follower.lag.pending") == 0);
    }

    @Test
    void aSyncSessionTimesItsAcksInItsOwnMetersOnlyWhenAsked() {
        RecordingMeters timedMeters = new RecordingMeters();
        RecordingMeters untimedMeters = new RecordingMeters();
        b.engine.replace(sync(MAIN, true));
        ReplicatingStore timed = a.ha.replicatingStore(sync(MAIN, true), new RecordingEngine.RecordingStore().start(), new RecordingProbes());
        aLeadsBFollows();

        timed.sessionMeters(timedMeters);
        timed.storeNextIncomingSeqNum(7);
        timed.awaitReplicated();
        ReplicatingStore untimed = a.ha.replicatingStore(sync(MAIN, false), new RecordingEngine.RecordingStore().start(), new RecordingProbes());
        untimed.sessionMeters(untimedMeters);
        untimed.storeNextIncomingSeqNum(8);
        untimed.awaitReplicated();

        assertThat(timedMeters.recordings(HaMeters.SESSION_SYNC_ACK_LATENCY)).isEqualTo(1);
        assertThat(untimedMeters.recordings(HaMeters.SESSION_SYNC_ACK_LATENCY)).isZero();
        assertThat(a.engine.meters.recordings(HaMeters.SYNC_ACK_LATENCY)).as("the engine's timer counts both").isEqualTo(2);
    }

    private static FixSessionSettings sync(FixSessionSettings settings, boolean syncAckLatencyEnabled) {
        return settings.toBuilder().haSessionSettings(FixSessionSettings.HaSessionSettings.builder()
                .replicationMode(ReplicationMode.SYNC).syncAckLatencyEnabled(syncAckLatencyEnabled).build()).build();
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
        assertThat(a.ha.getStatus().isCandidacySupported()).isFalse();
        assertThatThrownBy(() -> a.ha.setCandidate(false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FakeLeaderElection cannot decline candidacy");

        a.election.candidacySupported(true);
        a.ha.setCandidate(false);

        HaStatus status = a.ha.getStatus();
        assertThat(status.isCandidacySupported()).isTrue();
        assertThat(status.isCandidate()).isFalse();
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
        assertThat(meters.recordings(HaMeters.SYNC_ACK_LATENCY)).isZero();
    }

    @Test
    void theNodeProbesItsReplicationThroughTheAlertsPublisherItNames() {
        a.start();

        assertThat(a.engine.probes.list()).extracting(Probe::getName)
                .containsExactlyInAnyOrder(HaProbes.FOLLOWERS, HaProbes.LEADER_LINK, HaProbes.LEADERSHIP);
        assertThat(a.engine.probes.active.getAsBoolean()).isTrue();
        assertThat(a.sessionProbes.list()).extracting(Probe::getName).containsExactly(HaProbes.REPLICATION);

        a.ha.stop(Deadline.of(Duration.ofSeconds(2)));
        started.remove(a.ha);

        assertThat(a.engine.probes.active.getAsBoolean()).isFalse();
        a.start();
        assertThat(a.engine.probes.list()).as("replaced on restart").hasSize(3);
    }

    @Test
    void aLeaderAlertsOnAConnectedFollowerThatStoppedApplyingItsWrites() {
        aLeadsBFollows();
        await().until(() -> HaProbes.followers(a.ha).evaluate().isEmpty());
        CountDownLatch stalled = new CountDownLatch(1);
        b.engine.store(MAIN).incomingWritesHeld = stalled;

        try {
            a.store.storeNextIncomingSeqNum(7);

            await().untilAsserted(() -> assertThat(HaProbes.followers(a.ha).evaluate()).hasValueSatisfying(alert -> {
                assertThat(alert.getLevel()).isEqualTo(AlertLevel.MAJOR);
                assertThat(alert.getMessage()).startsWith("Follower b has not applied a write for");
            }));
            assertThat(a.ha.getStatus().getFollowers()).singleElement().satisfies(follower -> {
                assertThat(follower.isConnected()).isTrue();
                assertThat(follower.isInSync()).as("as of its last ack, which is why the lag is watched").isTrue();
            });
        } finally {
            stalled.countDown();
        }
        await().until(() -> HaProbes.followers(a.ha).evaluate().isEmpty());
    }

    @Test
    void aLeaderAlertsUntilAFollowerIsConnectedAndInSync() {
        a.election.elect(Role.LEADER, 3, "a");
        a.start();
        await().until(() -> a.engine.activity.contains("activate, stores open 0"));

        assertThat(HaProbes.followers(a.ha).evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.MAJOR);
            assertThat(alert.getMessage()).startsWith("No follower connected");
        });
        assertThat(HaProbes.leaderLink(a.ha).evaluate()).as("a follower's probe").isEmpty();
        assertThat(HaProbes.leadership(a.ha).evaluate()).isEmpty();

        b.election.elect(Role.FOLLOWER, 3, "a");
        b.start();

        await().until(() -> HaProbes.followers(a.ha).evaluate().isEmpty());
        await().until(() -> HaProbes.leaderLink(b.ha).evaluate().isEmpty());
        assertThat(HaProbes.followers(b.ha).evaluate()).as("a leader's probe").isEmpty();
    }

    @Test
    void aFollowerAlertsWhileNotConnectedToItsLeader() {
        b.election.elect(Role.FOLLOWER, 3, "a");
        b.start();
        await().until(() -> b.ha.getFollowedRole() == Role.FOLLOWER);

        assertThat(HaProbes.leaderLink(b.ha).evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.MAJOR);
            assertThat(alert.getMessage()).startsWith("Not connected to the leader");
        });
    }

    @Test
    void aNodeWithoutRoleOrUnableToProveItLeadsAlertsCritical() {
        a.start();

        assertThat(HaProbes.leadership(a.ha).evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.CRITICAL);
            assertThat(alert.getMessage()).startsWith("No role");
        });

        a.election.elect(Role.LEADER, 3, "a");
        await().until(() -> HaProbes.leadership(a.ha).evaluate().isEmpty());
        a.election.leadershipValid(false);

        await().untilAsserted(() -> assertThat(HaProbes.leadership(a.ha).evaluate()).hasValueSatisfying(alert ->
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
    void anEngineWithoutLeaderElectionIsRefused() {
        ReplicationFixEngineHa withoutElection = new ReplicationFixEngineHa(ReplicationHaSettings.builder()
                .nodeId("a").peer("a", addressA).build());
        withoutElection.bind(new RecordingEngine());

        assertThatThrownBy(withoutElection::start).hasMessageContaining("needs a leaderElection");
    }
}
