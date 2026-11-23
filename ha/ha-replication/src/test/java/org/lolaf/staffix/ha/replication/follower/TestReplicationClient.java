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
package org.lolaf.staffix.ha.replication.follower;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;
import org.lolaf.betty.api.ss.WakeupSelectStrategy;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.staffix.ha.replication.StreamWriter;
import org.lolaf.staffix.ha.replication.TestingPorts;
import org.lolaf.staffix.ha.replication.encryption.TestingKeyExchanges;
import org.lolaf.staffix.ha.replication.journal.Journal;
import org.lolaf.staffix.ha.replication.journal.JournalCopy;
import org.lolaf.staffix.ha.replication.journal.JournalReplica;
import org.lolaf.staffix.ha.replication.journal.JournalStream;
import org.lolaf.staffix.ha.replication.leader.FollowerConnection;
import org.lolaf.staffix.ha.replication.leader.Followers;
import org.lolaf.staffix.ha.replication.leader.ReplicationServer;
import org.lolaf.staffix.ha.replication.leader.ReplicationStats;
import org.lolaf.staffix.ha.replication.leader.SlowFollowers;
import org.lolaf.staffix.ha.replication.log.ReplicationLogs;
import org.lolaf.staffix.ha.replication.spi.TestingReplicationClock;
import org.lolaf.staffix.ha.replication.spi.StreamAnswer;
import org.lolaf.staffix.ha.replication.spi.StreamReplicationMode;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A leader's server and a follower's client over a real connection, the follower writing on its network threads
 * or on a sink writer thread.
 */
@ParameterizedClass
@ValueSource(ints = {0, 1})
class TestReplicationClient {

    private static final long EPOCH = 7;
    private static final String MAIN = "main";
    private static final String OTHER = "other";
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(5);

    @Parameter
    int sinkWriterThreads;

    private final Journal leader;
    private final JournalStream leaderMain;
    private final InetSocketAddress address;
    private final ReplicationServer server;
    private final JournalReplica replica;
    private final JournalCopy main;
    private final FollowerStreams followerStreams;
    private final ReplicationStats twoLanesStats;
    private ReplicationClient client;
    private ReplicationServer twoLanesServer;
    private ReplicationServer syncReceivedServer;
    private Journal syncReceivedLeader;
    private JournalStream syncReceivedMain;
    private JournalStream twoLanesMain;
    private JournalStream twoLanesOther;

    TestReplicationClient() {
        this.leader = Journal.ofCapacity(1 << 16);
        this.leaderMain = leader.add(MAIN, StreamReplicationMode.SYNC_APPLIED);
        this.address = new InetSocketAddress("localhost", TestingPorts.findFree());
        this.server = server(address, leader);
        this.replica = new JournalReplica();
        this.main = replica.configure(MAIN, StreamReplicationMode.SYNC_APPLIED);
        this.followerStreams = new FollowerStreams(replica);
        this.twoLanesStats = new ReplicationStats(new SlowFollowers(Duration.ZERO, new TestingReplicationClock()));
        leaderMain.append("first");
        server.start();
    }

    private static ReplicationServer server(InetSocketAddress address, Journal leader) {
        return server(address, leader, new ReplicationStats(new SlowFollowers(Duration.ZERO, new TestingReplicationClock())));
    }

    private static ReplicationServer server(InetSocketAddress address, Journal leader, ReplicationStats stats) {
        return new ReplicationServer(address, leader.getLogs(), leader, leader.getFollowers(), () -> EPOCH, "leader-a",
                Duration.ofMillis(100), Duration.ZERO, 4, Integer.MAX_VALUE, BackoffIdleStrategy::new,
                new WakeupSelectStrategy(10), stats, new TestingReplicationClock(), TestingKeyExchanges.CLEAR);
    }

    /**
     * A leader with two lanes, {@link #MAIN} on lane 0 and {@link #OTHER} on lane 1, and the client following it in
     * sync.
     */
    private JournalCopy followTwoLanes() {
        JournalCopy other = replica.configure(OTHER, StreamReplicationMode.ASYNC);
        Journal twoLanes = new Journal(new ReplicationLogs(2, 1 << 16), Duration.ofMillis(50));
        twoLanesMain = twoLanes.add(MAIN, StreamReplicationMode.SYNC_APPLIED);
        twoLanesOther = twoLanes.add(OTHER, StreamReplicationMode.ASYNC);
        InetSocketAddress twoLanesAddress = new InetSocketAddress("localhost", TestingPorts.findFree());
        twoLanesServer = server(twoLanesAddress, twoLanes, twoLanesStats);
        twoLanesServer.start();
        connect(twoLanesAddress);
        await().until(client::isInSync);
        return other;
    }

    private void connect() {
        connect(address);
        await().until(() -> client.getConnection(0) != null
                && client.getConnection(0).getState() == LeaderConnection.State.STREAMING);
    }

    private void connect(InetSocketAddress address) {
        connect(address, leader.getLogs().get(0).getMaxRecordFrameBytes());
    }

    private void connect(InetSocketAddress address, int maxFrameBytes) {
        client = new ReplicationClient(address, followerStreams, () -> EPOCH, "follower-b", maxFrameBytes,
                Duration.ofMillis(50), new WakeupSelectStrategy(10), sinkWriterThreads, BackoffIdleStrategy::new,
                Duration.ofSeconds(1), TestingKeyExchanges.CLEAR);
        client.start();
    }

    /**
     * {@link #MAIN} as {@code SYNC_RECEIVED} on a leader of its own, the client following it in sync. Its copy writes
     * once a read is applied, after the RECEIVED, as a sink does.
     */
    private FollowerConnection followSyncReceived(int maxFrameBytes) {
        replica.configure(MAIN, StreamReplicationMode.SYNC_RECEIVED);
        main.setBatching(true);
        syncReceivedLeader = Journal.ofCapacity(1 << 16);
        syncReceivedMain = syncReceivedLeader.add(MAIN, StreamReplicationMode.SYNC_RECEIVED);
        InetSocketAddress syncReceivedAddress = new InetSocketAddress("localhost", TestingPorts.findFree());
        syncReceivedServer = server(syncReceivedAddress, syncReceivedLeader);
        syncReceivedMain.append("first");
        syncReceivedServer.start();
        connect(syncReceivedAddress, maxFrameBytes);
        await().until(() -> !syncReceivedLeader.getFollowers().connections().isEmpty()
                && syncReceivedLeader.getFollowers().connections().get(0).isStreamInSync(0));
        return syncReceivedLeader.getFollowers().connections().get(0);
    }

    private FollowerConnection inSync() {
        await().until(() -> !leader.getFollowers().connections().isEmpty()
                && leader.getFollowers().connections().get(0).isStreamInSync(0));
        return leader.getFollowers().connections().get(0);
    }

    /**
     * The entries applied from the log, not by a snapshot.
     */
    private static List<Long> appliedPositions(JournalCopy copy) {
        return copy.getWrites().stream().filter(write -> Character.isDigit(write.charAt(0)))
                .map(write -> Long.parseLong(write.substring(0, write.indexOf(' ')))).collect(Collectors.toList());
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.stop(Deadline.of(Duration.ofSeconds(2)));
        }
        server.stop(Deadline.of(Duration.ofSeconds(2)));
        if (twoLanesServer != null) {
            twoLanesServer.stop(Deadline.of(Duration.ofSeconds(2)));
        }
        if (syncReceivedServer != null) {
            syncReceivedServer.stop(Deadline.of(Duration.ofSeconds(2)));
        }
        followerStreams.close(Deadline.of(Duration.ofSeconds(2)));
    }

    /**
     * What a follower acknowledged as received is written before a promotion stops its client: the stop waits for
     * the read in progress, and does not interrupt the write it makes.
     */
    @Test
    void aStopWaitsForTheWriteOfWhatWasAcknowledgedAsReceived() {
        FollowerConnection connection = followSyncReceived(leader.getLogs().get(0).getMaxRecordFrameBytes());
        CountDownLatch writeHeld = new CountDownLatch(1);
        main.setWritesHeld(writeHeld);

        syncReceivedMain.append("second");
        await().until(() -> connection.getReceivedOffset() > connection.getAppliedOffset());
        ReplicationClient stopping = client;
        client = null;
        CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> stopping.stop(Deadline.of(Duration.ofSeconds(10))));
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> !stopped.isDone());
        writeHeld.countDown();

        assertThat(stopped).succeedsWithin(Duration.ofSeconds(10));
        assertThat(main.isHeldWriteInterrupted()).as("the write was not interrupted").isFalse();
        assertThat(main.getWrites()).contains("2 second");
    }

    /**
     * A sink writer thread applying a WELCOME takes the client's lock, so a stop waiting for that thread must not
     * hold it: it used to, and the two waited for each other forever.
     */
    @Test
    void aStopWaitingForTheSinkWriterLeavesTheClientLockFree() {
        FollowerConnection connection = followSyncReceived(leader.getLogs().get(0).getMaxRecordFrameBytes());
        CountDownLatch writeHeld = new CountDownLatch(1);
        main.setWritesHeld(writeHeld);

        syncReceivedMain.append("second");
        await().until(() -> connection.getReceivedOffset() > connection.getAppliedOffset());
        ReplicationClient stopping = client;
        client = null;
        CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> stopping.stop(Deadline.of(Duration.ofSeconds(10))));
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> !stopped.isDone());

        try {
            assertThat(CompletableFuture.runAsync(() -> {
                synchronized (stopping) {
                    assertThat(stopped).isNotDone();
                }
            })).succeedsWithin(Duration.ofSeconds(2));
        } finally {
            writeHeld.countDown();
        }
        assertThat(stopped).succeedsWithin(Duration.ofSeconds(10));
    }

    @Test
    void aSinkWriterThreadLeavesTheNetworkThreadAcknowledgingAsReceivedWhatItHasNotWrittenYet() {
        assumeTrue(sinkWriterThreads > 0, "the network thread writes itself otherwise");
        FollowerConnection connection = followSyncReceived(leader.getLogs().get(0).getMaxRecordFrameBytes());
        CountDownLatch writeHeld = new CountDownLatch(1);
        main.setWritesHeld(writeHeld);

        syncReceivedMain.append("second");
        long secondRecord = syncReceivedLeader.getLogs().get(0).getTailOffset();
        syncReceivedMain.append("third");

        await().until(() -> connection.getReceivedOffset() >= secondRecord);
        assertThat(main.getWrites()).as("the first still held").doesNotContain("2 second", "3 third");
        writeHeld.countDown();
        await().until(() -> main.getWrites().contains("3 third"));
    }

    @Test
    void aFullWriteQueueHoldsTheReadsBackUntilTheSinkWriterCatchesUp() {
        assumeTrue(sinkWriterThreads > 0, "no write queue otherwise");
        int smallFrames = 256;
        followSyncReceived(smallFrames);
        CountDownLatch writeHeld = new CountDownLatch(1);
        main.setWritesHeld(writeHeld);
        int lastPosition = 600;

        for (int position = 2; position <= lastPosition; position++) {
            syncReceivedMain.append("e");
        }
        writeHeld.countDown();

        await().until(() -> main.getWrites().contains(lastPosition + " e"));
        assertThat(appliedPositions(main)).as("every record written, in order").isSorted().endsWith((long) lastPosition);
    }

    @Test
    void aFreshFollowerIsCaughtUpBySnapshotThenStreamedTheLeadersAppends() {
        connect();
        FollowerConnection connection = inSync();
        assertThat(main.getWrites()).startsWith("begin state=2", "snapshot 1 first", "end state=2");
        assertThat(followerStreams.isInSync()).isTrue();
        main.getWrites().clear();

        leaderMain.append("second");
        leaderMain.append("third");
        long lastOffset = leader.getLogs().get(0).getTailOffset();
        leaderMain.append("fourth");

        await().untilAsserted(() -> assertThat(main.getWrites()).containsExactly("2 second", "3 third", "4 fourth"));
        await().until(() -> connection.getAppliedOffset() == lastOffset);
        assertThat(connection.isReportedInSync()).isTrue();
    }

    @Test
    void aSyncAppendWaitsForTheFollowersAckOnceItIsInSync() {
        connect();
        inSync();

        leaderMain.append("second");

        assertThat(leaderMain.awaitAck(ACK_TIMEOUT)).isEqualTo(StreamWriter.Ack.ACKED);
        assertThat(main.getWrites()).endsWith("2 second");
    }

    @Test
    void aStreamWhoseStorageTheFollowerSharesIsRefusedAndNotWaitedFor() {
        replica.configure(MAIN, StreamReplicationMode.SYNC_APPLIED, "leader-storage");
        connect();
        await().until(() -> !leader.getFollowers().connections().isEmpty()
                && leader.getFollowers().connections().get(0).getStreamAnswer(0) == StreamAnswer.SHARED_STORAGE);

        leaderMain.append("second");

        assertThat(leaderMain.awaitAck(ACK_TIMEOUT)).as("sent without waiting").isEqualTo(StreamWriter.Ack.NO_FOLLOWER_IN_SYNC);
        assertThat(main.getWrites()).isEmpty();
        assertThat(leader.getFollowers().connections().get(0).isStreamInSync(0)).isFalse();
    }

    @Test
    void aFollowerReconnectingResumesWithTheAppendsItMissedWithoutANewSnapshot() {
        connect();
        inSync();
        leaderMain.append("second");
        await().until(() -> main.getWrites().contains("2 second"));
        client.stop(Deadline.of(Duration.ofSeconds(2)));
        await().until(() -> leader.getFollowers().connections().isEmpty());
        main.getWrites().clear();

        leaderMain.append("third");
        connect();
        inSync();
        leaderMain.append("fourth");

        await().untilAsserted(() -> assertThat(main.getWrites()).containsExactly("3 third", "4 fourth"));
    }

    @Test
    void aFollowerOpensEveryLaneTheLeaderAnnouncesAndReplicatesTheStreamsOfEach() {
        JournalCopy other = followTwoLanes();

        assertThat(followerStreams.getLanes()).isEqualTo(2);
        assertThat(client.getConnection(1).getLane()).isEqualTo(1);
        twoLanesMain.append("main's");
        twoLanesOther.append("other's");

        await().untilAsserted(() -> {
            assertThat(main.getWrites()).endsWith("1 main's");
            assertThat(other.getWrites()).endsWith("1 other's");
        });
    }

    @Test
    void aFollowerWithOneLaneFailingIsNotInSyncAndResumesThatLaneWithoutASnapshot() {
        JournalCopy other = followTwoLanes();
        twoLanesOther.append("first");
        await().until(() -> other.getWrites().contains("1 first"));
        long snapshots = twoLanesStats.getSnapshots();
        other.setFailing(true);

        twoLanesOther.append("second");

        await().until(() -> !client.isInSync());
        twoLanesMain.append("main's");
        await().untilAsserted(() -> assertThat(main.getWrites()).as("the other lane carries on").endsWith("1 main's"));
        other.setFailing(false);
        await().until(client::isInSync);
        await().untilAsserted(() -> assertThat(other.getWrites()).endsWith("2 second"));
        assertThat(twoLanesStats.getSnapshots()).as("resumed").isEqualTo(snapshots);
    }
}
