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
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreException;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreMessagesConsumer;
import org.lolaf.staffix.ha.ReplicatedSessions;
import org.lolaf.staffix.ha.replication.encryption.EncryptionMode;
import org.lolaf.staffix.ha.replication.encryption.TestingKeyExchanges;
import org.lolaf.staffix.ha.replication.follower.RecordingProbes;
import org.lolaf.staffix.ha.replication.wire.FrameEncoder;
import org.lolaf.staffix.ha.replication.wire.StreamAnswer;
import org.lolaf.staffix.ha.replication.wire.WireFormat;
import org.lolaf.staffix.ha.replication.spi.TestingReplicationClock;
import org.mockito.ArgumentCaptor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSnapshotSender extends FollowerConnectionHarness {

    @Test
    void aResyncSnapshotsAgainASessionTheFollowerHoldsAndLeavesTheOthers() {
        replicated("main").storeNextIncomingSeqNum(5);
        replicated("shared").storeNextIncomingSeqNum(6);
        replicated("refused").storeNextIncomingSeqNum(7);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.REPLICATING);
        connection.onStreamAnswer(EPOCH, 1, StreamAnswer.SHARED_STORAGE);
        connection.onStreamAnswer(EPOCH, 2, StreamAnswer.UNKNOWN_STREAM);

        for (int key = 0; key < 3; key++) {
            connection.resync(key);
        }

        assertThat(connection.getSyncState(0)).isEqualTo(StreamSyncState.SNAPSHOT_PENDING);
        assertThat(connection.isStreamInSync(0)).as("not waited for until the snapshot lands").isFalse();
        assertThat(connection.getSyncState(1)).as("refused").isEqualTo(StreamSyncState.NOT_REPLICATED);
        assertThat(connection.getSyncState(2)).isEqualTo(StreamSyncState.NOT_REPLICATED);
        sent.frames.clear();
        snapshot();
        assertThat(sent.frames).first().asString().startsWith("SNAPSHOT_BEGIN epoch=7 key=0 ");
        assertThat(sent.frames).noneMatch(frame -> frame.contains("key=1") || frame.contains("key=2"));
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
        ha.replicatingStore(settings("main").toBuilder().highAvailability(FixSessionSettings.HaSessionSettings.builder()
                        .replicationMode(ReplicationMode.SYNC_APPLIED).unencryptedMessageTypes(List.of("S")).build()).build(),
                local, new RecordingProbes()).storeNextIncomingSeqNum(20);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        sent.frames.clear();

        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        snapshot();

        long snapshotOffset = ha.getReplicationLogs().get(0).getTailOffset();
        assertThat(sent.frames).containsExactly(
                "SNAPSHOT_BEGIN epoch=7 key=0 snapshotOffset=" + snapshotOffset + " nextIn=21 nextOut=13",
                "SNAPSHOT_RECORD epoch=7 key=0 seq=11 message=eleven",
                "SNAPSHOT_RECORD epoch=7 key=0 seq=12 message=twelve",
                "SNAPSHOT_END epoch=7 key=0 count=2 nextIn=21 nextOut=13");
        verify(local).find(eq(1L), eq(12L), any(StoreMessagesConsumer.class));
        assertThat(connection.getSyncState(0)).isEqualTo(StreamSyncState.SNAPSHOT_SENT);
        assertThat(stats.getSnapshots()).isEqualTo(1);
        assertThat(stats.getSnapshotMessages()).isEqualTo(2);
    }

    @Test
    void aSnapshotMessageOfAnUnencryptedTypeIsFlaggedPlaintextOnEveryFragment() {
        int fragment = ha.getReplicationLogs().get(0).getMaxRecordFrameBytes() - FrameEncoder.snapshotRecordBytes(0);
        String quote = "8=FIX.4.4\u00019=5\u000135=S\u0001" + "x".repeat(2 * fragment) + "\u000110=000\u0001";
        FixSessionMessagesStore local = startedStore();
        when(local.getIncomingSeqNum()).thenReturn(21L);
        when(local.getOutgoingSeqNum()).thenReturn(13L);
        doAnswer(invocation -> {
            StoreMessagesConsumer consumer = invocation.getArgument(2);
            consumer.onMessage(11, ByteBuffer.wrap(quote.getBytes(StandardCharsets.US_ASCII)));
            consumer.onMessage(12, ByteBuffer.wrap("no type".getBytes(StandardCharsets.US_ASCII)));
            return null;
        }).when(local).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));
        ha.replicatingStore(settings("main").toBuilder().highAvailability(FixSessionSettings.HaSessionSettings.builder()
                        .replicationMode(ReplicationMode.SYNC_APPLIED).unencryptedMessageTypes(List.of("S")).build()).build(),
                local, new RecordingProbes()).storeNextIncomingSeqNum(20);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        sentPlaintext.clear();

        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        snapshot();

        assertThat(sentPlaintext).as("three fragments, then the untyped message").containsExactly(true, true, true, false);
    }

    @Test
    void aSnapshotGoesInChunksBetweenTheBatchesOfTheOtherSessionsAndSkipsItsOwnEvents() {
        FixSessionMessagesStore main = ha.replicatingStore(settings("main"), storeHolding(1_001L), new RecordingProbes());
        FixSessionMessagesStore other = replicated("other");
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        sent.frames.clear();

        connection.startPendingSnapshot();
        connection.sendSnapshotChunk();

        assertThat(sent.frames).first().asString().startsWith("SNAPSHOT_BEGIN epoch=7 key=0 ");
        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("SNAPSHOT_END"));
        int chunked = sent.frames.size();
        main.storeNextIncomingSeqNum(22);
        other.storeNextIncomingSeqNum(6);
        fill();
        assertThat(sent.frames.subList(chunked, sent.frames.size())).singleElement().asString()
                .contains("key=1 STORE_INCOMING_SEQ_NUM seq=6");

        while (connection.isSnapshotInProgress()) {
            connection.sendSnapshotChunk();
        }
        assertThat(snapshotSeqNums()).isEqualTo(LongStream.rangeClosed(1, 1_000).boxed().collect(Collectors.toList()));
        assertThat(sent.frames).last().isEqualTo("SNAPSHOT_END epoch=7 key=0 count=1000 nextIn=21 nextOut=1001");
    }

    @Test
    void aSnapshotChunkHoldsNoMoreThanItsBudget() {
        int chunkBytes = 4 << 10;
        connection = new FollowerConnection(ioSession, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a",
                Duration.ofMillis(100), Duration.ZERO, 4, chunkBytes, new BackoffIdleStrategy(), stats, loop -> {
        }, new TestingReplicationClock(), TestingKeyExchanges.CLEAR);
        ha.replicatingStore(settings("main"), storeHolding(1_001L), new RecordingProbes());
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        connection.startPendingSnapshot();
        connection.sendSnapshotChunk();
        sent.frames.clear();

        connection.sendSnapshotChunk();

        int messageFrameBytes = FrameEncoder.snapshotRecordBytes(MESSAGE_BYTES);
        assertThat(snapshotSeqNums()).hasSize(chunkBytes / messageFrameBytes);
        snapshot();
        assertThat(sent.frames).last().isEqualTo("SNAPSHOT_END epoch=7 key=0 count=1000 nextIn=21 nextOut=1001");
    }

    @Test
    void aSnapshotReadsTheStoreOnlyAboutAsFarAsEachChunkSends() {
        connection = new FollowerConnection(ioSession, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a",
                Duration.ofMillis(100), Duration.ZERO, 4, 4 << 10, new BackoffIdleStrategy(), stats, loop -> {
        }, new TestingReplicationClock(), TestingKeyExchanges.CLEAR);
        FixSessionMessagesStore store = storeHolding(1_001L);
        ha.replicatingStore(settings("main"), store, new RecordingProbes());
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);

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
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        sent.frames.clear();

        snapshot();

        assertThat(snapshotSeqNums()).isEqualTo(LongStream.rangeClosed(1, 1_010).boxed().collect(Collectors.toList()));
        assertThat(sent.frames).last().isEqualTo("SNAPSHOT_END epoch=7 key=0 count=1010 nextIn=21 nextOut=1011");
    }

    @Test
    void aSnapshotNotCatchingUpSendsItsLastRoundWhole() {
        ha.replicatingStore(settings("main"), storeHolding(1_001L, 3_001L), new RecordingProbes());
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        connection.startPendingSnapshot();
        while (snapshotSeqNums().size() < 1_000) {
            connection.sendSnapshotChunk();
        }
        sent.frames.clear();

        connection.sendSnapshotChunk();

        assertThat(snapshotSeqNums()).as("the second round, longer than the first, in one chunk")
                .isEqualTo(LongStream.rangeClosed(1_001, 3_000).boxed().collect(Collectors.toList()));
        assertThat(sent.frames).last().isEqualTo("SNAPSHOT_END epoch=7 key=0 count=3000 nextIn=21 nextOut=3001");
        assertThat(connection.isSnapshotInProgress()).isFalse();
    }

    @Test
    void aResetOfTheSessionEndsItsSnapshotAndIsSentAfterItsEnd() {
        FixSessionMessagesStore main = ha.replicatingStore(settings("main"), storeHolding(1_001L), new RecordingProbes());
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        connection.startPendingSnapshot();
        connection.sendSnapshotChunk();
        sent.frames.clear();

        main.resetSequenceNumbers();
        fill();
        connection.sendSnapshotChunk();
        fill();

        assertThat(connection.isSnapshotInProgress()).isFalse();
        assertThat(sent.frames).hasSize(2);
        assertThat(sent.frames.get(0)).startsWith("SNAPSHOT_END epoch=7 key=0 count=");
        assertThat(sent.frames.get(1)).contains("key=0 RESET_SEQ_NUM");
    }

    @Test
    void aSnapshotCarriesAMessageLongerThanAFrameInFragmentsAndCountsItOnce() {
        int fragment = ha.getReplicationLogs().get(0).getMaxRecordFrameBytes() - FrameEncoder.snapshotRecordBytes(0);
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
        ha.replicatingStore(settings("main").toBuilder().highAvailability(FixSessionSettings.HaSessionSettings.builder()
                        .replicationMode(ReplicationMode.SYNC_APPLIED).unencryptedMessageTypes(List.of("S")).build()).build(),
                local, new RecordingProbes()).storeNextIncomingSeqNum(20);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        sent.frames.clear();

        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        snapshot();

        assertThat(sent.frames).filteredOn(frame -> !frame.startsWith("SNAPSHOT_BEGIN")).containsExactly(
                "SNAPSHOT_RECORD epoch=7 key=0 seq=12 message=" + x + " more",
                "SNAPSHOT_RECORD epoch=7 key=0 seq=12 message=" + y + " continued more",
                "SNAPSHOT_RECORD epoch=7 key=0 seq=12 message=zzz continued",
                "SNAPSHOT_END epoch=7 key=0 count=1 nextIn=21 nextOut=13");
    }

    @Test
    void aSnapshotCopiesEveryMessageTheStoreKeeps() {
        FixSessionMessagesStore local = startedStore();
        when(local.getOutgoingSeqNum()).thenReturn(1_001L);
        ha.replicatingStore(settings("main").toBuilder().highAvailability(FixSessionSettings.HaSessionSettings.builder()
                        .replicationMode(ReplicationMode.SYNC_APPLIED).unencryptedMessageTypes(List.of("S")).build()).build(),
                local, new RecordingProbes()).storeNextIncomingSeqNum(20);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();

        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
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
        ha.replicatingStore(settings("main").toBuilder().highAvailability(FixSessionSettings.HaSessionSettings.builder()
                        .replicationMode(ReplicationMode.SYNC_APPLIED).unencryptedMessageTypes(List.of("S")).build()).build(),
                local, new RecordingProbes()).storeNextIncomingSeqNum(20);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        sent.frames.clear();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);

        snapshot();

        assertThat(sent.frames).isEmpty();
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        assertThat(connection.getSyncState(0)).isEqualTo(StreamSyncState.SNAPSHOT_PENDING);
        verify(local, never()).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));

        when(local.isStarted()).thenReturn(true);
        await().untilAsserted(() -> {
            snapshot();
            assertThat(sent.frames).first().asString().startsWith("SNAPSHOT_BEGIN epoch=7 key=0 ");
        });
        assertThat(connection.getSyncState(0)).isEqualTo(StreamSyncState.SNAPSHOT_SENT);
    }

    @Test
    void aStoreThatCannotBeReadEndsTheStreamSoTheFollowerRetries() {
        FixSessionMessagesStore local = startedStore();
        when(local.getOutgoingSeqNum()).thenReturn(5L);
        doThrow(new StoreException("storage down")).when(local).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));
        ha.replicatingStore(settings("main").toBuilder().highAvailability(FixSessionSettings.HaSessionSettings.builder()
                        .replicationMode(ReplicationMode.SYNC_APPLIED).unencryptedMessageTypes(List.of("S")).build()).build(),
                local, new RecordingProbes()).storeNextIncomingSeqNum(20);
        connection.onHello(EPOCH, WireFormat.VERSION, ReplicatedSessions.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        sent.frames.clear();

        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        snapshot();

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        assertThat(sent.frames).anyMatch(frame -> frame.startsWith("CLOSE epoch=7 SNAPSHOT_FAILED"));
        assertThat(connection.getSyncState(0)).isEqualTo(StreamSyncState.SNAPSHOT_PENDING);
    }
}
