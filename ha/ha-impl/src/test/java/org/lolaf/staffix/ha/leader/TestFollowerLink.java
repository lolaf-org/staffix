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
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.betty.api.io.IOWriter.MessageSentCallback;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.staffix.api.monitoring.Timer;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreException;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreMessagesConsumer;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.ha.ReplicationFixEngineHa;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.ha.follower.RecordingProbes;
import org.lolaf.staffix.ha.wire.FrameDecoder;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.RecordingHandler;
import org.lolaf.staffix.ha.wire.SessionStatus;
import org.lolaf.staffix.ha.wire.WireFormat;
import org.lolaf.staffix.tests.TestingClock;
import org.mockito.ArgumentCaptor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestFollowerLink {

    private static final long EPOCH = 7;
    private static final String SESSION = "default.main:VERSION_44:SENDER->TARGET";
    private static final int MESSAGE_BYTES = 1_000;

    private final IOSession connection = mock(IOSession.class);
    private final RecordingHandler sent = new RecordingHandler();
    private ReplicationStats stats = new ReplicationStats(new SlowFollowers(Duration.ZERO, new TestingClock()));
    /**
     * The batches sent, whose write betty has not reported yet while {@link #holdingWrites}.
     */
    private final Queue<Runnable> unwrittenBatches = new ConcurrentLinkedQueue<>();
    private final AtomicInteger batchesSent = new AtomicInteger();
    private volatile boolean holdingWrites;
    private ReplicationFixEngineHa ha = engine(1 << 16);
    private FollowerLink link = link();

    TestFollowerLink() {
        when(connection.borrow(anyInt())).then(invocation -> ByteBuffer.allocate(invocation.getArgument(0)));
        doAnswer(invocation -> {
            ByteBuffer frames = invocation.getArgument(0);
            decodeAll(frames.duplicate().flip());
            return null;
        }).when(connection).send(any(ByteBuffer.class), anyBoolean());
        doAnswer(invocation -> {
            ByteBuffer frames = invocation.getArgument(0);
            MessageSentCallback<Object> callback = invocation.getArgument(2);
            if (!holdingWrites) {
                decodeAll(frames.duplicate().flip());
            }
            batchesSent.incrementAndGet();
            Runnable written = () -> {
                // as betty: flipped for the callback, its position and limit restored once it returns
                frames.flip();
                int position = frames.position();
                int limit = frames.limit();
                callback.onMessageWriteCallback(frames, null, invocation.getArgument(1));
                frames.limit(limit).position(position);
            };
            if (holdingWrites) {
                unwrittenBatches.add(written);
            } else {
                written.run();
            }
            return null;
        }).when(connection).send(any(ByteBuffer.class), any(), any(), anyBoolean());
    }

    private static ReplicationFixEngineHa engine(int logCapacity) {
        return new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(logCapacity)
                .logFullTimeout(Duration.ofMillis(50)).build());
    }

    private FollowerLink link() {
        return new FollowerLink(connection, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a", Duration.ofMillis(100),
                Duration.ZERO, 4, Integer.MAX_VALUE, new BackoffIdleStrategy(), stats,
                loop -> {
                });
    }

    private static FixSessionSettings settings(String name) {
        return FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of(name, FixRegularVersion.VERSION_44, "SENDER", "TARGET"))
                .fixSessionType(FixSessionType.INITIATOR)
                .haSessionSettings(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.SYNC_APPLIED).build())
                .build();
    }

    private static FixSessionMessagesStore startedStore() {
        FixSessionMessagesStore store = mock(FixSessionMessagesStore.class);
        when(store.isStarted()).thenReturn(true);
        return store;
    }

    private FixSessionMessagesStore replicated(String name) {
        return ha.replicatingStore(settings(name), startedStore(), new RecordingProbes());
    }

    /**
     * As a follower would, refusing a frame longer than the log's longest event.
     */
    private void decodeAll(ByteBuffer frames) {
        FrameDecoder decoder = new FrameDecoder(ha.getReplicationLogs().get(0).getMaxEventFrameBytes());
        int index = frames.position();
        int consumed;
        while (index < frames.limit() && (consumed = decoder.decode(frames, index, frames.limit() - index, sent)) > 0) {
            index += consumed;
        }
    }

    private void fill() {
        fill(1 << 20);
    }

    private void fill(int batchBytes) {
        ByteBuffer batch = ByteBuffer.allocate(batchBytes);
        int filled = link.fill(batch);
        decodeAll(batch.limit(filled));
    }

    /**
     * Starts the pending snapshot and sends its chunks until it ends, as the sender loop would with no live writes.
     */
    private void snapshot() {
        link.startPendingSnapshot();
        while (link.isSnapshotInProgress() && link.getState() == FollowerLink.State.STREAMING) {
            link.sendSnapshotChunk();
        }
    }

    @Test
    void theSenderWritesOnlyItsOwnBuffersWhichAConnectionStoppingCannotFree() throws InterruptedException {
        FixSessionMessagesStore main = replicated("main");
        Thread[] sender = new Thread[1];
        link = new FollowerLink(connection, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a",
                Duration.ofHours(1), Duration.ZERO, 4, Integer.MAX_VALUE, new BackoffIdleStrategy(), stats, loop -> {
            sender[0] = new Thread(loop);
            sender[0].start();
        });
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        await().alias("the session's announcement").until(() -> batchesSent.get() == 1);
        main.storeNextIncomingSeqNum(1);
        await().until(() -> batchesSent.get() == 2);

        link.disconnected();
        sender[0].join();

        verify(connection, times(1)).borrow(anyInt());
        verify(connection, never()).send(any(ByteBuffer.class), any(), any(), eq(true));
        verify(connection, never()).unborrow(any());
    }

    private FollowerLink linkStallingAfter(Duration stallTimeout, Thread[] sender) {
        return new FollowerLink(connection, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a",
                Duration.ofHours(1), stallTimeout, 4, Integer.MAX_VALUE, new BackoffIdleStrategy(), stats, loop -> {
            sender[0] = new Thread(loop);
            sender[0].start();
        });
    }

    @Test
    void aFollowerOwingAnAckAndSilentForTheStallTimeoutIsDropped() throws InterruptedException {
        FixSessionMessagesStore main = replicated("main");
        Thread[] sender = new Thread[1];
        link = linkStallingAfter(Duration.ofMillis(100), sender);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);

        main.storeNextIncomingSeqNum(1);

        sender[0].join(5_000);
        assertThat(link.getState()).isEqualTo(FollowerLink.State.CLOSED);
        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 STALLED");
    }

    @Test
    void aSlowFollowerStillAckingEachReadIsKept() {
        FixSessionMessagesStore main = replicated("main");
        Thread[] sender = new Thread[1];
        link = linkStallingAfter(Duration.ofMillis(100), sender);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        try {
            for (int seq = 1; seq <= 10; seq++) {
                main.storeNextIncomingSeqNum(seq);
                await().pollDelay(Duration.ofMillis(40)).until(() -> true);
                link.onAck(EPOCH, 0, true);
            }

            assertThat(link.getState()).as("behind all along, never silent").isEqualTo(FollowerLink.State.STREAMING);
        } finally {
            link.disconnected();
        }
    }

    @Test
    void aWriteAfterALongIdleIsNotTakenForAStall() {
        FixSessionMessagesStore main = replicated("main");
        Thread[] sender = new Thread[1];
        link = linkStallingAfter(Duration.ofMillis(100), sender);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        try {
            await().pollDelay(Duration.ofMillis(300)).until(() -> true);

            long offset = ha.getReplicationLogs().get(0).getTailOffset();
            main.storeNextIncomingSeqNum(1);
            await().pollDelay(Duration.ofMillis(30)).until(() -> true);
            link.onAck(EPOCH, offset, true);
            await().pollDelay(Duration.ofMillis(150)).until(() -> true);

            assertThat(link.getState()).isEqualTo(FollowerLink.State.STREAMING);
        } finally {
            link.disconnected();
        }
    }

    @Test
    void aSenderDroppedWhileAHeartbeatIsDueWritesNothingAfterItsClose() {
        ha = engine(1024);
        Runnable[] sender = new Runnable[1];
        link = new FollowerLink(connection, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a",
                Duration.ofNanos(1), Duration.ZERO, 4, Integer.MAX_VALUE, new BackoffIdleStrategy(), stats, loop -> sender[0] = loop);
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(1);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        for (int seq = 2; seq < 200; seq++) {
            store.storeNextIncomingSeqNum(seq);
        }

        sender[0].run();

        assertThat(link.getState()).isEqualTo(FollowerLink.State.CLOSED);
        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 TOO_SLOW");
        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("HEARTBEAT"));
    }

    @Test
    void theSenderWaitsWhileEverySendBufferIsInFlightAndResumesOnceOneIsWritten() {
        FixSessionMessagesStore main = replicated("main");
        Thread[] sender = new Thread[1];
        link = new FollowerLink(connection, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a", Duration.ofHours(1),
                Duration.ZERO, 4, Integer.MAX_VALUE, new BackoffIdleStrategy(), stats, loop -> {
            sender[0] = new Thread(loop);
            sender[0].start();
        });
        holdingWrites = true;
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
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
            link.disconnected();
        }
    }

    @Test
    void aWriteHeldBackByTheWriteWatermarkIsASendWaitButTheWatermarkAloneIsNot() {
        FixSessionMessagesStore main = replicated("main");
        link = senderRunningLink();
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        try {
            await().alias("the session's announcement").until(() -> batchesSent.get() == 1);

            link.pause(true);
            await().during(Duration.ofMillis(200)).until(() -> stats.getSendWaits() == 0);

            main.storeNextIncomingSeqNum(1);
            await().until(() -> stats.getSendWaits() == 1);

            link.pause(false);
            await().until(() -> batchesSent.get() == 2);
        } finally {
            link.disconnected();
        }
    }

    @Test
    void aSnapshotHeldBackIsASendWait() {
        ha.replicatingStore(settings("main"), storeHolding(1_001L), new RecordingProbes());
        link = senderRunningLink(4 << 10);
        holdingWrites = true;
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        try {
            await().alias("the session's announcement").until(() -> batchesSent.get() == 1);

            link.onSessionStatus(EPOCH, 0, SessionStatus.SNAPSHOT_NEEDED);

            await().until(() -> stats.getSendWaits() == 1);
            assertThat(batchesSent.get()).as("all four in flight").isEqualTo(4);
        } finally {
            link.disconnected();
        }
    }

    private FollowerLink senderRunningLink() {
        return senderRunningLink(Integer.MAX_VALUE);
    }

    private FollowerLink senderRunningLink(int snapshotChunkBytes) {
        return new FollowerLink(connection, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a", Duration.ofHours(1),
                Duration.ZERO, 4, snapshotChunkBytes, new BackoffIdleStrategy(), stats, loop -> new Thread(loop).start());
    }

    @Test
    void aFreshFollowerIsStreamedFromTheLogTail() {
        replicated("main").storeNextIncomingSeqNum(5);

        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        fill();

        assertThat(link.getState()).isEqualTo(FollowerLink.State.STREAMING);
        assertThat(link.getFollowerId()).isEqualTo("follower-b");
        assertThat(sent.frames).containsExactly(
                "WELCOME epoch=7 version=1 leader=leader-a resume=-1 links=1",
                "SESSION epoch=7 key=0 INITIATOR SYNC_APPLIED id=" + SESSION + " identity=null");
    }

    @Test
    void aLinkAnnouncesAndStreamsOnlyTheSessionsItCarries() {
        ha = new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(1 << 16).replicationLinks(2).build());
        link = link();
        FixSessionMessagesStore main = replicated("main");
        FixSessionMessagesStore other = replicated("other");
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 1);
        main.storeNextIncomingSeqNum(5);
        other.storeNextIncomingSeqNum(6);

        fill();

        assertThat(ha.getReplicationLogs().get(0).getTailOffset()).as("main, key 0").isPositive();
        assertThat(sent.frames).hasSize(3);
        assertThat(sent.frames.get(0)).endsWith("links=2");
        assertThat(sent.frames.get(1)).startsWith("SESSION epoch=7 key=1 ");
        assertThat(sent.frames.get(2)).contains("key=1 STORE_INCOMING_SEQ_NUM seq=6");
        assertThat(link.carries(0)).isFalse();
        assertThat(link.carries(1)).isTrue();
    }

    @Test
    void aLinkIsInSyncOnceTheSessionsItCarriesAre() {
        ha = new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(1 << 16).replicationLinks(2).build());
        link = link();
        replicated("main");
        replicated("other");
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 1);
        fill();

        link.onSessionStatus(EPOCH, 1, SessionStatus.REPLICATING);

        assertThat(link.isInSyncForAll(2)).as("main is another link's").isTrue();
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

        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        fill();

        assertThat(link.getState()).isEqualTo(FollowerLink.State.STREAMING);
        assertThat(sent.frames).containsExactly(
                "WELCOME epoch=7 version=1 leader=leader-a resume=" + second + " links=1",
                "SESSION epoch=7 key=0 INITIATOR SYNC_APPLIED id=" + SESSION + " identity=null",
                "STORE_EVENT epoch=7 offset=" + second + " key=0 STORE_INCOMING_SEQ_NUM seq=6 message=",
                "STORE_EVENT epoch=7 offset=" + third + " key=0 STORE_INCOMING_SEQ_NUM seq=7 message=");
    }

    @Test
    void aFollowerWhoseLastAppliedEventWasOverwrittenWhileAwayStreamsFromTheTailForASnapshot() {
        ha = engine(1024);
        link = link();
        FixSessionMessagesStore store = replicated("main");
        for (int seq = 1; seq < 200; seq++) {
            store.storeNextIncomingSeqNum(seq);
        }

        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);

        assertThat(link.getState()).isEqualTo(FollowerLink.State.STREAMING);
        assertThat(sent.frames).first().asString().contains("resume=-1 ");
        assertThat(stats.getTooSlow()).as("nobody gated the writes while it was away").isZero();
    }

    @Test
    void aFollowerDroppedAsTooSlowIsAcceptedOnceItsHoldOffHasPassed() {
        TestingClock clock = new TestingClock();
        clock.toAdvanceableTime(Instant.parse("2026-11-01T10:00:00Z"));
        stats = new ReplicationStats(new SlowFollowers(Duration.ofSeconds(1), clock));
        stats.droppedTooSlow("follower-b", 0);
        link = link();
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 0);
        assertThat(link.getState()).isEqualTo(FollowerLink.State.CLOSED);

        clock.advance(Duration.ofSeconds(1));
        link = link();
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 0);

        assertThat(link.getState()).isEqualTo(FollowerLink.State.STREAMING);
        assertThat(sent.frames).last().asString().startsWith("WELCOME");
    }

    @Test
    void aFollowerOfAnEarlierEpochIsNotResumed() {
        replicated("main").storeNextIncomingSeqNum(5);

        link.onHello(EPOCH - 1, WireFormat.VERSION, "follower-b", EPOCH - 1, 0, 0);

        assertThat(sent.frames).first().asString().contains("resume=-1 ");
    }

    @Test
    void aFollowerOpeningALinkPastTheCountIsClosed() {
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 1);

        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 NO_SUCH_LINK");
        assertThat(link.getState()).isEqualTo(FollowerLink.State.CLOSED);
    }

    @Test
    void aFollowerOfAnotherVersionIsClosed() {
        link.onHello(EPOCH, WireFormat.VERSION + 1, "follower-b", 0, -1, 0);

        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 VERSION_MISMATCH");
        assertThat(link.getState()).isEqualTo(FollowerLink.State.CLOSED);
        verify(connection).stop(any(Deadline.class));
    }

    @Test
    void aLeaderBehindItsFollowersEpochClosesTheStreamAsStale() {
        link.onHello(EPOCH + 1, WireFormat.VERSION, "follower-b", EPOCH + 1, 0, 0);

        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 STALE_EPOCH");
    }

    @Test
    void aFrameAFollowerNeverSendsClosesTheStream() {
        link.onHeartbeat(EPOCH, 0);

        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
    }

    @Test
    void aFollowerAWriteWaitedTheLogFullTimeoutForIsDroppedAsTooSlow() {
        ha = engine(1024);
        link = link();
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(1);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        for (int seq = 2; seq < 200; seq++) {
            store.storeNextIncomingSeqNum(seq);
        }

        fill();

        assertThat(link.getState()).isEqualTo(FollowerLink.State.CLOSED);
        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("STORE_EVENT"));
        assertThat(sent.frames).filteredOn(frame -> frame.startsWith("CLOSE")).singleElement().asString()
                .startsWith("CLOSE epoch=7 TOO_SLOW");
        assertThat(stats.getTooSlow()).isEqualTo(1);
    }

    @Test
    void aStreamingFollowerHoldsWritesBackUntilItReadsWhatTheyWouldOverwrite() throws InterruptedException {
        ha = new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(1024)
                .logFullTimeout(Duration.ofMinutes(1)).build());
        link = link();
        FixSessionMessagesStore store = replicated("main");
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 0);
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

        assertThat(link.getState()).isEqualTo(FollowerLink.State.STREAMING);
        assertThat(sent.frames).filteredOn(frame -> frame.startsWith("STORE_EVENT")).hasSize(199);
    }

    @Test
    void aFollowerDroppedAsTooSlowIsRefusedWhileHeldOff() {
        stats = new ReplicationStats(new SlowFollowers(Duration.ofMinutes(1), new TestingClock()));
        stats.droppedTooSlow("follower-b", 0);
        link = link();

        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 0);

        assertThat(link.getState()).isEqualTo(FollowerLink.State.CLOSED);
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 RETRY_LATER");
    }

    @Test
    void aFollowerDroppedAsTooSlowIsAcceptedAtOnceWithoutAHoldOffAndClearedOnceInSync() {
        stats = new ReplicationStats(new SlowFollowers(Duration.ZERO, new TestingClock()));
        stats.droppedTooSlow("follower-b", 0);
        link = link();

        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 0);
        link.onAck(EPOCH, -1, true);

        assertThat(link.getState()).isEqualTo(FollowerLink.State.STREAMING);
        assertThat(stats.getSlowFollowers().notBackInSync()).isEmpty();
    }

    @Test
    void aFollowerNotStreamingHoldsNoWriteBack() {
        ha = engine(1024);
        link = link();
        FixSessionMessagesStore store = replicated("main");
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, -1, 0);
        link.disconnected();

        for (int seq = 1; seq < 200; seq++) {
            store.storeNextIncomingSeqNum(seq);
        }

        assertThat(stats.getTooSlow()).isZero();
        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("CLOSE"));
    }

    @Test
    void aBatchTakesEventsUntilTheNextDoesNotFitWhichStartsTheNextBatch() {
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(1);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
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
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
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
        ByteBuffer batch = ByteBuffer.allocate(256);

        assertThat(link.heartbeatIfDue(batch, 0)).isZero();
        int length = link.heartbeatIfDue(batch, Duration.ofMillis(100).toNanos());

        decodeAll(batch.limit(length));
        assertThat(sent.frames).containsExactly("HEARTBEAT epoch=7 tail=" + ha.getReplicationLogs().get(0).getTailOffset());
    }

    @Test
    void aFollowerIsBehindFromAWriteUntilAnAckCoversItThenSampledAgainOnTheNextWriteItLacks() {
        Timer lagTimer = mock(Timer.class);
        stats.lagTimers((followerId, link) -> lagTimer);
        FixSessionMessagesStore store = replicated("main");
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        long first = ha.getReplicationLogs().get(0).getTailOffset();
        store.storeNextIncomingSeqNum(5);
        fill();
        assertThat(link.getLagNanos(System.nanoTime())).as("written, not acked").isPositive();

        long second = ha.getReplicationLogs().get(0).getTailOffset();
        store.storeNextIncomingSeqNum(6);
        fill();
        link.onAck(EPOCH, first, true);
        verify(lagTimer).record(anyLong(), eq(TimeUnit.NANOSECONDS));
        assertThat(link.getLagNanos(System.nanoTime())).as("the sample is covered").isZero();

        fill();
        assertThat(link.getLagNanos(System.nanoTime())).as("sent, not acked yet").isPositive();
        link.onAck(EPOCH, second, true);
        verify(lagTimer, times(2)).record(anyLong(), eq(TimeUnit.NANOSECONDS));
        fill();
        assertThat(link.getLagNanos(System.nanoTime())).as("caught up, nothing new written").isZero();
    }

    @Test
    void anAckRecordsWhatTheFollowerApplied() {
        link.onAck(EPOCH, 4096, true);

        assertThat(link.getAppliedOffset()).isEqualTo(4096);
        assertThat(List.copyOf(sent.frames)).isEmpty();
    }

    @Test
    void aCleanHandOverClosesWithShutdownOnceEveryEventIsSent() {
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(5);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        store.storeNextIncomingSeqNum(6);
        fill();

        assertThat(link.handOver(Deadline.of(Duration.ofSeconds(1)))).isTrue();

        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 SHUTDOWN");
        assertThat(link.getState()).isEqualTo(FollowerLink.State.CLOSED);
    }

    @Test
    void aFollowerNotSentEveryEventBeforeTheDeadlineIsDroppedWithoutShutdown() {
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(5);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        store.storeNextIncomingSeqNum(6);

        assertThat(link.handOver(Deadline.of(Duration.ofMillis(50)))).isFalse();

        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("CLOSE"));
        assertThat(link.getState()).isEqualTo(FollowerLink.State.CLOSED);
        verify(connection).stop(any(Deadline.class));
    }

    @Test
    void aSessionIsInSyncOnceTheFollowerSaysItReplicatesIt() {
        replicated("main").storeNextIncomingSeqNum(5);
        assertThat(link.isInSync(0)).as("before its hello").isFalse();
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        fill();
        assertThat(link.getSessionSync(0)).isEqualTo(FollowerLink.SessionSync.ANNOUNCED);
        assertThat(link.isInSync(0)).as("announced").isFalse();

        link.onSessionStatus(EPOCH, 0, SessionStatus.REPLICATING);

        assertThat(link.isInSync(0)).isTrue();
        assertThat(link.isInSync(1)).as("another session").isFalse();
    }

    @Test
    void aSessionTheFollowerDoesNotReplicateIsNeverInSync() {
        replicated("main").storeNextIncomingSeqNum(5);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        fill();

        link.onSessionStatus(EPOCH, 0, SessionStatus.UNKNOWN_SESSION);

        assertThat(link.getSessionSync(0)).isEqualTo(FollowerLink.SessionSync.NOT_REPLICATED);
        assertThat(link.isInSync(0)).isFalse();
    }

    @Test
    void aSessionNoLongerReplicatedIsAnnouncedAgainAndLeftOutOfTheFollowersSync() {
        replicated("main").storeNextIncomingSeqNum(5);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        fill();
        link.onSessionStatus(EPOCH, 0, SessionStatus.REPLICATING);

        ha.notReplicated(settings("main"));
        link.reannounce(0);
        fill();
        link.onSessionStatus(EPOCH, 0, SessionStatus.NOT_REPLICATED);

        assertThat(sent.frames).endsWith("SESSION epoch=7 key=0 INITIATOR NONE id=" + SESSION + " identity=null");
        assertThat(link.getSessionSync(0)).isEqualTo(FollowerLink.SessionSync.NOT_REPLICATED);
        assertThat(link.isInSyncForAll(1)).as("a session not replicated is not waited for").isTrue();
    }

    @Test
    void aResyncSnapshotsAgainASessionTheFollowerHoldsAndLeavesTheOthers() {
        replicated("main").storeNextIncomingSeqNum(5);
        replicated("shared").storeNextIncomingSeqNum(6);
        replicated("refused").storeNextIncomingSeqNum(7);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0, 0);
        fill();
        link.onSessionStatus(EPOCH, 0, SessionStatus.REPLICATING);
        link.onSessionStatus(EPOCH, 1, SessionStatus.SHARED_STORAGE);
        link.onSessionStatus(EPOCH, 2, SessionStatus.UNKNOWN_SESSION);

        for (int key = 0; key < 3; key++) {
            link.resync(key);
        }

        assertThat(link.getSessionSync(0)).isEqualTo(FollowerLink.SessionSync.SNAPSHOT_PENDING);
        assertThat(link.isInSync(0)).as("not waited for until the snapshot lands").isFalse();
        assertThat(link.getSessionSync(1)).as("refused").isEqualTo(FollowerLink.SessionSync.NOT_REPLICATED);
        assertThat(link.getSessionSync(2)).isEqualTo(FollowerLink.SessionSync.NOT_REPLICATED);
        sent.frames.clear();
        snapshot();
        assertThat(sent.frames).first().asString().startsWith("SNAPSHOT_BEGIN epoch=7 key=0 ");
        assertThat(sent.frames).noneMatch(frame -> frame.contains("key=1") || frame.contains("key=2"));
    }

    @Test
    void aStatusForASessionNeverAnnouncedClosesTheStream() {
        link.onSessionStatus(EPOCH, 3, SessionStatus.REPLICATING);

        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
    }

    @Test
    void aSnapshotCarriesTheSeqnumsAndTheLastMessagesCutAtTheStreamPosition() {
        FixSessionMessagesStore local = startedStore();
        when(local.getIncomingSeqNum()).thenReturn(21L);
        when(local.getOutgoingSeqNum()).thenReturn(13L);
        doAnswer(invocation -> {
            StoreMessagesConsumer consumer = invocation.getArgument(2);
            consumer.onMessage(11, ByteBuffer.wrap("eleven".getBytes(StandardCharsets.US_ASCII)));
            consumer.onMessage(12, ByteBuffer.wrap("twelve".getBytes(StandardCharsets.US_ASCII)));
            return null;
        }).when(local).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));
        ha.replicatingStore(settings("main"), local, new RecordingProbes()).storeNextIncomingSeqNum(20);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        fill();
        sent.frames.clear();

        link.onSessionStatus(EPOCH, 0, SessionStatus.SNAPSHOT_NEEDED);
        snapshot();

        long cut = ha.getReplicationLogs().get(0).getTailOffset();
        assertThat(sent.frames).containsExactly(
                "SNAPSHOT_BEGIN epoch=7 key=0 cut=" + cut + " nextIn=21 nextOut=13",
                "SNAPSHOT_MESSAGE epoch=7 key=0 seq=11 message=eleven",
                "SNAPSHOT_MESSAGE epoch=7 key=0 seq=12 message=twelve",
                "SNAPSHOT_END epoch=7 key=0 count=2 nextIn=21 nextOut=13");
        verify(local).find(eq(1L), eq(12L), any(StoreMessagesConsumer.class));
        assertThat(link.getSessionSync(0)).isEqualTo(FollowerLink.SessionSync.SNAPSHOT_SENT);
        assertThat(stats.getSnapshots()).isEqualTo(1);
        assertThat(stats.getSnapshotMessages()).isEqualTo(2);
    }

    /**
     * A started store holding the messages up to {@code outgoing - 1}, each {@code MESSAGE_BYTES} long, its next
     * outgoing seqnum answered from {@code outgoing} one call after the other, the last answer repeated.
     */
    private static FixSessionMessagesStore storeHolding(Long first, Long... next) {
        FixSessionMessagesStore store = startedStore();
        when(store.getIncomingSeqNum()).thenReturn(21L);
        when(store.getOutgoingSeqNum()).thenReturn(first, next);
        doAnswer(invocation -> {
            long stop = invocation.getArgument(1);
            StoreMessagesConsumer consumer = invocation.getArgument(2);
            for (long seqNum = invocation.getArgument(0); seqNum <= stop; seqNum++) {
                if (!consumer.onMessage(seqNum, ByteBuffer.wrap(new byte[MESSAGE_BYTES]))) {
                    return null;
                }
            }
            return null;
        }).when(store).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));
        return store;
    }

    private List<Long> snapshotSeqNums() {
        return sent.frames.stream().filter(frame -> frame.startsWith("SNAPSHOT_MESSAGE"))
                .map(frame -> Long.parseLong(frame.replaceAll(".* seq=(\\d+) .*", "$1"))).collect(Collectors.toList());
    }

    @Test
    void aSnapshotGoesInChunksBetweenTheBatchesOfTheOtherSessionsAndSkipsItsOwnEvents() {
        FixSessionMessagesStore main = ha.replicatingStore(settings("main"), storeHolding(1_001L), new RecordingProbes());
        FixSessionMessagesStore other = replicated("other");
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        fill();
        link.onSessionStatus(EPOCH, 0, SessionStatus.SNAPSHOT_NEEDED);
        sent.frames.clear();

        link.startPendingSnapshot();
        link.sendSnapshotChunk();

        assertThat(sent.frames).first().asString().startsWith("SNAPSHOT_BEGIN epoch=7 key=0 ");
        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("SNAPSHOT_END"));
        int chunked = sent.frames.size();
        main.storeNextIncomingSeqNum(22);
        other.storeNextIncomingSeqNum(6);
        fill();
        assertThat(sent.frames.subList(chunked, sent.frames.size())).singleElement().asString()
                .contains("key=1 STORE_INCOMING_SEQ_NUM seq=6");

        while (link.isSnapshotInProgress()) {
            link.sendSnapshotChunk();
        }
        assertThat(snapshotSeqNums()).isEqualTo(LongStream.rangeClosed(1, 1_000).boxed().collect(Collectors.toList()));
        assertThat(sent.frames).last().isEqualTo("SNAPSHOT_END epoch=7 key=0 count=1000 nextIn=21 nextOut=1001");
    }

    @Test
    void aSnapshotChunkHoldsNoMoreThanItsBudget() {
        int chunkBytes = 4 << 10;
        link = new FollowerLink(connection, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a",
                Duration.ofMillis(100), Duration.ZERO, 4, chunkBytes, new BackoffIdleStrategy(), stats, loop -> {
        });
        ha.replicatingStore(settings("main"), storeHolding(1_001L), new RecordingProbes());
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        fill();
        link.onSessionStatus(EPOCH, 0, SessionStatus.SNAPSHOT_NEEDED);
        link.startPendingSnapshot();
        link.sendSnapshotChunk();
        sent.frames.clear();

        link.sendSnapshotChunk();

        int messageFrameBytes = FrameEncoder.snapshotMessageBytes(MESSAGE_BYTES);
        assertThat(snapshotSeqNums()).hasSize(chunkBytes / messageFrameBytes);
        snapshot();
        assertThat(sent.frames).last().isEqualTo("SNAPSHOT_END epoch=7 key=0 count=1000 nextIn=21 nextOut=1001");
    }

    @Test
    void aSnapshotReadsTheStoreOnlyAboutAsFarAsEachChunkSends() {
        link = new FollowerLink(connection, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a",
                Duration.ofMillis(100), Duration.ZERO, 4, 4 << 10, new BackoffIdleStrategy(), stats, loop -> {
        });
        FixSessionMessagesStore store = storeHolding(1_001L);
        ha.replicatingStore(settings("main"), store, new RecordingProbes());
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        fill();
        link.onSessionStatus(EPOCH, 0, SessionStatus.SNAPSHOT_NEEDED);

        snapshot();

        ArgumentCaptor<Long> starts = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> stops = ArgumentCaptor.forClass(Long.class);
        verify(store, atLeastOnce()).find(starts.capture(), stops.capture(), any(StoreMessagesConsumer.class));
        long read = 0;
        for (int i = 0; i < starts.getAllValues().size(); i++) {
            read += stops.getAllValues().get(i) - starts.getAllValues().get(i) + 1;
        }
        assertThat(read).as("seqnums asked of the store for 1 000 sent").isLessThan(1_500);
        assertThat(sent.frames).last().isEqualTo("SNAPSHOT_END epoch=7 key=0 count=1000 nextIn=21 nextOut=1001");
    }

    @Test
    void aSnapshotEndsWithTheRoundThatCaughtUpWithTheSessionWrites() {
        ha.replicatingStore(settings("main"), storeHolding(1_001L, 1_011L), new RecordingProbes());
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        fill();
        link.onSessionStatus(EPOCH, 0, SessionStatus.SNAPSHOT_NEEDED);
        sent.frames.clear();

        snapshot();

        assertThat(snapshotSeqNums()).isEqualTo(LongStream.rangeClosed(1, 1_010).boxed().collect(Collectors.toList()));
        assertThat(sent.frames).last().isEqualTo("SNAPSHOT_END epoch=7 key=0 count=1010 nextIn=21 nextOut=1011");
    }

    @Test
    void aSnapshotNotCatchingUpSendsItsLastRoundWhole() {
        ha.replicatingStore(settings("main"), storeHolding(1_001L, 3_001L), new RecordingProbes());
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        fill();
        link.onSessionStatus(EPOCH, 0, SessionStatus.SNAPSHOT_NEEDED);
        link.startPendingSnapshot();
        while (snapshotSeqNums().size() < 1_000) {
            link.sendSnapshotChunk();
        }
        sent.frames.clear();

        link.sendSnapshotChunk();

        assertThat(snapshotSeqNums()).as("the second round, longer than the first, in one chunk")
                .isEqualTo(LongStream.rangeClosed(1_001, 3_000).boxed().collect(Collectors.toList()));
        assertThat(sent.frames).last().isEqualTo("SNAPSHOT_END epoch=7 key=0 count=3000 nextIn=21 nextOut=3001");
        assertThat(link.isSnapshotInProgress()).isFalse();
    }

    @Test
    void aResetOfTheSessionEndsItsSnapshotAndIsSentAfterItsEnd() {
        FixSessionMessagesStore main = ha.replicatingStore(settings("main"), storeHolding(1_001L), new RecordingProbes());
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        fill();
        link.onSessionStatus(EPOCH, 0, SessionStatus.SNAPSHOT_NEEDED);
        link.startPendingSnapshot();
        link.sendSnapshotChunk();
        sent.frames.clear();

        main.resetSequenceNumbers();
        fill();
        link.sendSnapshotChunk();
        fill();

        assertThat(link.isSnapshotInProgress()).isFalse();
        assertThat(sent.frames).hasSize(2);
        assertThat(sent.frames.get(0)).startsWith("SNAPSHOT_END epoch=7 key=0 count=");
        assertThat(sent.frames.get(1)).contains("key=0 RESET_SEQ_NUM");
    }

    @Test
    void aSnapshotCarriesAMessageLongerThanAFrameInFragmentsAndCountsItOnce() {
        int fragment = ha.getReplicationLogs().get(0).getMaxEventFrameBytes() - FrameEncoder.snapshotMessageBytes(0);
        String x = "x".repeat(fragment);
        String y = "y".repeat(fragment);
        FixSessionMessagesStore local = startedStore();
        when(local.getIncomingSeqNum()).thenReturn(21L);
        when(local.getOutgoingSeqNum()).thenReturn(13L);
        doAnswer(invocation -> {
            StoreMessagesConsumer consumer = invocation.getArgument(2);
            consumer.onMessage(12, ByteBuffer.wrap((x + y + "zzz").getBytes(StandardCharsets.US_ASCII)));
            return null;
        }).when(local).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));
        ha.replicatingStore(settings("main"), local, new RecordingProbes()).storeNextIncomingSeqNum(20);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        fill();
        sent.frames.clear();

        link.onSessionStatus(EPOCH, 0, SessionStatus.SNAPSHOT_NEEDED);
        snapshot();

        assertThat(sent.frames).filteredOn(frame -> !frame.startsWith("SNAPSHOT_BEGIN")).containsExactly(
                "SNAPSHOT_MESSAGE epoch=7 key=0 seq=12 message=" + x + " more",
                "SNAPSHOT_MESSAGE epoch=7 key=0 seq=12 message=" + y + " continued more",
                "SNAPSHOT_MESSAGE epoch=7 key=0 seq=12 message=zzz continued",
                "SNAPSHOT_END epoch=7 key=0 count=1 nextIn=21 nextOut=13");
    }

    @Test
    void aSnapshotCopiesEveryMessageTheStoreKeeps() {
        FixSessionMessagesStore local = startedStore();
        when(local.getOutgoingSeqNum()).thenReturn(1_001L);
        ha.replicatingStore(settings("main"), local, new RecordingProbes()).storeNextIncomingSeqNum(20);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        fill();

        link.onSessionStatus(EPOCH, 0, SessionStatus.SNAPSHOT_NEEDED);
        snapshot();

        ArgumentCaptor<Long> starts = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> stops = ArgumentCaptor.forClass(Long.class);
        verify(local, atLeastOnce()).find(starts.capture(), stops.capture(), any(StoreMessagesConsumer.class));
        List<Long> next = new ArrayList<>(List.of(1L));
        stops.getAllValues().forEach(stop -> next.add(stop + 1));
        assertThat(starts.getAllValues()).as("each read starts where the last ended").isEqualTo(next.subList(0, next.size() - 1));
        assertThat(stops.getAllValues()).last().isEqualTo(1_000L);
    }

    @Test
    void aSessionWhoseStoreIsNotStartedYetIsSnapshotOnceItIs() {
        FixSessionMessagesStore local = mock(FixSessionMessagesStore.class);
        when(local.getOutgoingSeqNum()).thenReturn(5L);
        ha.replicatingStore(settings("main"), local, new RecordingProbes()).storeNextIncomingSeqNum(20);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        fill();
        sent.frames.clear();
        link.onSessionStatus(EPOCH, 0, SessionStatus.SNAPSHOT_NEEDED);

        snapshot();

        assertThat(sent.frames).isEmpty();
        assertThat(link.getState()).isEqualTo(FollowerLink.State.STREAMING);
        assertThat(link.getSessionSync(0)).isEqualTo(FollowerLink.SessionSync.SNAPSHOT_PENDING);
        verify(local, never()).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));

        when(local.isStarted()).thenReturn(true);
        await().untilAsserted(() -> {
            snapshot();
            assertThat(sent.frames).first().asString().startsWith("SNAPSHOT_BEGIN epoch=7 key=0 ");
        });
        assertThat(link.getSessionSync(0)).isEqualTo(FollowerLink.SessionSync.SNAPSHOT_SENT);
    }

    @Test
    void aStoreThatCannotBeReadEndsTheStreamSoTheFollowerRetries() {
        FixSessionMessagesStore local = startedStore();
        when(local.getOutgoingSeqNum()).thenReturn(5L);
        doThrow(new StoreException("storage down")).when(local).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));
        ha.replicatingStore(settings("main"), local, new RecordingProbes()).storeNextIncomingSeqNum(20);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0);
        fill();
        sent.frames.clear();

        link.onSessionStatus(EPOCH, 0, SessionStatus.SNAPSHOT_NEEDED);
        snapshot();

        assertThat(link.getState()).isEqualTo(FollowerLink.State.CLOSED);
        assertThat(sent.frames).anyMatch(frame -> frame.startsWith("CLOSE epoch=7 SNAPSHOT_FAILED"));
        assertThat(link.getSessionSync(0)).isEqualTo(FollowerLink.SessionSync.SNAPSHOT_PENDING);
    }
}
