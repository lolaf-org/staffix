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
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.monitoring.Timer;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreMessagesConsumer;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreException;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.ha.ReplicationFixEngineHa;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.ha.follower.RecordingProbes;
import org.lolaf.staffix.ha.wire.FrameDecoder;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.RecordingHandler;
import org.lolaf.staffix.ha.wire.SessionStatus;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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

    private final IOSession connection = mock(IOSession.class);
    private final RecordingHandler sent = new RecordingHandler();
    private final ReplicationStats stats = new ReplicationStats();
    private ReplicationFixEngineHa ha = engine(1 << 16);
    private FollowerLink link = link();

    TestFollowerLink() {
        when(connection.borrow(anyInt())).then(invocation -> ByteBuffer.allocate(invocation.getArgument(0)));
        doAnswer(invocation -> {
            ByteBuffer frames = invocation.getArgument(0);
            decodeAll(frames.duplicate().flip());
            return null;
        }).when(connection).send(any(ByteBuffer.class), anyBoolean());
    }

    private static ReplicationFixEngineHa engine(int logCapacity) {
        return new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(logCapacity).build());
    }

    private FollowerLink link() {
        return new FollowerLink(connection, ha.getReplicationLog(), ha.getSessions(), () -> EPOCH, "leader-a", Duration.ofMillis(100), 100, stats,
                loop -> {
                });
    }

    private static FixSessionSettings settings(String name) {
        return FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of(name, FixRegularVersion.VERSION_44, "SENDER", "TARGET"))
                .fixSessionType(FixSessionType.INITIATOR)
                .haSessionSettings(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.SYNC).build())
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
        FrameDecoder decoder = new FrameDecoder(ha.getReplicationLog().getMaxEventFrameBytes());
        int index = frames.position();
        int consumed;
        while (index < frames.limit() && (consumed = decoder.decode(frames, index, frames.limit() - index, sent)) > 0) {
            index += consumed;
        }
    }

    private void fill() {
        ByteBuffer batch = ByteBuffer.allocate(1 << 20);
        int filled = link.fill(batch);
        decodeAll(batch.limit(filled));
    }

    @Test
    void aFreshFollowerIsStreamedFromTheLogTail() {
        replicated("main").storeNextIncomingSeqNum(5);

        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1);
        fill();

        assertThat(link.getState()).isEqualTo(FollowerLink.State.STREAMING);
        assertThat(link.getFollowerId()).isEqualTo("follower-b");
        assertThat(sent.frames).containsExactly(
                "WELCOME epoch=7 version=1 leader=leader-a resume=-1",
                "SESSION epoch=7 key=0 INITIATOR SYNC id=" + SESSION + " identity=null");
    }

    @Test
    void aFollowerResumesAfterItsLastAppliedEventStillInTheLog() {
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(5);
        // events without a message are all one length, so the second starts halfway to the third
        store.storeNextIncomingSeqNum(6);
        long third = ha.getReplicationLog().getTailOffset();
        long second = third / 2;
        store.storeNextIncomingSeqNum(7);

        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0);
        fill();

        assertThat(link.getState()).isEqualTo(FollowerLink.State.STREAMING);
        assertThat(sent.frames).containsExactly(
                "WELCOME epoch=7 version=1 leader=leader-a resume=" + second,
                "SESSION epoch=7 key=0 INITIATOR SYNC id=" + SESSION + " identity=null",
                "STORE_EVENT epoch=7 offset=" + second + " key=0 STORE_INCOMING_SEQ_NUM seq=6 message=",
                "STORE_EVENT epoch=7 offset=" + third + " key=0 STORE_INCOMING_SEQ_NUM seq=7 message=");
    }

    @Test
    void aFollowerOfAnEarlierEpochIsNotResumed() {
        replicated("main").storeNextIncomingSeqNum(5);

        link.onHello(EPOCH - 1, WireFormat.VERSION, "follower-b", EPOCH - 1, 0);

        assertThat(sent.frames).first().asString().endsWith("resume=-1");
    }

    @Test
    void aFollowerOfAnotherVersionIsClosed() {
        link.onHello(EPOCH, WireFormat.VERSION + 1, "follower-b", 0, -1);

        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 VERSION_MISMATCH");
        assertThat(link.getState()).isEqualTo(FollowerLink.State.CLOSED);
        verify(connection).stop(any(Deadline.class));
    }

    @Test
    void aLeaderBehindItsFollowersEpochClosesTheStreamAsStale() {
        link.onHello(EPOCH + 1, WireFormat.VERSION, "follower-b", EPOCH + 1, 0);

        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 STALE_EPOCH");
    }

    @Test
    void aFrameAFollowerNeverSendsClosesTheStream() {
        link.onHeartbeat(EPOCH, 0);

        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
    }

    @Test
    void aFollowerTheLogLapsIsDisconnected() {
        ha = engine(1024);
        link = link();
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(1);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0);
        for (int seq = 2; seq < 200; seq++) {
            store.storeNextIncomingSeqNum(seq);
        }

        fill();

        assertThat(link.getState()).isEqualTo(FollowerLink.State.CLOSED);
        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("STORE_EVENT"));
        assertThat(sent.frames).anyMatch(frame -> frame.startsWith("CLOSE epoch=7 LAPPED"));
        assertThat(stats.getLaps()).isEqualTo(1);
    }

    @Test
    void aSessionAppearingMidStreamIsAnnouncedBeforeItsFirstEvent() {
        FixSessionMessagesStore main = replicated("main");
        main.storeNextIncomingSeqNum(1);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0);
        fill();
        sent.frames.clear();

        replicated("other").storeNextOutgoingSeqNum(3);
        fill();

        assertThat(sent.frames).hasSize(2);
        assertThat(sent.frames.get(0)).isEqualTo("SESSION epoch=7 key=1 INITIATOR SYNC id=default.other:VERSION_44:SENDER->TARGET identity=null");
        assertThat(sent.frames.get(1)).contains("key=1 STORE_OUTGOING_SEQ_NUM seq=3");
    }

    @Test
    void aHeartbeatCarriesTheLogTailOnceAnIntervalPassedWithoutSending() {
        replicated("main").storeNextIncomingSeqNum(1);
        ByteBuffer batch = ByteBuffer.allocate(256);

        assertThat(link.heartbeatIfDue(batch, 0)).isZero();
        int length = link.heartbeatIfDue(batch, Duration.ofMillis(100).toNanos());

        decodeAll(batch.limit(length));
        assertThat(sent.frames).containsExactly("HEARTBEAT epoch=7 tail=" + ha.getReplicationLog().getTailOffset());
    }

    @Test
    void aFollowerIsBehindFromAWriteUntilAnAckCoversItThenSampledAgainOnTheNextWriteItLacks() {
        Timer lagTimer = mock(Timer.class);
        stats.lagTimers(followerId -> lagTimer);
        FixSessionMessagesStore store = replicated("main");
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1);
        long first = ha.getReplicationLog().getTailOffset();
        store.storeNextIncomingSeqNum(5);
        fill();
        assertThat(link.getLagNanos(System.nanoTime())).as("written, not acked").isPositive();

        long second = ha.getReplicationLog().getTailOffset();
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
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0);
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
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0);
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
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0);
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
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0);
        fill();

        link.onSessionStatus(EPOCH, 0, SessionStatus.UNKNOWN_SESSION);

        assertThat(link.getSessionSync(0)).isEqualTo(FollowerLink.SessionSync.NOT_REPLICATED);
        assertThat(link.isInSync(0)).isFalse();
    }

    @Test
    void aSessionNoLongerReplicatedIsAnnouncedAgainAndLeftOutOfTheFollowersSync() {
        replicated("main").storeNextIncomingSeqNum(5);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0);
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
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0);
        fill();
        link.onSessionStatus(EPOCH, 0, SessionStatus.REPLICATING);
        link.onSessionStatus(EPOCH, 1, SessionStatus.SHARED_STORAGE);
        link.onSessionStatus(EPOCH, 2, SessionStatus.UNKNOWN_SESSION);

        for (int key = 0; key < 3; key++) {
            link.resync(key);
        }

        assertThat(link.getSessionSync(0)).isEqualTo(FollowerLink.SessionSync.SNAPSHOT_PENDING);
        assertThat(link.isInSync(0)).as("not waited for until the snapshot lands").isFalse();
        assertThat(link.getSessionSync(1)).isEqualTo(FollowerLink.SessionSync.IN_SYNC);
        assertThat(link.getSessionSync(2)).isEqualTo(FollowerLink.SessionSync.NOT_REPLICATED);
        sent.frames.clear();
        link.snapshotPending();
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
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1);
        fill();
        sent.frames.clear();

        link.onSessionStatus(EPOCH, 0, SessionStatus.SNAPSHOT_NEEDED);
        link.snapshotPending();

        long cut = ha.getReplicationLog().getTailOffset();
        assertThat(sent.frames).containsExactly(
                "SNAPSHOT_BEGIN epoch=7 key=0 cut=" + cut + " nextIn=21 nextOut=13",
                "SNAPSHOT_MESSAGE epoch=7 key=0 seq=11 message=eleven",
                "SNAPSHOT_MESSAGE epoch=7 key=0 seq=12 message=twelve",
                "SNAPSHOT_END epoch=7 key=0 count=2");
        verify(local).find(eq(1L), eq(12L), any(StoreMessagesConsumer.class));
        assertThat(link.getSessionSync(0)).isEqualTo(FollowerLink.SessionSync.SNAPSHOT_SENT);
        assertThat(stats.getSnapshots()).isEqualTo(1);
        assertThat(stats.getSnapshotMessages()).isEqualTo(2);
    }

    @Test
    void aSnapshotCarriesAMessageLongerThanAFrameInFragmentsAndCountsItOnce() {
        int fragment = ha.getReplicationLog().getMaxEventFrameBytes() - FrameEncoder.snapshotMessageBytes(0);
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
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1);
        fill();
        sent.frames.clear();

        link.onSessionStatus(EPOCH, 0, SessionStatus.SNAPSHOT_NEEDED);
        link.snapshotPending();

        assertThat(sent.frames).filteredOn(frame -> !frame.startsWith("SNAPSHOT_BEGIN")).containsExactly(
                "SNAPSHOT_MESSAGE epoch=7 key=0 seq=12 message=" + x + " more",
                "SNAPSHOT_MESSAGE epoch=7 key=0 seq=12 message=" + y + " continued more",
                "SNAPSHOT_MESSAGE epoch=7 key=0 seq=12 message=zzz continued",
                "SNAPSHOT_END epoch=7 key=0 count=1");
    }

    @Test
    void aSnapshotKeepsTheMostRecentMessagesOnly() {
        FixSessionMessagesStore local = startedStore();
        when(local.getOutgoingSeqNum()).thenReturn(1_001L);
        ha.replicatingStore(settings("main"), local, new RecordingProbes()).storeNextIncomingSeqNum(20);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1);
        fill();

        link.onSessionStatus(EPOCH, 0, SessionStatus.SNAPSHOT_NEEDED);
        link.snapshotPending();

        verify(local).find(eq(901L), eq(1_000L), any(StoreMessagesConsumer.class));
    }

    @Test
    void aSessionWhoseStoreIsNotStartedYetIsSnapshotOnceItIs() {
        FixSessionMessagesStore local = mock(FixSessionMessagesStore.class);
        when(local.getOutgoingSeqNum()).thenReturn(5L);
        ha.replicatingStore(settings("main"), local, new RecordingProbes()).storeNextIncomingSeqNum(20);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1);
        fill();
        sent.frames.clear();
        link.onSessionStatus(EPOCH, 0, SessionStatus.SNAPSHOT_NEEDED);

        link.snapshotPending();

        assertThat(sent.frames).isEmpty();
        assertThat(link.getState()).isEqualTo(FollowerLink.State.STREAMING);
        assertThat(link.getSessionSync(0)).isEqualTo(FollowerLink.SessionSync.SNAPSHOT_PENDING);
        verify(local, never()).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));

        when(local.isStarted()).thenReturn(true);
        link.snapshotPending();

        assertThat(sent.frames).first().asString().startsWith("SNAPSHOT_BEGIN epoch=7 key=0 ");
        assertThat(link.getSessionSync(0)).isEqualTo(FollowerLink.SessionSync.SNAPSHOT_SENT);
    }

    @Test
    void aStoreThatCannotBeReadEndsTheStreamSoTheFollowerRetries() {
        FixSessionMessagesStore local = startedStore();
        when(local.getOutgoingSeqNum()).thenReturn(5L);
        doThrow(new StoreException("storage down")).when(local).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));
        ha.replicatingStore(settings("main"), local, new RecordingProbes()).storeNextIncomingSeqNum(20);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1);
        fill();
        sent.frames.clear();

        link.onSessionStatus(EPOCH, 0, SessionStatus.SNAPSHOT_NEEDED);
        link.snapshotPending();

        assertThat(link.getState()).isEqualTo(FollowerLink.State.CLOSED);
        assertThat(sent.frames).anyMatch(frame -> frame.startsWith("CLOSE epoch=7 SNAPSHOT_FAILED"));
        assertThat(link.getSessionSync(0)).isEqualTo(FollowerLink.SessionSync.SNAPSHOT_PENDING);
    }
}
