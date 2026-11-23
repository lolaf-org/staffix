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
package org.lolaf.staffix.ha.replication;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.ha.replication.journal.Journal;
import org.lolaf.staffix.ha.replication.journal.JournalCopy;
import org.lolaf.staffix.ha.replication.journal.JournalReplica;
import org.lolaf.staffix.ha.replication.journal.JournalStream;
import org.lolaf.staffix.ha.replication.spi.LatencyRecorder;
import org.lolaf.staffix.ha.replication.spi.RecordFlag;
import org.lolaf.staffix.ha.replication.spi.ReplicationMetrics;
import org.lolaf.staffix.ha.replication.spi.StreamAnswer;
import org.lolaf.staffix.ha.replication.spi.StreamReplicationMode;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Two nodes, {@code a} and {@code b}, over real connections, each replicating its own journal while it leads and
 * holding the other's while it follows.
 */
class TestReplicationNode {

    private static final String MAIN = "main";
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(5);

    private final Journal journalA;
    private final Journal journalB;
    private final JournalReplica replicaB;
    private final JournalCopy copyB;
    private final InetSocketAddress addressA;
    private final InetSocketAddress addressB;
    private final List<String> recordersAskedFor;
    private final ReplicationNode a;
    private final ReplicationNode b;

    TestReplicationNode() {
        this.journalA = new Journal();
        this.journalB = new Journal();
        this.replicaB = new JournalReplica();
        this.copyB = replicaB.configure(MAIN, StreamReplicationMode.SYNC_APPLIED);
        this.addressA = new InetSocketAddress("localhost", TestingPorts.findFree());
        this.addressB = new InetSocketAddress("localhost", TestingPorts.findFree());
        this.recordersAskedFor = new CopyOnWriteArrayList<>();
        this.a = node("a", addressA, journalA, new JournalReplica());
        this.b = node("b", addressB, journalB, replicaB);
    }

    private ReplicationNode node(String nodeId, InetSocketAddress bindAddress, Journal journal, JournalReplica replica) {
        ReplicationNode node = ReplicationNode.builder()
                .nodeId(nodeId)
                .bindAddress(bindAddress)
                .streamsToReplicate(journal)
                .replicaSinks(replica)
                .logCapacity(1 << 16)
                .heartbeatInterval(Duration.ofMillis(100))
                .reconnectInterval(Duration.ofMillis(50))
                .metrics(new ReplicationMetrics() {
                    @Override
                    public LatencyRecorder followerAckLatency(String followerId, int lane) {
                        recordersAskedFor.add(nodeId + " times " + followerId + " lane " + lane);
                        return LatencyRecorder.NONE;
                    }
                })
                .build();
        journal.writeThrough(node);
        return node;
    }

    private static Deadline stop() {
        return Deadline.of(Duration.ofSeconds(2));
    }

    @AfterEach
    void close() {
        a.close(stop());
        b.close(stop());
    }

    private JournalStream aLeadsBInSync() {
        JournalStream main = journalA.add(MAIN, StreamReplicationMode.SYNC_APPLIED);
        main.append("first");
        a.lead(1, stop());
        b.follow(addressA, () -> 1, stop());
        await().until(() -> b.leader().isInSync() && a.followers().isAnyInSync());
        return main;
    }

    @Test
    void aFollowerIsCaughtUpBySnapshotThenAcksTheLeadersAppends() {
        JournalStream main = aLeadsBInSync();

        main.append("second");

        assertThat(main.awaitAck(ACK_TIMEOUT)).isEqualTo(StreamWriter.Ack.ACKED);
        assertThat(copyB.getWrites()).containsExactly("begin state=2", "snapshot 1 first", "end state=2", "2 second");
        assertThat(a.isLeading()).isTrue();
        assertThat(b.isFollowing()).isTrue();
        assertThat(a.getLogBytesWritten()).isPositive();
        assertThat(recordersAskedFor).containsExactly("a times b lane 0");
    }

    @Test
    void theLeaderSeesItsFollowerInSync() {
        aLeadsBInSync();

        FollowersView followers = a.followers();
        assertThat(followers.countConnected()).isEqualTo(1);
        assertThat(followers.countInSync()).isEqualTo(1);
        assertThat(followers.isStreamInSync(0)).isTrue();
        assertThat(followers.getStreamRefusals(0)).isEmpty();
        assertThat(followers.getSnapshots()).isEqualTo(1);
        assertThat(followers.getSnapshotRecords()).isEqualTo(1);
        assertThat(followers.getDroppedFollowers()).isEmpty();
        assertThat(followers.getDroppedTooSlow()).isZero();
        assertThat(followers.getSendBuffersAllocated()).isZero();
        assertThat(followers.getSendWaits()).isNotNegative();
        assertThat(followers.getLaneStatuses()).singleElement().satisfies(lane -> {
            assertThat(lane.getNodeId()).isEqualTo("b");
            assertThat(lane.isConnected()).isTrue();
            assertThat(lane.isInSync()).isTrue();
            assertThat(lane.getCipher()).isNull();
        });
        await().until(() -> followers.getMaxUnsentBytes() == 0 && followers.getMaxLagNanos() == 0);
    }

    @Test
    void theFollowerSeesItsLeaderAndTheStreamsItHolds() {
        aLeadsBInSync();

        LeaderView leader = b.leader();
        assertThat(leader.isConnected()).isTrue();
        assertThat(leader.isEncrypted()).isFalse();
        assertThat(leader.isCopyInSync()).isTrue();
        assertThat(leader.getLanes()).isEqualTo(1);
        assertThat(leader.getStatus()).isNotNull().satisfies(status -> {
            assertThat(status.getLeaderId()).isEqualTo("a");
            assertThat(status.isInSync()).isTrue();
        });
        assertThat(leader.getStream(MAIN)).isNotNull().satisfies(stream -> {
            assertThat(stream.isReplicated()).isTrue();
            assertThat(stream.isInSync()).isTrue();
            assertThat(stream.getSink()).isSameAs(copyB);
            assertThat(stream.getLeaderMode()).isEqualTo(StreamReplicationMode.SYNC_APPLIED);
        });
        assertThat(leader.getStream("unknown")).isNull();
    }

    @Test
    void aStreamTheFollowerRefusesIsReportedToTheLeaderAndNotWaitedFor() {
        replicaB.configure(MAIN, StreamReplicationMode.SYNC_APPLIED, "leader-storage");
        JournalStream main = journalA.add(MAIN, StreamReplicationMode.SYNC_APPLIED);
        a.lead(1, stop());
        b.follow(addressA, () -> 1, stop());
        await().until(() -> !a.followers().getStreamRefusals(0).isEmpty());

        main.append("first");

        assertThat(main.awaitAck(ACK_TIMEOUT)).isEqualTo(StreamWriter.Ack.NO_FOLLOWER_IN_SYNC);
        assertThat(a.followers().getStreamRefusals(0)).isEqualTo(Map.of("b", StreamAnswer.SHARED_STORAGE));
        assertThat(a.followers().isStreamInSync(0)).isFalse();
        assertThat(b.leader().getStream(MAIN)).isNotNull().satisfies(stream -> {
            assertThat(stream.isReplicated()).isFalse();
            assertThat(stream.getNotReplicatedAnswer()).isEqualTo(StreamAnswer.SHARED_STORAGE);
        });
    }

    @Test
    void aResyncSendsTheStreamAgainBySnapshot() {
        aLeadsBInSync();
        copyB.getWrites().clear();

        a.resync(0);

        await().untilAsserted(() -> assertThat(copyB.getWrites()).startsWith("begin state=2"));
        assertThat(a.followers().getSnapshots()).isEqualTo(2);
    }

    @Test
    void aFollowerHandedOverTakesOverWhatItHoldsOnceItsSinksStop() {
        aLeadsBInSync();

        assertThat(a.handOver(stop())).isTrue();
        await().until(() -> b.leader().getStream(MAIN).isHandedOver());
        a.standDown(stop());
        b.lead(2, stop());
        a.follow(addressB, () -> 2, stop());

        assertThat(copyB.isStopped()).isTrue();
        assertThat(b.leader().getStream(MAIN)).as("the copy is the application's now").isNull();
        assertThat(a.isLeading()).isFalse();
        await().until(() -> a.leader().isConnected() && b.followers().countConnected() == 1);
    }

    @Test
    void aNodeStandingDownNeitherLeadsNorFollows() {
        aLeadsBInSync();

        a.standDown(stop());
        b.standDown(stop());

        assertThat(a.isLeading()).isFalse();
        assertThat(a.handOver(stop())).isFalse();
        assertThat(a.followers().getLaneStatuses()).isEmpty();
        assertThat(b.isFollowing()).isFalse();
        assertThat(b.leader().getStatus()).isNull();
        assertThat(b.leader().isConnected()).isFalse();
        assertThat(b.leader().isInSync()).isFalse();
        assertThat(b.leader().isCopyInSync()).as("what it holds is kept").isTrue();
        assertThat(copyB.isStopped()).isFalse();
    }

    @Test
    void aWriterRefusesAPayloadLongerThanHalfTheLog() {
        try (StreamWriter writer = a.writer(0)) {
            ByteBuffer tooLong = ByteBuffer.allocate(writer.getMaxPayloadLength() + 1);

            assertThat(writer.getMaxPayloadLength()).isEqualTo(1 << 15);
            assertThatThrownBy(() -> writer.append(JournalStream.TEXT, RecordFlag.NONE, 1, tooLong, 0, tooLong.capacity()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(a.getLogBytesWritten()).isZero();
        }
    }

    @Test
    void aNodeIsBuiltWithItsDefaults() {
        assertThat(a.getLanes()).isEqualTo(1);
        assertThat(a.cipherAllocatesOnEveryFrame()).isFalse();
        assertThat(a.describeCipher()).isNotEmpty();
    }

    @Test
    void aNodeIsNotBuiltWithoutWhatItNeeds() {
        assertThatThrownBy(() -> ReplicationNode.builder().nodeId("c").streamsToReplicate(new Journal())
                .replicaSinks(new JournalReplica()).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("bindAddress is required");
    }
}
