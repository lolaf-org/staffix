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
package org.lolaf.staffix.ha.replication.leader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.ha.replication.encryption.EncryptionMode;
import org.lolaf.staffix.ha.replication.encryption.TestingKeyExchanges;
import org.lolaf.staffix.ha.replication.journal.Journal;
import org.lolaf.staffix.ha.replication.journal.JournalStream;
import org.lolaf.staffix.ha.replication.log.ReplicationLogs;
import org.lolaf.staffix.ha.replication.spi.StreamReplicationMode;
import org.lolaf.staffix.ha.replication.wire.AllocatedBytes;
import org.lolaf.staffix.ha.replication.spi.StreamAnswer;
import org.lolaf.staffix.ha.replication.wire.WireFormat;
import org.lolaf.staffix.ha.replication.spi.TestingReplicationClock;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Isolated
class TestFollowers {

    private static final long EPOCH = 7;
    private static final long NO_TIMEOUT = 0;
    private static final int KEY = 0;

    private final Journal journal;
    private final Followers followers;
    private final IdleStrategy idle;
    private final long offset;

    TestFollowers() {
        this.journal = Journal.ofCapacity(1 << 16);
        this.followers = new Followers();
        this.idle = new BackoffIdleStrategy();
        JournalStream main = journal.add("main", StreamReplicationMode.SYNC_APPLIED);
        main.append("first");
        offset = journal.getLogs().get(0).getTailOffset();
        main.append("second");
    }

    private static Journal twoLanes() {
        Journal twoLanes = new Journal(new ReplicationLogs(2, 1 << 16), Duration.ofMillis(50));
        twoLanes.add("main", StreamReplicationMode.SYNC_APPLIED);
        twoLanes.add("other", StreamReplicationMode.SYNC_APPLIED);
        return twoLanes;
    }

    private FollowerConnection inSyncFollower(long appliedOffset) {
        IOSession ioSession = mock(IOSession.class);
        when(ioSession.borrow(anyInt())).then(invocation -> ByteBuffer.allocate(invocation.getArgument(0)));
        FollowerConnection connection = new FollowerConnection(ioSession, journal.getLogs(), journal, () -> EPOCH, "leader-a",
                Duration.ofSeconds(1), Duration.ZERO, 4, Integer.MAX_VALUE, new BackoffIdleStrategy(), new ReplicationStats(new SlowFollowers(Duration.ZERO, new TestingReplicationClock())), loop -> {
        }, new TestingReplicationClock(), TestingKeyExchanges.CLEAR);
        connection.onHello(EPOCH, WireFormat.VERSION, Journal.APPLICATION_ID, "follower", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        connection.fillBatch(ByteBuffer.allocate(1 << 16));
        connection.onStreamAnswer(EPOCH, KEY, StreamAnswer.REPLICATING);
        connection.onAck(EPOCH, appliedOffset, true, 0);
        followers.add(connection);
        return connection;
    }

    @Test
    void withoutAFollowerInSyncTheWaitEndsAtOnceEvenWithoutATimeout() {
        assertThat(followers.awaitAck(KEY, offset, StreamReplicationMode.SYNC_APPLIED, NO_TIMEOUT, idle)).isEqualTo(Followers.Ack.NO_FOLLOWER_IN_SYNC);

        inSyncFollower(0).onStreamAnswer(EPOCH, KEY, StreamAnswer.SNAPSHOT_NEEDED);

        assertThat(followers.awaitAck(KEY, offset, StreamReplicationMode.SYNC_APPLIED, NO_TIMEOUT, idle)).isEqualTo(Followers.Ack.NO_FOLLOWER_IN_SYNC);
    }

    @Test
    void aRecordTheFollowerAppliedNeedsNoWait() {
        inSyncFollower(offset);

        assertThat(followers.awaitAck(KEY, offset, StreamReplicationMode.SYNC_APPLIED, NO_TIMEOUT, idle)).isEqualTo(Followers.Ack.ACKED);
    }

    @Test
    void anAckLateByMoreThanTheTimeoutEndsTheWait() {
        inSyncFollower(0);

        long start = System.nanoTime();
        assertThat(followers.awaitAck(KEY, offset, StreamReplicationMode.SYNC_APPLIED, TimeUnit.MILLISECONDS.toNanos(20), idle)).isEqualTo(Followers.Ack.TIMED_OUT);
        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(20));
    }

    @Test
    void aFollowerAWaitTimedOutOnIsNotWaitedForAgainUntilItAppliesWhatItMissed() {
        FollowerConnection follower = inSyncFollower(0);
        assertThat(followers.awaitAck(KEY, offset, StreamReplicationMode.SYNC_APPLIED, TimeUnit.MILLISECONDS.toNanos(20), idle)).isEqualTo(Followers.Ack.TIMED_OUT);

        assertThat(CompletableFuture.supplyAsync(() -> followers.awaitAck(KEY, offset, StreamReplicationMode.SYNC_APPLIED, NO_TIMEOUT, new BackoffIdleStrategy())))
                .as("no second wait, even without a timeout").succeedsWithin(Duration.ofSeconds(5)).isEqualTo(Followers.Ack.TIMED_OUT);

        follower.onAck(EPOCH, offset, true, 0);

        assertThat(followers.awaitAck(KEY, offset, StreamReplicationMode.SYNC_APPLIED, NO_TIMEOUT, idle)).isEqualTo(Followers.Ack.ACKED);
    }

    @Test
    void theWaitEndsWhenTheFollowerAcks() {
        FollowerConnection follower = inSyncFollower(0);

        CompletableFuture<Followers.Ack> ack = CompletableFuture.supplyAsync(() -> followers.awaitAck(KEY, offset, StreamReplicationMode.SYNC_APPLIED, NO_TIMEOUT, new BackoffIdleStrategy()));
        follower.onAck(EPOCH, offset, true, 0);

        assertThat(ack).succeedsWithin(Duration.ofSeconds(5)).isEqualTo(Followers.Ack.ACKED);
    }

    @Test
    void aSyncReceivedWaitEndsOnceTheFollowerReceivedTheRecord() {
        FollowerConnection follower = inSyncFollower(0);

        follower.onReceived(EPOCH, offset);

        assertThat(followers.awaitAck(KEY, offset, StreamReplicationMode.SYNC_RECEIVED, NO_TIMEOUT, idle)).isEqualTo(Followers.Ack.ACKED);
    }

    @Test
    void aSyncAppliedWaitIsNotEndedByTheRecordReceivedOnly() {
        FollowerConnection follower = inSyncFollower(0);

        follower.onReceived(EPOCH, offset);

        assertThat(followers.awaitAck(KEY, offset, StreamReplicationMode.SYNC_APPLIED, TimeUnit.MILLISECONDS.toNanos(20), idle))
                .isEqualTo(Followers.Ack.TIMED_OUT);
    }

    @Test
    void aSyncReceivedWaitEndsOnAnApplyTheFollowerSentNoReceivedFor() {
        FollowerConnection follower = inSyncFollower(0);

        follower.onAck(EPOCH, offset, true, 0);

        assertThat(followers.awaitAck(KEY, offset, StreamReplicationMode.SYNC_RECEIVED, NO_TIMEOUT, idle)).isEqualTo(Followers.Ack.ACKED);
    }

    @Test
    void anAckWakesAWaitParkedLongerThanTheTest() {
        FollowerConnection follower = inSyncFollower(0);
        long parkNanos = TimeUnit.SECONDS.toNanos(30);
        IdleStrategy parking = new BackoffIdleStrategy(0, 0, parkNanos, parkNanos);
        followers.addWaiter(parking);
        CompletableFuture<Followers.Ack> ack = new CompletableFuture<>();
        Thread waiter = new Thread(() -> ack.complete(followers.awaitAck(KEY, offset, StreamReplicationMode.SYNC_APPLIED, NO_TIMEOUT, parking)));
        waiter.start();
        await().until(() -> waiter.getState() == Thread.State.TIMED_WAITING);

        follower.onAck(EPOCH, offset, true, 0);
        followers.wakeWaiters();

        assertThat(ack).succeedsWithin(Duration.ofSeconds(5)).isEqualTo(Followers.Ack.ACKED);
    }

    @Test
    void aFollowerDisconnectingReleasesTheWait() {
        FollowerConnection follower = inSyncFollower(0);

        CompletableFuture<Followers.Ack> ack = CompletableFuture.supplyAsync(() -> followers.awaitAck(KEY, offset, StreamReplicationMode.SYNC_APPLIED, NO_TIMEOUT, new BackoffIdleStrategy()));
        follower.disconnected();

        assertThat(ack).succeedsWithin(Duration.ofSeconds(5)).isEqualTo(Followers.Ack.NO_FOLLOWER_IN_SYNC);
    }

    /**
     * A closed connection stays listed until betty reports it gone, after its CLOSE flush: a reconnection meanwhile
     * would count the follower's writes twice.
     */
    @Test
    void theWritesPendingOnAFollowerAreCountedOnlyOnItsConnectionStreaming() {
        FollowerConnection closed = inSyncFollower(offset);
        closed.onAck(EPOCH, offset, true, 600);
        FollowerConnection reconnected = inSyncFollower(offset);
        reconnected.onAck(EPOCH, offset, true, 500);

        closed.disconnected();

        assertThat(followers.connections()).contains(closed);
        assertThat(followers.getPendingWrites()).isEqualTo(500);
    }

    @Test
    void aSyncWaitIsOnlyForTheLaneCarryingItsStream() {
        Journal twoLanes = twoLanes();
        twoLanes.get(0).append("");
        twoLanes.get(1).append("");
        IOSession ioSession = mock(IOSession.class);
        when(ioSession.borrow(anyInt())).then(invocation -> ByteBuffer.allocate(invocation.getArgument(0)));
        FollowerConnection connectionOfMain = new FollowerConnection(ioSession, twoLanes.getLogs(), twoLanes,
                () -> EPOCH, "leader-a", Duration.ofSeconds(1), Duration.ZERO, 4, Integer.MAX_VALUE, new BackoffIdleStrategy(),
                new ReplicationStats(new SlowFollowers(Duration.ZERO, new TestingReplicationClock())), loop -> {
        }, new TestingReplicationClock(), TestingKeyExchanges.CLEAR);
        connectionOfMain.onHello(EPOCH, WireFormat.VERSION, Journal.APPLICATION_ID, "follower", EPOCH, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        connectionOfMain.fillBatch(ByteBuffer.allocate(1 << 16));
        connectionOfMain.onStreamAnswer(EPOCH, 0, StreamAnswer.REPLICATING);
        connectionOfMain.onAck(EPOCH, twoLanes.getLogs().get(0).getTailOffset(), true, 0);
        followers.add(connectionOfMain);

        assertThat(followers.awaitAck(0, 0, StreamReplicationMode.SYNC_APPLIED, NO_TIMEOUT, idle)).isEqualTo(Followers.Ack.ACKED);
        assertThat(followers.awaitAck(1, 0, StreamReplicationMode.SYNC_APPLIED, NO_TIMEOUT, idle)).as("other's lane is not connected")
                .isEqualTo(Followers.Ack.NO_FOLLOWER_IN_SYNC);
    }

    @Test
    void aFollowerIsInSyncOnlyOnceEveryLaneIs() {
        Journal twoLanes = twoLanes();
        FollowerConnection laneOfMain = laneConnection(twoLanes, 0);
        FollowerConnection laneOfOther = laneConnection(twoLanes, 1);
        laneOfMain.onStreamAnswer(EPOCH, 0, StreamAnswer.REPLICATING);

        assertThat(followers.isAnyFollowerInSync(2, 2)).as("other's lane still catching up").isFalse();

        laneOfOther.onStreamAnswer(EPOCH, 1, StreamAnswer.REPLICATING);

        assertThat(followers.isAnyFollowerInSync(2, 2)).isTrue();
        assertThat(followers.countFollowers(2, FollowerConnection::isConnected)).isEqualTo(1);
    }

    private FollowerConnection laneConnection(Journal twoLanes, int lane) {
        IOSession ioSession = mock(IOSession.class);
        when(ioSession.borrow(anyInt())).then(invocation -> ByteBuffer.allocate(invocation.getArgument(0)));
        FollowerConnection connection = new FollowerConnection(ioSession, twoLanes.getLogs(), twoLanes,
                () -> EPOCH, "leader-a", Duration.ofSeconds(1), Duration.ZERO, 4, Integer.MAX_VALUE, new BackoffIdleStrategy(),
                new ReplicationStats(new SlowFollowers(Duration.ZERO, new TestingReplicationClock())), loop -> {
        }, new TestingReplicationClock(), TestingKeyExchanges.CLEAR);
        connection.onHello(EPOCH, WireFormat.VERSION, Journal.APPLICATION_ID, "follower", EPOCH, -1, lane, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        connection.fillBatch(ByteBuffer.allocate(1 << 16));
        followers.add(connection);
        return connection;
    }

    @Test
    void everyFollowerInSyncMustApplyTheRecord() {
        inSyncFollower(offset);
        inSyncFollower(0);

        assertThat(followers.awaitAck(KEY, offset, StreamReplicationMode.SYNC_APPLIED, TimeUnit.MILLISECONDS.toNanos(1), idle)).isEqualTo(Followers.Ack.TIMED_OUT);
    }

    @Test
    void waitingAllocatesNothing() {
        inSyncFollower(offset);
        followers.awaitAck(KEY, offset, StreamReplicationMode.SYNC_APPLIED, NO_TIMEOUT, idle);

        long before = AllocatedBytes.ofCurrentThread();
        for (int i = 0; i < 100_000; i++) {
            followers.awaitAck(KEY, offset, StreamReplicationMode.SYNC_APPLIED, NO_TIMEOUT, idle);
        }

        assertThat(AllocatedBytes.ofCurrentThread() - before).as("less than a byte per wait").isLessThan(100_000);
    }

    @Test
    void theListOfFollowersIsBuiltOnlyWhenOneJoinsOrLeaves() {
        FollowerConnection first = inSyncFollower(offset);
        assertThat(followers.connections()).containsExactly(first).isSameAs(followers.connections());

        FollowerConnection second = inSyncFollower(offset);
        assertThat(followers.connections()).containsExactly(first, second);

        followers.remove(first);
        assertThat(followers.connections()).containsExactly(second);
    }
}
