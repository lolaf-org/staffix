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
package org.lolaf.staffix.ha.leader;

import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.staffix.api.monitoring.Timer;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.ha.ReplicationFixEngineHa;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.ha.follower.RecordingProbes;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.SessionAnswer;
import org.lolaf.staffix.ha.wire.WireFormat;
import org.lolaf.staffix.ha.wire.WireFormatException;
import org.lolaf.staffix.tests.TestingClock;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestFollowerConnection extends FollowerConnectionHarness {

    @Test
    void theSenderWritesOnlyItsOwnBuffersWhichAConnectionStoppingCannotFree() throws InterruptedException {
        FixSessionMessagesStore main = replicated("main");
        Thread[] sender = new Thread[1];
        connection = new FollowerConnection(ioSession, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a",
                Duration.ofHours(1), Duration.ZERO, 4, Integer.MAX_VALUE, new BackoffIdleStrategy(), stats, loop -> {
            sender[0] = new Thread(loop);
            sender[0].start();
        });
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        await().alias("the session's announcement").until(() -> batchesSent.get() == 1);
        main.storeNextIncomingSeqNum(1);
        await().until(() -> batchesSent.get() == 2);

        connection.disconnected();
        sender[0].join();

        verify(ioSession, times(1)).borrow(anyInt());
        verify(ioSession, never()).send(any(ByteBuffer.class), any(), any(), eq(true));
        verify(ioSession, never()).unborrow(any());
    }

    @Test
    void aFollowerOwingAnAckAndSilentForTheStallTimeoutIsDropped() throws InterruptedException {
        FixSessionMessagesStore main = replicated("main");
        Thread[] sender = new Thread[1];
        connection = connectionStallingAfter(Duration.ofMillis(100), sender);
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);

        main.storeNextIncomingSeqNum(1);

        sender[0].join(5_000);
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 STALLED");
    }

    @Test
    void aSlowFollowerStillAckingEachReadIsKept() {
        FixSessionMessagesStore main = replicated("main");
        Thread[] sender = new Thread[1];
        connection = connectionStallingAfter(Duration.ofMillis(100), sender);
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        try {
            for (int seq = 1; seq <= 10; seq++) {
                main.storeNextIncomingSeqNum(seq);
                await().pollDelay(Duration.ofMillis(40)).until(() -> true);
                connection.onAck(EPOCH, 0, true, 0);
            }

            assertThat(connection.getState()).as("behind all along, never silent").isEqualTo(FollowerConnection.State.STREAMING);
        } finally {
            connection.disconnected();
        }
    }

    @Test
    void aWriteAfterALongIdleIsNotTakenForAStall() {
        FixSessionMessagesStore main = replicated("main");
        Thread[] sender = new Thread[1];
        connection = connectionStallingAfter(Duration.ofMillis(100), sender);
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        try {
            await().pollDelay(Duration.ofMillis(300)).until(() -> true);

            long offset = ha.getReplicationLogs().get(0).getTailOffset();
            main.storeNextIncomingSeqNum(1);
            await().pollDelay(Duration.ofMillis(30)).until(() -> true);
            connection.onAck(EPOCH, offset, true, 0);
            await().pollDelay(Duration.ofMillis(150)).until(() -> true);

            assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        } finally {
            connection.disconnected();
        }
    }

    @Test
    void aSenderDroppedWhileAHeartbeatIsDueWritesNothingAfterItsClose() {
        ha = engine(1024);
        Runnable[] sender = new Runnable[1];
        connection = new FollowerConnection(ioSession, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a",
                Duration.ofNanos(1), Duration.ZERO, 4, Integer.MAX_VALUE, new BackoffIdleStrategy(), stats, loop -> sender[0] = loop);
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(1);
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        for (int seq = 2; seq < 200; seq++) {
            store.storeNextIncomingSeqNum(seq);
        }

        sender[0].run();

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 TOO_SLOW");
        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("HEARTBEAT"));
    }

    @Test
    void theSenderWaitsWhileEverySendBufferIsInFlightAndResumesOnceOneIsWritten() {
        FixSessionMessagesStore main = replicated("main");
        Thread[] sender = new Thread[1];
        connection = new FollowerConnection(ioSession, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a", Duration.ofHours(1),
                Duration.ZERO, 4, Integer.MAX_VALUE, new BackoffIdleStrategy(), stats, loop -> {
            sender[0] = new Thread(loop);
            sender[0].start();
        });
        holdingWrites = true;
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        try {
            await().alias("the session's announcement").until(() -> batchesSent.get() == 1);
            for (int seq = 1; seq <= 3; seq++) {
                main.storeNextIncomingSeqNum(seq);
                int sent = seq + 1;
                await().until(() -> batchesSent.get() == sent);
            }

            await().during(Duration.ofMillis(200)).alias("all four in flight, nothing to send")
                    .until(() -> stats.getSendWaits() == 0);

            main.storeNextIncomingSeqNum(4);

            await().during(Duration.ofMillis(200)).until(() -> batchesSent.get() == 4);
            assertThat(stats.getSendWaits()).as("a write held back").isEqualTo(1);
            main.storeNextIncomingSeqNum(5);
            await().during(Duration.ofMillis(200)).alias("the same wait").until(() -> stats.getSendWaits() == 1);
            unwrittenBatches.poll().run();
            await().during(Duration.ofMillis(200)).alias("the batch written last is kept").until(() -> batchesSent.get() == 4);
            unwrittenBatches.poll().run();
            await().until(() -> batchesSent.get() == 5);
        } finally {
            connection.disconnected();
        }
    }

    @Test
    void aWriteHeldBackByTheWriteWatermarkIsASendWaitButTheWatermarkAloneIsNot() {
        FixSessionMessagesStore main = replicated("main");
        connection = senderRunningConnection();
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        try {
            await().alias("the session's announcement").until(() -> batchesSent.get() == 1);

            connection.onWriteWatermark(true);
            await().during(Duration.ofMillis(200)).until(() -> stats.getSendWaits() == 0);

            main.storeNextIncomingSeqNum(1);
            await().until(() -> stats.getSendWaits() == 1);

            connection.onWriteWatermark(false);
            await().until(() -> batchesSent.get() == 2);
        } finally {
            connection.disconnected();
        }
    }

    @Test
    void aSnapshotHeldBackIsASendWait() {
        ha.replicatingStore(settings("main"), storeHolding(1_001L), new RecordingProbes());
        connection = senderRunningConnection(4 << 10);
        holdingWrites = true;
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        try {
            await().alias("the session's announcement").until(() -> batchesSent.get() == 1);

            connection.onSessionAnswer(EPOCH, 0, SessionAnswer.SNAPSHOT_NEEDED);

            await().until(() -> stats.getSendWaits() == 1);
            assertThat(batchesSent.get()).as("all four in flight").isEqualTo(4);
        } finally {
            connection.disconnected();
        }
    }

    @Test
    void anAckTellsWhatTheFollowerHasYetToStore() {
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);

        connection.onAck(EPOCH, 0, true, 7);

        assertThat(connection.getFollowerPendingWrites()).isEqualTo(7);
    }

    @Test
    void aFreshFollowerIsStreamedFromTheLogTail() {
        replicated("main").storeNextIncomingSeqNum(5);

        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        fill();

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        assertThat(connection.getFollowerId()).isEqualTo("follower-b");
        assertThat(sent.frames).containsExactly(
                "WELCOME epoch=7 version=1 leader=leader-a resume=-1 lanes=1",
                "SESSION epoch=7 key=0 INITIATOR SYNC_APPLIED id=" + SESSION + " identity=null");
    }

    @Test
    void aLaneAnnouncesAndStreamsOnlyTheSessionsItCarries() {
        ha = new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(1 << 16).replicationLanes(2).build());
        connection = connection();
        FixSessionMessagesStore main = replicated("main");
        FixSessionMessagesStore other = replicated("other");
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 1);
        main.storeNextIncomingSeqNum(5);
        other.storeNextIncomingSeqNum(6);

        fill();

        assertThat(ha.getReplicationLogs().get(0).getTailOffset()).as("main, key 0").isPositive();
        assertThat(sent.frames).hasSize(3);
        assertThat(sent.frames.get(0)).endsWith("lanes=2");
        assertThat(sent.frames.get(1)).startsWith("SESSION epoch=7 key=1 ");
        assertThat(sent.frames.get(2)).contains("key=1 STORE_INCOMING_SEQ_NUM seq=6");
        assertThat(connection.carries(0)).isFalse();
        assertThat(connection.carries(1)).isTrue();
    }

    @Test
    void aLaneIsInSyncOnceTheSessionsItCarriesAre() {
        ha = new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(1 << 16).replicationLanes(2).build());
        connection = connection();
        replicated("main");
        replicated("other");
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 1);
        fill();

        connection.onSessionAnswer(EPOCH, 1, SessionAnswer.REPLICATING);

        assertThat(connection.isLaneInSync(2)).as("main is another lane's").isTrue();
    }

    @Test
    void aFollowerResumesAfterItsLastAppliedEventStillInTheLog() {
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(5);
        // events without a message are all one length, so the second starts halfway to the third
        store.storeNextIncomingSeqNum(6);
        long third = ha.getReplicationLogs().get(0).getTailOffset();
        long second = third / 2;
        store.storeNextIncomingSeqNum(7);

        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        fill();

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        assertThat(sent.frames).containsExactly(
                "WELCOME epoch=7 version=1 leader=leader-a resume=" + second + " lanes=1",
                "SESSION epoch=7 key=0 INITIATOR SYNC_APPLIED id=" + SESSION + " identity=null",
                "STORE_EVENT epoch=7 offset=" + second + " key=0 STORE_INCOMING_SEQ_NUM seq=6 message=",
                "STORE_EVENT epoch=7 offset=" + third + " key=0 STORE_INCOMING_SEQ_NUM seq=7 message=");
    }

    @Test
    void aFollowerWhoseLastAppliedEventWasOverwrittenWhileAwayStreamsFromTheTailForASnapshot() {
        ha = engine(1024);
        connection = connection();
        FixSessionMessagesStore store = replicated("main");
        for (int seq = 1; seq < 200; seq++) {
            store.storeNextIncomingSeqNum(seq);
        }

        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        assertThat(sent.frames).first().asString().contains("resume=-1 ");
        assertThat(stats.getDroppedTooSlow()).as("nobody gated the writes while it was away").isZero();
    }

    @Test
    void aFollowerDroppedAsTooSlowIsAcceptedOnceItsHoldOffHasPassed() {
        TestingClock clock = new TestingClock();
        clock.toAdvanceableTime(Instant.parse("2026-11-01T10:00:00Z"));
        stats = new ReplicationStats(new SlowFollowers(Duration.ofSeconds(1), clock));
        stats.droppedTooSlow("follower-b", 0);
        connection = connection();
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 0);
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);

        clock.advance(Duration.ofSeconds(1));
        connection = connection();
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 0);

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        assertThat(sent.frames).last().asString().startsWith("WELCOME");
    }

    @Test
    void aFollowerOfAnEarlierEpochIsNotResumed() {
        replicated("main").storeNextIncomingSeqNum(5);

        connection.onHello(EPOCH - 1, WireFormat.VERSION, "follower-b", EPOCH - 1, 0, 0);

        assertThat(sent.frames).first().asString().contains("resume=-1 ");
    }

    @Test
    void aFollowerOpeningALanePastTheCountIsClosed() {
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 1);

        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 NO_SUCH_LANE");
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
    }

    @Test
    void aFollowerOfAnotherVersionIsClosed() {
        connection.onHello(EPOCH, WireFormat.VERSION + 1, "follower-b", 0, -1, 0);

        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 VERSION_MISMATCH");
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        verify(ioSession).stop(any(Deadline.class));
    }

    @Test
    void aLeaderBehindItsFollowersEpochClosesTheStreamAsStale() {
        connection.onHello(EPOCH + 1, WireFormat.VERSION, "follower-b", EPOCH + 1, 0, 0);

        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 STALE_EPOCH");
    }

    @Test
    void aFrameAFollowerNeverSendsIsAProtocolError() {
        assertThatThrownBy(() -> connection.onHeartbeat(EPOCH, 0)).isInstanceOf(WireFormatException.class)
                .hasMessage("Unexpected HEARTBEAT frame");
    }

    @Test
    void aFollowerAWriteWaitedTheLogFullTimeoutForIsDroppedAsTooSlow() {
        ha = engine(1024);
        connection = connection();
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(1);
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        for (int seq = 2; seq < 200; seq++) {
            store.storeNextIncomingSeqNum(seq);
        }

        fill();

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("STORE_EVENT"));
        assertThat(sent.frames).filteredOn(frame -> frame.startsWith("CLOSE")).singleElement().asString()
                .startsWith("CLOSE epoch=7 TOO_SLOW");
        assertThat(stats.getDroppedTooSlow()).isEqualTo(1);
    }

    @Test
    void aStreamingFollowerHoldsWritesBackUntilItReadsWhatTheyWouldOverwrite() throws InterruptedException {
        ha = new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(1024)
                .logFullTimeout(Duration.ofMinutes(1)).build());
        connection = connection();
        FixSessionMessagesStore store = replicated("main");
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 0);
        CountDownLatch written = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            for (int seq = 1; seq < 200; seq++) {
                store.storeNextIncomingSeqNum(seq);
            }
            written.countDown();
        });
        writer.start();

        assertThat(written.await(100, TimeUnit.MILLISECONDS)).as("held back by the follower").isFalse();
        while (written.getCount() > 0) {
            fill();
        }
        writer.join();
        fill();

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        assertThat(sent.frames).filteredOn(frame -> frame.startsWith("STORE_EVENT")).hasSize(199);
    }

    @Test
    void aFollowerDroppedAsTooSlowIsRefusedWhileHeldOff() {
        stats = new ReplicationStats(new SlowFollowers(Duration.ofMinutes(1), new TestingClock()));
        stats.droppedTooSlow("follower-b", 0);
        connection = connection();

        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 0);

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 RETRY_LATER");
    }

    @Test
    void aFollowerDroppedAsTooSlowIsAcceptedAtOnceWithoutAHoldOffAndClearedOnceInSync() {
        stats = new ReplicationStats(new SlowFollowers(Duration.ZERO, new TestingClock()));
        stats.droppedTooSlow("follower-b", 0);
        connection = connection();

        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 0);
        connection.onAck(EPOCH, -1, true, 0);

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        assertThat(stats.getSlowFollowers().notBackInSync()).isEmpty();
    }

    @Test
    void aFollowerNotStreamingHoldsNoWriteBack() {
        ha = engine(1024);
        connection = connection();
        FixSessionMessagesStore store = replicated("main");
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 0);
        connection.disconnected();

        for (int seq = 1; seq < 200; seq++) {
            store.storeNextIncomingSeqNum(seq);
        }

        assertThat(stats.getDroppedTooSlow()).isZero();
        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("CLOSE"));
    }

    @Test
    void aBatchTakesEventsUntilTheNextDoesNotFitWhichStartsTheNextBatch() {
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(1);
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        fill();
        sent.frames.clear();
        for (int seq = 2; seq <= 4; seq++) {
            store.storeNextIncomingSeqNum(seq);
        }

        fill(3 * FrameEncoder.storeEventBytes(0) - 1);

        assertThat(sent.frames).hasSize(2);
        assertThat(sent.frames.get(0)).contains("seq=2");
        assertThat(sent.frames.get(1)).contains("seq=3");

        sent.frames.clear();
        fill();

        assertThat(sent.frames).singleElement().asString().contains("seq=4");
    }

    @Test
    void aSessionAppearingMidStreamIsAnnouncedBeforeItsFirstEvent() {
        FixSessionMessagesStore main = replicated("main");
        main.storeNextIncomingSeqNum(1);
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        fill();
        sent.frames.clear();

        replicated("other").storeNextOutgoingSeqNum(3);
        fill();

        assertThat(sent.frames).hasSize(2);
        assertThat(sent.frames.get(0)).isEqualTo("SESSION epoch=7 key=1 INITIATOR SYNC_APPLIED id=default.other:VERSION_44:SENDER->TARGET identity=null");
        assertThat(sent.frames.get(1)).contains("key=1 STORE_OUTGOING_SEQ_NUM seq=3");
    }

    @Test
    void aHeartbeatCarriesTheLogTailOnceAnIntervalPassedWithoutSending() {
        replicated("main").storeNextIncomingSeqNum(1);
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        sent.frames.clear();
        ByteBuffer batch = ByteBuffer.allocate(256);

        assertThat(connection.heartbeatIfDue(batch, 0)).isZero();
        int length = connection.heartbeatIfDue(batch, Duration.ofMillis(100).toNanos());

        decodeAll(batch.limit(length));
        assertThat(sent.frames).containsExactly("HEARTBEAT epoch=7 tail=" + ha.getReplicationLogs().get(0).getTailOffset());
    }

    @Test
    void aFollowerIsBehindFromAWriteUntilAnAckCoversItThenSampledAgainOnTheNextWriteItLacks() {
        Timer lagTimer = mock(Timer.class);
        stats.lagTimers((followerId, connection) -> lagTimer);
        FixSessionMessagesStore store = replicated("main");
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        long first = ha.getReplicationLogs().get(0).getTailOffset();
        store.storeNextIncomingSeqNum(5);
        fill();
        assertThat(connection.getLagNanos(System.nanoTime())).as("written, not acked").isPositive();

        long second = ha.getReplicationLogs().get(0).getTailOffset();
        store.storeNextIncomingSeqNum(6);
        fill();
        connection.onAck(EPOCH, first, true, 0);
        verify(lagTimer).record(anyLong(), eq(TimeUnit.NANOSECONDS));
        assertThat(connection.getLagNanos(System.nanoTime())).as("the sample is covered").isZero();

        fill();
        assertThat(connection.getLagNanos(System.nanoTime())).as("sent, not acked yet").isPositive();
        connection.onAck(EPOCH, second, true, 0);
        verify(lagTimer, times(2)).record(anyLong(), eq(TimeUnit.NANOSECONDS));
        fill();
        assertThat(connection.getLagNanos(System.nanoTime())).as("caught up, nothing new written").isZero();
    }

    @Test
    void anAckRecordsWhatTheFollowerApplied() {
        connection.onAck(EPOCH, 4096, true, 0);

        assertThat(connection.getAppliedOffset()).isEqualTo(4096);
        assertThat(List.copyOf(sent.frames)).isEmpty();
    }

    @Test
    void aCleanHandOverClosesWithShutdownOnceEveryEventIsSent() {
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(5);
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        store.storeNextIncomingSeqNum(6);
        fill();

        assertThat(connection.handOver(Deadline.of(Duration.ofSeconds(1)))).isTrue();

        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 SHUTDOWN");
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
    }

    @Test
    void aFollowerNotSentEveryEventBeforeTheDeadlineIsDroppedWithoutShutdown() {
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(5);
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        store.storeNextIncomingSeqNum(6);

        assertThat(connection.handOver(Deadline.of(Duration.ofMillis(50)))).isFalse();

        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("CLOSE"));
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        verify(ioSession).stop(any(Deadline.class));
    }

    @Test
    void aSessionIsInSyncOnceTheFollowerSaysItReplicatesIt() {
        replicated("main").storeNextIncomingSeqNum(5);
        assertThat(connection.isSessionInSync(0)).as("before its hello").isFalse();
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        fill();
        assertThat(connection.getSyncState(0)).isEqualTo(SessionSyncState.ANNOUNCED);
        assertThat(connection.isSessionInSync(0)).as("announced").isFalse();

        connection.onSessionAnswer(EPOCH, 0, SessionAnswer.REPLICATING);

        assertThat(connection.isSessionInSync(0)).isTrue();
        assertThat(connection.isSessionInSync(1)).as("another session").isFalse();
    }

    @Test
    void aSessionTheFollowerDoesNotReplicateIsNeverInSync() {
        replicated("main").storeNextIncomingSeqNum(5);
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        fill();

        connection.onSessionAnswer(EPOCH, 0, SessionAnswer.UNKNOWN_SESSION);

        assertThat(connection.getSyncState(0)).isEqualTo(SessionSyncState.NOT_REPLICATED);
        assertThat(connection.isSessionInSync(0)).isFalse();
    }

    @Test
    void aSessionNoLongerReplicatedIsAnnouncedAgainAndLeftOutOfTheFollowersSync() {
        replicated("main").storeNextIncomingSeqNum(5);
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        fill();
        connection.onSessionAnswer(EPOCH, 0, SessionAnswer.REPLICATING);

        ha.onSessionNotReplicated(settings("main"));
        connection.reannounce(0);
        fill();
        connection.onSessionAnswer(EPOCH, 0, SessionAnswer.NOT_REPLICATED);

        assertThat(sent.frames).endsWith("SESSION epoch=7 key=0 INITIATOR NONE id=" + SESSION + " identity=null");
        assertThat(connection.getSyncState(0)).isEqualTo(SessionSyncState.NOT_REPLICATED);
        assertThat(connection.isLaneInSync(1)).as("a session not replicated is not waited for").isTrue();
    }

    @Test
    void aStatusForASessionNeverAnnouncedClosesTheStream() {
        connection.onSessionAnswer(EPOCH, 3, SessionAnswer.REPLICATING);

        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
    }

    @Test
    void theAddressIsWrittenOutOnce() {
        when(ioSession.getSocketAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 40000));
        FollowerConnection follower = connection();

        String address = follower.getStatus(0).getAddress();

        assertThat(address).isEqualTo("/127.0.0.1:40000");
        assertThat(follower.getStatus(0).getAddress()).isSameAs(address);
    }
}
