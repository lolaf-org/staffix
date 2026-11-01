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
package org.lolaf.staffix.ha.follower;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lolaf.betty.api.ss.WakeupSelectStrategy;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.staffix.api.ha.FixEngineHa.ReplicatingStore;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.ha.ReplicationFixEngineHa;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.ha.leader.FollowerLink;
import org.lolaf.staffix.ha.leader.ReplicationPublisher;
import org.lolaf.staffix.ha.leader.ReplicationStats;
import org.lolaf.staffix.ha.leader.SlowFollowers;
import org.lolaf.staffix.tests.TestingClock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A leader's publisher and a follower's receiver over a real connection.
 */
class TestReplicationReceiver {

    private static final long EPOCH = 7;
    private static final FixSessionSettings MAIN = RecordingEngine.session("main", FixSessionType.ACCEPTOR).toBuilder()
            .haSessionSettings(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.SYNC).build())
            .build();
    private static final String MESSAGE = "8=FIX.4.4\u00019=5\u000135=D\u000110=000\u0001";

    private static final FixSessionSettings OTHER = RecordingEngine.session("other", FixSessionType.ACCEPTOR);

    private final ReplicationFixEngineHa leader = new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(1 << 16).build());
    private final InetSocketAddress address = new InetSocketAddress("localhost", freePort());
    private final ReplicationPublisher publisher = publisher(address, leader);
    private final RecordingEngine follower = new RecordingEngine().with(MAIN);
    private final Replica replica = new Replica(follower);
    private final ReplicatingStore leaderStore = leader.replicatingStore(MAIN, new RecordingEngine.RecordingStore().start(), new RecordingProbes());
    private ReplicationReceiver receiver;

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private ReplicationPublisher twoLinksPublisher;
    private final ReplicationStats twoLinksStats = new ReplicationStats(new SlowFollowers(Duration.ZERO, new TestingClock()));
    private ReplicatingStore twoLinksMain;
    private ReplicatingStore twoLinksOther;

    private static ReplicationPublisher publisher(InetSocketAddress address, ReplicationFixEngineHa leader) {
        return publisher(address, leader, new ReplicationStats(new SlowFollowers(Duration.ZERO, new TestingClock())));
    }

    private static ReplicationPublisher publisher(InetSocketAddress address, ReplicationFixEngineHa leader,
                                                  ReplicationStats stats) {
        return new ReplicationPublisher(address, leader.getReplicationLogs(), leader.getSessions(), leader.getFollowers(),
                () -> EPOCH, "leader-a", Duration.ofMillis(100), Duration.ZERO, 4, Integer.MAX_VALUE, BackoffIdleStrategy::new,
                new WakeupSelectStrategy(10), stats);
    }

    /**
     * A leader with two links, {@link #MAIN} on link 0 and {@link #OTHER} on link 1, and the receiver following it in
     * sync.
     */
    private void followTwoLinks() {
        follower.with(OTHER);
        ReplicationFixEngineHa twoLinks = new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(1 << 16)
                .replicationLinks(2).build());
        InetSocketAddress twoLinksAddress = new InetSocketAddress("localhost", freePort());
        twoLinksPublisher = publisher(twoLinksAddress, twoLinks, twoLinksStats);
        twoLinksMain = twoLinks.replicatingStore(MAIN, new RecordingEngine.RecordingStore().start(), new RecordingProbes());
        twoLinksOther = twoLinks.replicatingStore(OTHER, new RecordingEngine.RecordingStore().start(), new RecordingProbes());
        twoLinksPublisher.start();
        connect(twoLinksAddress);
        await().until(receiver::isInSync);
    }

    TestReplicationReceiver() {
        leaderStore.storeNextIncomingSeqNum(1);
        publisher.start();
    }

    private void connect() {
        connect(address);
        await().until(() -> receiver.getLink(0) != null && receiver.getLink(0).getState() == LeaderLink.State.STREAMING);
    }

    private void connect(InetSocketAddress address) {
        receiver = new ReplicationReceiver(address, replica, () -> EPOCH, "follower-b", leader.getReplicationLogs().get(0).getMaxEventFrameBytes(),
                Duration.ofMillis(50), new WakeupSelectStrategy(10));
        receiver.start();
    }

    private FollowerLink inSync() {
        await().until(() -> !leader.getFollowers().all().isEmpty() && leader.getFollowers().all().get(0).isInSync(0));
        return leader.getFollowers().all().get(0);
    }

    @AfterEach
    void stop() {
        if (receiver != null) {
            receiver.stop(Deadline.of(Duration.ofSeconds(2)));
        }
        publisher.stop(Deadline.of(Duration.ofSeconds(2)));
        if (twoLinksPublisher != null) {
            twoLinksPublisher.stop(Deadline.of(Duration.ofSeconds(2)));
        }
        replica.close(Deadline.of(Duration.ofSeconds(2)));
    }

    @Test
    void aFreshFollowerIsCaughtUpBySnapshotThenStreamedTheLeadersWrites() {
        connect();
        FollowerLink link = inSync();
        assertThat(follower.store(MAIN).writes).startsWith("reset");
        assertThat(replica.isComplete()).isTrue();
        follower.store(MAIN).writes.clear();

        leaderStore.storeMessageSent(5, ByteBuffer.wrap(MESSAGE.getBytes(StandardCharsets.US_ASCII)));
        leaderStore.storeNextIncomingSeqNum(2);
        long lastOffset = leader.getReplicationLogs().get(0).getTailOffset();
        leaderStore.storeNextOutgoingSeqNum(9);

        await().untilAsserted(() -> assertThat(follower.store(MAIN).writes)
                .containsExactly("message 5 " + MESSAGE, "incoming 2", "outgoing 9"));
        await().until(() -> link.getAppliedOffset() == lastOffset);
        assertThat(link.isReportedInSync()).isTrue();
    }

    @Test
    void aSyncSendWaitsForTheFollowersAckOnceItIsInSync() {
        connect();
        inSync();

        leaderStore.storeNextOutgoingSeqNum(2);
        leaderStore.awaitReplicated();

        assertThat(follower.store(MAIN).writes).endsWith("outgoing 2");
        assertThat(leader.getSessions().get(0).getUnacknowledgedSends()).isZero();
    }

    @Test
    void aSessionWhoseStorageTheFollowerSharesIsInSyncWithoutBeingWritten() {
        follower.nextStorageIdentity = leader.getSessions().get(0).getConfig().getStorageIdentity();
        connect();
        inSync();

        leaderStore.storeNextOutgoingSeqNum(2);
        leaderStore.awaitReplicated();

        assertThat(follower.store(MAIN).writes).isEmpty();
        assertThat(leader.getSessions().get(0).getUnacknowledgedSends()).isZero();
    }

    @Test
    void aFollowerReconnectingResumesWithTheWritesItMissedWithoutANewSnapshot() {
        connect();
        inSync();
        leaderStore.storeNextIncomingSeqNum(2);
        await().until(() -> follower.store(MAIN).writes.contains("incoming 2"));
        receiver.stop(Deadline.of(Duration.ofSeconds(2)));
        await().until(() -> leader.getFollowers().all().isEmpty());
        follower.store(MAIN).writes.clear();

        leaderStore.storeNextIncomingSeqNum(3);
        connect();
        inSync();
        leaderStore.storeNextIncomingSeqNum(4);

        await().untilAsserted(() -> assertThat(follower.store(MAIN).writes).containsExactly("incoming 3", "incoming 4"));
    }

    @Test
    void aFollowerOpensEveryLinkTheLeaderAnnouncesAndReplicatesTheSessionsOfEach() {
        followTwoLinks();

        assertThat(replica.getLinks()).isEqualTo(2);
        assertThat(receiver.getLink(1).getLink()).isEqualTo(1);
        twoLinksMain.storeNextIncomingSeqNum(5);
        twoLinksOther.storeNextIncomingSeqNum(6);

        await().untilAsserted(() -> {
            assertThat(follower.store(MAIN).writes).endsWith("incoming 5");
            assertThat(follower.store(OTHER).writes).endsWith("incoming 6");
        });
    }

    @Test
    void aFollowerWithOneLinkFailingIsNotInSyncAndResumesThatLinkWithoutASnapshot() {
        followTwoLinks();
        twoLinksOther.storeNextIncomingSeqNum(6);
        await().until(() -> follower.store(OTHER).writes.contains("incoming 6"));
        long snapshots = twoLinksStats.getSnapshots();
        follower.store(OTHER).messageWritesFail = true;

        twoLinksOther.storeMessageSent(5, ByteBuffer.wrap(MESSAGE.getBytes(StandardCharsets.US_ASCII)));

        await().until(() -> !receiver.isInSync());
        twoLinksMain.storeNextIncomingSeqNum(7);
        await().untilAsserted(() -> assertThat(follower.store(MAIN).writes).as("the other link carries on").endsWith("incoming 7"));
        follower.store(OTHER).messageWritesFail = false;
        await().until(receiver::isInSync);
        await().untilAsserted(() -> assertThat(follower.store(OTHER).writes).endsWith("message 5 " + MESSAGE));
        assertThat(twoLinksStats.getSnapshots()).as("resumed").isEqualTo(snapshots);
    }
}
