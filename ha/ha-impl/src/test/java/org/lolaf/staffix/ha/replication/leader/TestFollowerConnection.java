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
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.staffix.ha.replication.spi.LatencyRecorder;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.ha.ReplicatedSessions;
import org.lolaf.staffix.ha.replication.encryption.EncryptionMode;
import org.lolaf.staffix.ha.ReplicationFixEngineHa;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.ha.TypedSessionId;
import org.lolaf.staffix.ha.replication.encryption.TestingKeyExchanges;
import org.lolaf.staffix.ha.replication.follower.RecordingProbes;
import org.lolaf.staffix.ha.replication.wire.CloseReason;
import org.lolaf.staffix.ha.replication.wire.FrameEncoder;
import org.lolaf.staffix.ha.replication.wire.FrameType;
import org.lolaf.staffix.ha.replication.wire.TestingFrameEncoder;
import org.lolaf.staffix.ha.replication.wire.StreamAnswer;
import org.lolaf.staffix.ha.replication.wire.WireFormat;
import org.lolaf.staffix.ha.replication.wire.WireFormatException;
import org.lolaf.staffix.ha.replication.spi.TestingReplicationClock;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
        }, new TestingReplicationClock(), TestingKeyExchanges.CLEAR);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
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
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        await().alias("the session's announcement").until(() -> batchesSent.get() == 1);
        main.storeNextIncomingSeqNum(1);
        await().until(() -> batchesSent.get() == 2);
        senderClock.advance(Duration.ofMillis(100));
        awaitSenderTurn();
        assertThat(connection.getState()).as("silent for the timeout, not past it").isEqualTo(FollowerConnection.State.STREAMING);

        senderClock.advance(Duration.ofNanos(1));

        sender[0].join(5_000);
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 STALLED");
    }

    @Test
    void aSlowFollowerStillAckingEachReadIsKept() {
        FixSessionMessagesStore main = replicated("main");
        Thread[] sender = new Thread[1];
        connection = connectionStallingAfter(Duration.ofMillis(100), sender);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        try {
            await().alias("the session's announcement").until(() -> batchesSent.get() == 1);
            for (int seq = 1; seq <= 10; seq++) {
                main.storeNextIncomingSeqNum(seq);
                int sent = seq + 1;
                await().until(() -> batchesSent.get() == sent);
                senderClock.advance(Duration.ofMillis(60));
                awaitSenderTurn();
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
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        try {
            await().alias("the session's announcement").until(() -> batchesSent.get() == 1);
            senderClock.advance(Duration.ofMillis(300));
            awaitSenderTurn();

            long offset = ha.getReplicationLogs().get(0).getTailOffset();
            main.storeNextIncomingSeqNum(1);
            await().until(() -> batchesSent.get() == 2);
            senderClock.advance(Duration.ofMillis(30));
            awaitSenderTurn();
            assertThat(connection.getState()).as("owing since the write only").isEqualTo(FollowerConnection.State.STREAMING);
            connection.onAck(EPOCH, offset, true, 0);
            senderClock.advance(Duration.ofMillis(150));
            awaitSenderTurn();

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
                Duration.ofNanos(1), Duration.ZERO, 4, Integer.MAX_VALUE, new BackoffIdleStrategy(), stats, loop -> sender[0] = loop, new TestingReplicationClock(), TestingKeyExchanges.CLEAR);
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(1);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
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
        connection = senderRunningConnection();
        holdingWrites = true;
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        try {
            await().alias("the session's announcement").until(() -> batchesSent.get() == 1);
            for (int seq = 1; seq <= 3; seq++) {
                main.storeNextIncomingSeqNum(seq);
                int sent = seq + 1;
                await().until(() -> batchesSent.get() == sent);
            }

            awaitSenderTurn();
            assertThat(stats.getSendWaits()).as("all four in flight, nothing to send").isZero();

            main.storeNextIncomingSeqNum(4);

            awaitSenderTurn();
            assertThat(batchesSent.get()).isEqualTo(4);
            assertThat(stats.getSendWaits()).as("a write held back").isEqualTo(1);
            main.storeNextIncomingSeqNum(5);
            awaitSenderTurn();
            assertThat(stats.getSendWaits()).as("the same wait").isEqualTo(1);
            unwrittenBatches.poll().run();
            awaitSenderTurn();
            assertThat(batchesSent.get()).as("the batch written last is kept").isEqualTo(4);
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
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        try {
            await().alias("the session's announcement").until(() -> batchesSent.get() == 1);

            connection.onWriteWatermark(true);
            awaitSenderTurn();
            assertThat(stats.getSendWaits()).isZero();

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
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        try {
            await().alias("the session's announcement").until(() -> batchesSent.get() == 1);

            connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);

            await().until(() -> stats.getSendWaits() == 1);
            assertThat(batchesSent.get()).as("all four in flight").isEqualTo(4);
        } finally {
            connection.disconnected();
        }
    }

    @Test
    void anAckTellsWhatTheFollowerHasYetToStore() {
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        connection.onAck(EPOCH, 0, true, 7);

        assertThat(connection.getFollowerPendingWrites()).isEqualTo(7);
    }

    @Test
    void aFreshFollowerIsStreamedFromTheLogTail() {
        replicated("main").storeNextIncomingSeqNum(5);

        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        assertThat(connection.getFollowerId()).isEqualTo("follower-b");
        assertThat(sent.frames).containsExactly(
                "WELCOME epoch=7 version=1 leader=leader-a resume=-1 lanes=1",
                "STREAM epoch=7 key=0 INITIATOR SYNC_APPLIED id=" + SESSION + " identity=null",
                "STREAMS_ANNOUNCED epoch=7 count=1");
    }

    @Test
    void theFirstAnnouncementEndsWithTheCountOfSessionsItAnnouncedOverAsManyBatchesAsItTakes() {
        replicated("main").storeNextIncomingSeqNum(5);
        replicated("other").storeNextIncomingSeqNum(6);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        fill(FrameEncoder.bytesWith(FrameType.STREAM, new TypedSessionId(FixSessionType.INITIATOR, SESSION).toStreamName()));
        assertThat(sent.frames).last().asString().startsWith("STREAM epoch=7 key=0 ");
        fill();

        assertThat(sent.frames).filteredOn(frame -> frame.startsWith("STREAM")).containsExactly(
                "STREAM epoch=7 key=0 INITIATOR SYNC_APPLIED id=" + SESSION + " identity=null",
                "STREAM epoch=7 key=1 INITIATOR SYNC_APPLIED id=default.other:VERSION_44:SENDER->TARGET identity=null",
                "STREAMS_ANNOUNCED epoch=7 count=2");
    }

    @Test
    void aLaneCarryingNoSessionAnnouncesNone() {
        ha = new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(1 << 16).replicationLanes(2).build());
        connection = connection();
        replicated("main").storeNextIncomingSeqNum(5);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 1, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        fill();

        assertThat(sent.frames).containsExactly("WELCOME epoch=7 version=1 leader=leader-a resume=-1 lanes=2",
                "STREAMS_ANNOUNCED epoch=7 count=0");
    }

    @Test
    void aLaneAnnouncesAndStreamsOnlyTheSessionsItCarries() {
        ha = new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(1 << 16).replicationLanes(2).build());
        connection = connection();
        FixSessionMessagesStore main = replicated("main");
        FixSessionMessagesStore other = replicated("other");
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, -1, 1, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        main.storeNextIncomingSeqNum(5);
        other.storeNextIncomingSeqNum(6);

        fill();

        assertThat(ha.getReplicationLogs().get(0).getTailOffset()).as("main, key 0").isPositive();
        assertThat(sent.frames).hasSize(4);
        assertThat(sent.frames.get(0)).endsWith("lanes=2");
        assertThat(sent.frames.get(1)).startsWith("STREAM epoch=7 key=1 ");
        assertThat(sent.frames.get(2)).isEqualTo("STREAMS_ANNOUNCED epoch=7 count=1");
        assertThat(sent.frames.get(3)).contains("key=1 STORE_INCOMING_SEQ_NUM seq=6");
        assertThat(connection.carries(0)).isFalse();
        assertThat(connection.carries(1)).isTrue();
    }

    @Test
    void aLaneIsInSyncOnceTheSessionsItCarriesAre() {
        ha = new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(1 << 16).replicationLanes(2).build());
        connection = connection();
        replicated("main");
        replicated("other");
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, -1, 1, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();

        connection.onStreamAnswer(EPOCH, 1, StreamAnswer.REPLICATING);

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

        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        assertThat(sent.frames).containsExactly(
                "WELCOME epoch=7 version=1 leader=leader-a resume=" + second + " lanes=1",
                "STREAM epoch=7 key=0 INITIATOR SYNC_APPLIED id=" + SESSION + " identity=null",
                "STREAMS_ANNOUNCED epoch=7 count=1",
                "RECORD epoch=7 offset=" + second + " key=0 STORE_INCOMING_SEQ_NUM seq=6 message=",
                "RECORD epoch=7 offset=" + third + " key=0 STORE_INCOMING_SEQ_NUM seq=7 message=");
    }

    @Test
    void aFollowerWhoseLastAppliedEventWasOverwrittenWhileAwayStreamsFromTheTailForASnapshot() {
        ha = engine(1024);
        connection = connection();
        FixSessionMessagesStore store = replicated("main");
        for (int seq = 1; seq < 200; seq++) {
            store.storeNextIncomingSeqNum(seq);
        }

        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        assertThat(sent.frames).first().asString().contains("resume=-1 ");
        assertThat(stats.getDroppedTooSlow()).as("nobody gated the writes while it was away").isZero();
    }

    @Test
    void aFollowerDroppedAsTooSlowIsAcceptedOnceItsHoldOffHasPassed() {
        TestingReplicationClock clock = new TestingReplicationClock();
        clock.toAdvanceableTime(Instant.parse("2026-11-01T10:00:00Z"));
        stats = new ReplicationStats(new SlowFollowers(Duration.ofSeconds(1), clock));
        stats.droppedTooSlow("follower-b", 0);
        connection = connection();
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);

        clock.advance(Duration.ofSeconds(1));
        connection = connection();
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        assertThat(sent.frames).last().asString().startsWith("WELCOME");
    }

    @Test
    void aFollowerOfAnEarlierEpochIsNotResumed() {
        replicated("main").storeNextIncomingSeqNum(5);

        connection.onHello(EPOCH - 1, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH - 1, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        assertThat(sent.frames).first().asString().contains("resume=-1 ");
    }

    @Test
    void aFollowerOpeningALanePastTheCountIsClosed() {
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, -1, 1, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 NO_SUCH_LANE");
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
    }

    @Test
    void aFollowerOfAnotherApplicationIsClosed() {
        connection.onHello(EPOCH, WireFormat.VERSION, 0x41505031, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        assertThat(sent.frames).singleElement().asString()
                .isEqualTo("CLOSE epoch=7 APPLICATION_MISMATCH leader replicates application 53464958, follower 41505031");
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
    }

    @Test
    void aFollowerOfAnotherVersionIsClosed() {
        connection.onHello(EPOCH, WireFormat.VERSION + 1, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 VERSION_MISMATCH");
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        verify(ioSession).stop(any(Deadline.class));
    }

    @Test
    void aLeaderBehindItsFollowersEpochClosesTheStreamAsStale() {
        connection.onHello(EPOCH + 1, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH + 1, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 STALE_EPOCH");
    }

    @Test
    void aFrameAFollowerNeverSendsIsAProtocolError() {
        assertThatThrownBy(() -> connection.onHeartbeat(EPOCH, 0)).isInstanceOf(WireFormatException.class)
                .hasMessage("Unexpected HEARTBEAT frame");
    }

    @Test
    void aFollowerAWriteWaitedTheLogFullTimeoutForIsDroppedAsTooSlowAndClosedByItsSender() {
        ha = engine(1024);
        Runnable[] sender = new Runnable[1];
        connection = new FollowerConnection(ioSession, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a",
                Duration.ofHours(1), Duration.ZERO, 4, Integer.MAX_VALUE, new BackoffIdleStrategy(), stats, loop -> sender[0] = loop,
                new TestingReplicationClock(), TestingKeyExchanges.CLEAR);
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(1);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        for (int seq = 2; seq < 200; seq++) {
            store.storeNextIncomingSeqNum(seq);
        }
        assertThat(connection.getState()).as("the write only marks it").isEqualTo(FollowerConnection.State.DROPPED_AS_TOO_SLOW);
        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("CLOSE"));
        verify(ioSession, never()).stop(any());

        sender[0].run();

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("RECORD"));
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
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
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
        assertThat(sent.frames).filteredOn(frame -> frame.startsWith("RECORD")).hasSize(199);
    }

    @Test
    void aFollowerDroppedAsTooSlowIsRefusedWhileHeldOff() {
        stats = new ReplicationStats(new SlowFollowers(Duration.ofMinutes(1), new TestingReplicationClock()));
        stats.droppedTooSlow("follower-b", 0);
        connection = connection();

        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 RETRY_LATER");
    }

    @Test
    void aFollowerDroppedAsTooSlowIsAcceptedAtOnceWithoutAHoldOffAndClearedOnceInSync() {
        stats = new ReplicationStats(new SlowFollowers(Duration.ZERO, new TestingReplicationClock()));
        stats.droppedTooSlow("follower-b", 0);
        connection = connection();

        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        connection.onAck(EPOCH, -1, true, 0);

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        assertThat(stats.getSlowFollowers().notBackInSync()).isEmpty();
    }

    @Test
    void aFollowerNotStreamingHoldsNoWriteBack() {
        ha = engine(1024);
        connection = connection();
        FixSessionMessagesStore store = replicated("main");
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        connection.disconnected();

        for (int seq = 1; seq < 200; seq++) {
            store.storeNextIncomingSeqNum(seq);
        }

        assertThat(stats.getDroppedTooSlow()).isZero();
        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("CLOSE"));
    }

    /**
     * Betty reports the disconnection only once the follower's side is closed: until then, a reader left open would
     * hold the writes back for the log full timeout.
     */
    @Test
    void aFollowerClosingTheStreamHoldsNoWriteBack() {
        ha = new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(1024)
                .logFullTimeout(Duration.ofSeconds(30)).build());
        connection = connection();
        FixSessionMessagesStore store = replicated("main");
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        connection.onClose(EPOCH, CloseReason.SHUTDOWN, "the follower stops");

        assertThat(CompletableFuture.runAsync(() -> {
            for (int seq = 1; seq < 200; seq++) {
                store.storeNextIncomingSeqNum(seq);
            }
        })).succeedsWithin(Duration.ofSeconds(5));
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
    }

    @Test
    void aBatchTakesEventsUntilTheNextDoesNotFitWhichStartsTheNextBatch() {
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(1);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        sent.frames.clear();
        for (int seq = 2; seq <= 4; seq++) {
            store.storeNextIncomingSeqNum(seq);
        }

        fill(3 * TestingFrameEncoder.recordBytes(0) - 1);

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
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        sent.frames.clear();

        replicated("other").storeNextOutgoingSeqNum(3);
        fill();

        assertThat(sent.frames).hasSize(2);
        assertThat(sent.frames.get(0)).isEqualTo("STREAM epoch=7 key=1 INITIATOR SYNC_APPLIED id=default.other:VERSION_44:SENDER->TARGET identity=null");
        assertThat(sent.frames.get(1)).contains("key=1 STORE_OUTGOING_SEQ_NUM seq=3");
    }

    @Test
    void aHeartbeatCarriesTheLogTailOnceAnIntervalPassedWithoutSending() {
        replicated("main").storeNextIncomingSeqNum(1);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        sent.frames.clear();
        ByteBuffer batch = ByteBuffer.allocate(256);

        assertThat(connection.heartbeatIfDue(batch, 0)).isZero();
        int length = connection.heartbeatIfDue(batch, Duration.ofMillis(100).toNanos());

        decodeAll(batch.limit(length));
        assertThat(sent.frames).containsExactly("HEARTBEAT epoch=7 tail=" + ha.getReplicationLogs().get(0).getTailOffset());
    }

    @Test
    void aFollowerIsBehindFromAWriteUntilAnAckCoversItThenSampledAgainOnTheNextWriteItLacks() {
        LatencyRecorder lagTimer = mock(LatencyRecorder.class);
        stats.lagTimers((followerId, connection) -> lagTimer);
        FixSessionMessagesStore store = replicated("main");
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
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
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        store.storeNextIncomingSeqNum(6);
        fill();
        followerHangsUpOnClose();

        assertThat(connection.handOver(Deadline.of(Duration.ofSeconds(1)))).isTrue();

        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 SHUTDOWN");
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
    }

    @Test
    void aHandOverTheFollowerDoesNotHangUpOnBeforeTheDeadlineFails() {
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(5);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();

        assertThat(connection.handOver(Deadline.of(Duration.ofMillis(50)))).isFalse();

        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 SHUTDOWN");
        verify(ioSession).stop(any(Deadline.class));
    }

    private void followerHangsUpOnClose() {
        doAnswer(invocation -> {
            ByteBuffer frames = invocation.getArgument(0);
            decodeAll(frames.duplicate().flip());
            if (!sent.frames.isEmpty() && sent.frames.get(sent.frames.size() - 1).startsWith("CLOSE")) {
                connection.disconnected();
            }
            return null;
        }).when(ioSession).send(any(ByteBuffer.class), anyBoolean());
    }

    @Test
    void aFollowerNotSentEveryEventBeforeTheDeadlineIsDroppedWithoutShutdown() {
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(5);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        store.storeNextIncomingSeqNum(6);

        assertThat(connection.handOver(Deadline.of(Duration.ofMillis(50)))).isFalse();

        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("CLOSE"));
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        verify(ioSession).stop(any(Deadline.class));
    }

    /**
     * The IO, sender, session and role threads may all close a connection: the first close is the one the follower
     * and the logs are told.
     */
    @Test
    void aConnectionClosesOnceWhicheverThreadsCloseIt() {
        replicated("main").storeNextIncomingSeqNum(5);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();

        assertThat(connection.close(CloseReason.STALLED, "no ack")).isTrue();
        assertThat(connection.close(CloseReason.PROTOCOL_ERROR, "a bad frame")).isFalse();

        assertThat(sent.frames).filteredOn(frame -> frame.startsWith("CLOSE")).singleElement().asString()
                .startsWith("CLOSE epoch=7 STALLED");
        verify(ioSession).stop(any(Deadline.class));
    }

    @Test
    void aCloseTextTooLongForTheWireIsCut() {
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        String tooLong = "aCloseTextTooLongForTheWireIsCut é ".repeat(2_000);

        assertThat(connection.close(CloseReason.PROTOCOL_ERROR, tooLong)).isTrue();

        assertThat(sent.frames).filteredOn(frame -> frame.startsWith("CLOSE")).singleElement().asString()
                .isEqualTo("CLOSE epoch=7 PROTOCOL_ERROR " + tooLong.substring(0, 1024));
        verify(ioSession).stop(any(Deadline.class));
    }

    @Test
    void aFollowerClosedForAnotherReasonIsNotDroppedAsTooSlowToo() {
        replicated("main").storeNextIncomingSeqNum(5);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        connection.close(CloseReason.PROTOCOL_ERROR, "a bad frame");

        connection.droppedAsTooSlow();

        assertThat(stats.getDroppedTooSlow()).isZero();
        assertThat(stats.getSlowFollowers().notBackInSync()).isEmpty();
    }

    /**
     * A handover tells the follower only on a connection still open: one gone meanwhile has its follower take over
     * with its seqnums moved ahead, and the switchover must not report a clean handover.
     */
    @Test
    void aShutdownOnAConnectionAlreadyGoneTellsNobody() {
        replicated("main").storeNextIncomingSeqNum(5);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        connection.disconnected();

        assertThat(connection.close(CloseReason.SHUTDOWN, "the leader stopped cleanly")).isFalse();

        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("CLOSE"));
    }

    @Test
    void aSessionIsInSyncOnceTheFollowerSaysItReplicatesIt() {
        replicated("main").storeNextIncomingSeqNum(5);
        assertThat(connection.isStreamInSync(0)).as("before its hello").isFalse();
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        assertThat(connection.getSyncState(0)).isEqualTo(StreamSyncState.ANNOUNCED);
        assertThat(connection.isStreamInSync(0)).as("announced").isFalse();

        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.REPLICATING);

        assertThat(connection.isStreamInSync(0)).isTrue();
        assertThat(connection.isStreamInSync(1)).as("another session").isFalse();
    }

    @Test
    void aSessionTheFollowerDoesNotReplicateIsNeverInSync() {
        replicated("main").storeNextIncomingSeqNum(5);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();

        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.UNKNOWN_STREAM);

        assertThat(connection.getSyncState(0)).isEqualTo(StreamSyncState.NOT_REPLICATED);
        assertThat(connection.isStreamInSync(0)).isFalse();
    }

    @Test
    void aSessionNoLongerReplicatedIsAnnouncedAgainAndLeftOutOfTheFollowersSync() {
        replicated("main").storeNextIncomingSeqNum(5);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.REPLICATING);

        ha.onSessionNotReplicated(settings("main"));
        connection.reannounce(0);
        fill();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.NOT_REPLICATED);

        assertThat(sent.frames).endsWith("STREAM epoch=7 key=0 INITIATOR NONE id=" + SESSION + " identity=null");
        assertThat(connection.getSyncState(0)).isEqualTo(StreamSyncState.NOT_REPLICATED);
        assertThat(connection.isLaneInSync(1)).as("a session not replicated is not waited for").isTrue();
    }

    @Test
    void aStatusForASessionNeverAnnouncedClosesTheStream() {
        connection.onStreamAnswer(EPOCH, 3, StreamAnswer.REPLICATING);

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
