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
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.FixEngineHa.ReplicatingStore;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.ha.ReplicationFixEngineHa;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.ha.leader.FollowerLink;
import org.lolaf.staffix.ha.leader.ReplicationPublisher;

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

    private final ReplicationFixEngineHa leader = new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(1 << 16).build());
    private final InetSocketAddress address = new InetSocketAddress("localhost", freePort());
    private final ReplicationPublisher publisher = new ReplicationPublisher(address, leader.getReplicationLog(), leader.getSessions(),
            leader.getFollowers(), () -> EPOCH, "leader-a", Duration.ofMillis(100), 100);
    private final RecordingEngine follower = new RecordingEngine().with(MAIN);
    private final Replica replica = new Replica(follower);
    private final ReplicatingStore leaderStore = leader.replicatingStore(MAIN, new RecordingEngine.RecordingStore());
    private ReplicationReceiver receiver;

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    TestReplicationReceiver() {
        leaderStore.storeNextIncomingSeqNum(1);
        publisher.start();
    }

    private void connect() {
        receiver = new ReplicationReceiver(address, replica, () -> EPOCH, "follower-b", leader.getReplicationLog().getMaxEventFrameBytes(),
                Duration.ofMillis(50));
        receiver.start();
        await().until(() -> receiver.getLink() != null && receiver.getLink().getState() == LeaderLink.State.STREAMING);
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
        long lastOffset = leader.getReplicationLog().getTailOffset();
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
}
