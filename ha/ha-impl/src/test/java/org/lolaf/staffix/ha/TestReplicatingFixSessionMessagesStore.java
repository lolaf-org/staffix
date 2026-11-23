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
package org.lolaf.staffix.ha;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.FixEngineHa.ReplicatingStore;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.api.monitoring.Timer;
import org.lolaf.staffix.ha.replication.FollowersView;
import org.lolaf.staffix.ha.replication.StreamWriter;
import org.lolaf.staffix.ha.replication.spi.RecordFlag;
import org.lolaf.staffix.ha.replication.wire.AllocatedBytes;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Isolated
class TestReplicatingFixSessionMessagesStore {

    private static final String MESSAGE = "8=FIX.4.4\u00019=5\u000135=D\u000110=000\u0001";
    private static final MessageType NEW_ORDER = MessageType.of("D", false);
    private static final MessageType HEARTBEAT = MessageType.of("0", true);

    private static final int MAX_PAYLOAD_LENGTH = 1024;

    private final ReplicatedSessions sessions;
    private final RecordingStreamWriters writers;
    private final ConsumingStore local;

    TestReplicatingFixSessionMessagesStore() {
        this.sessions = new ReplicatedSessions();
        this.writers = new RecordingStreamWriters(MAX_PAYLOAD_LENGTH);
        this.local = new ConsumingStore();
    }

    /**
     * As {@link ReplicationFixEngineHa#replicatingStore} builds it.
     */
    private ReplicatingStore replicatingStore(FixSessionSettings settings, FixSessionMessagesStore local,
                                              RecordingProbes probes) {
        ReplicatedSession session = sessions.register(settings, local);
        probes.add(HaProbes.sessionProbe(session, mock(FollowersView.class)));
        return new ReplicatingFixSessionMessagesStore(local, writers.writer(session.getKey()), session,
                Timer.VoidTimer.getInstance(), Timer.VoidTimer.getInstance(), started -> {
        });
    }

    private static FixSessionSettings session(String name, FixSessionType type) {
        return FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of(name, FixRegularVersion.VERSION_44, "SENDER", "TARGET"))
                .fixSessionType(type)
                .highAvailability(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.ASYNC).build())
                .build();
    }

    private static ByteBuffer message() {
        return ByteBuffer.wrap(MESSAGE.getBytes(StandardCharsets.US_ASCII));
    }

    private String nextEvent() {
        return writers.next();
    }

    @Test
    void eachWriteIsAppliedLocallyThenLoggedInOrderWithTheSessionsKey() {
        FixSessionMessagesStore store = replicatingStore(session("main", FixSessionType.INITIATOR), local, new RecordingProbes());

        store.storeMessageSent(5, message());
        store.storeNextOutgoingSeqNum(7);
        store.storeNextIncomingSeqNum(3);
        store.resetSequenceNumbers();

        assertThat(local.calls).containsExactly("message 5", "outgoing 7", "incoming 3", "reset");
        assertThat(nextEvent()).isEqualTo("0 0 5 " + MESSAGE);
        assertThat(nextEvent()).isEqualTo("0 2 7 ");
        assertThat(nextEvent()).isEqualTo("0 1 3 ");
        assertThat(nextEvent()).isEqualTo("0 3 0 ");
        assertThat(writers.getLastFlag()).as("a reset ends a snapshot in progress").isEqualTo(RecordFlag.RESET);
        assertThat(nextEvent()).isEqualTo("none");
    }

    @Test
    void thePendingWritesAreTheLocalStores() {
        FixSessionMessagesStore pending = mock(FixSessionMessagesStore.class);
        when(pending.getPendingWrites()).thenReturn(7L);

        assertThat(replicatingStore(session("main", FixSessionType.INITIATOR), pending, new RecordingProbes()).getPendingWrites())
                .isEqualTo(7);
    }

    @Test
    void readsGoToTheLocalStoreAndAreNotLogged() {
        FixSessionMessagesStore store = replicatingStore(session("main", FixSessionType.INITIATOR), local, new RecordingProbes());

        assertThat(store.getNextOutgoingSeqNum()).isEqualTo(11);
        assertThat(store.getOutgoingSeqNum()).isEqualTo(12);
        assertThat(store.getIncomingSeqNum()).isEqualTo(21);

        assertThat(nextEvent()).isEqualTo("none");
    }

    @Test
    void theMessageIsLoggedWholeThoughTheLocalStoreConsumedIt() {
        FixSessionMessagesStore store = replicatingStore(session("main", FixSessionType.INITIATOR), local, new RecordingProbes());
        ByteBuffer message = message();

        store.storeMessageSent(5, message);

        assertThat(message.position()).isZero();
        assertThat(nextEvent()).endsWith(MESSAGE);
    }

    @Test
    void aSessionKeepsItsKeyWhenRebuiltAndTwoSessionsNeverShareOne() {
        replicatingStore(session("main", FixSessionType.INITIATOR), local, new RecordingProbes()).storeNextIncomingSeqNum(1);
        replicatingStore(session("main", FixSessionType.ACCEPTOR), local, new RecordingProbes()).storeNextIncomingSeqNum(1);
        replicatingStore(session("other", FixSessionType.INITIATOR), local, new RecordingProbes()).storeNextIncomingSeqNum(1);
        replicatingStore(session("main", FixSessionType.INITIATOR), local, new RecordingProbes()).storeNextIncomingSeqNum(2);

        assertThat(List.of(nextEvent(), nextEvent(), nextEvent(), nextEvent()))
                .containsExactly("0 1 1 ", "1 1 1 ", "2 1 1 ", "0 1 2 ");
    }

    @Test
    void aMessageTooLongForTheLogIsStoredLocallyOnlyAndAlerts() {
        RecordingProbes probes = new RecordingProbes();
        FixSessionMessagesStore store = replicatingStore(session("main", FixSessionType.INITIATOR), local, probes);
        ByteBuffer tooLong = ByteBuffer.allocate(MAX_PAYLOAD_LENGTH + 1);

        store.storeMessageSent(5, tooLong);

        assertThat(local.calls).containsExactly("message 5");
        assertThat(nextEvent()).isEqualTo("none");
        assertThat(probes.evaluate(HaProbes.REPLICATION)).hasValueSatisfying(alert ->
                assertThat(alert.getMessage()).contains("longer than half the replication log"));
    }

    /**
     * Fewer bytes than writes rather than zero: a one-off may land in the window while the JIT settles, whereas an
     * object per write would cost at least 16 bytes each.
     */
    private static MessageType messageType(boolean storable) {
        return storable ? NEW_ORDER : HEARTBEAT;
    }

    private ReplicatingStore syncStore() {
        return replicatingStore(session("main", FixSessionType.INITIATOR), local, new RecordingProbes());
    }

    @Test
    void aMessageReplicatedBeforeItIsSentIsOnlyStoredLocallyAfter() {
        ReplicatingStore store = syncStore();

        store.replicateSent(messageType(true), 5, message());
        assertThat(local.calls).as("the local store waits for the send").isEmpty();
        store.storeMessageSent(5, message());

        assertThat(local.calls).containsExactly("message 5");
        assertThat(nextEvent()).isEqualTo("0 0 5 " + MESSAGE);
        assertThat(nextEvent()).as("logged once").isEqualTo("none");
    }

    @Test
    void aMessageOfAnUnencryptedTypeIsLoggedPlaintextAndAnUntypedOneOrAnEventWithoutOneIsNot() {
        ReplicatingStore store = replicatingStore(session("main", FixSessionType.INITIATOR).toBuilder()
                .highAvailability(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.ASYNC)
                        .unencryptedMessageTypes(List.of("D")).build())
                .build(), local, new RecordingProbes());

        store.storeMessageSent(NEW_ORDER, 5, message());
        store.storeMessageSent(6, message());
        store.storeNextOutgoingSeqNum(8);

        assertThat(local.calls).containsExactly("message 5", "message 6", "outgoing 8");
        assertThat(nextEvent()).isEqualTo("0 0 5 " + MESSAGE);
        assertThat(writers.getLastFlag()).isEqualTo(RecordFlag.PLAINTEXT);
        assertThat(nextEvent()).isEqualTo("0 0 6 " + MESSAGE);
        assertThat(writers.getLastFlag()).as("untyped").isEqualTo(RecordFlag.NONE);
        assertThat(nextEvent()).isEqualTo("0 2 8 ");
        assertThat(writers.getLastFlag()).isEqualTo(RecordFlag.NONE);
    }

    @Test
    void anAdminMessageReplicatedBeforeItIsSentLogsTheNextOutgoingSeqNumOnce() {
        ReplicatingStore store = syncStore();

        store.replicateSent(messageType(false), 5, message());
        store.storeNextOutgoingSeqNum(6);

        assertThat(local.calls).containsExactly("outgoing 6");
        assertThat(nextEvent()).isEqualTo("0 2 6 ");
        assertThat(nextEvent()).isEqualTo("none");
    }

    @Test
    void aBatchReplicatedBeforeItIsSentIsLoggedOnce() {
        ReplicatingStore store = syncStore();

        for (long seq = 5; seq <= 7; seq++) {
            store.replicateSent(messageType(true), seq, message());
        }
        for (long seq = 5; seq <= 7; seq++) {
            store.storeMessageSent(seq, message());
        }

        assertThat(nextEvent()).startsWith("0 0 5 ");
        assertThat(nextEvent()).startsWith("0 0 6 ");
        assertThat(nextEvent()).startsWith("0 0 7 ");
        assertThat(nextEvent()).isEqualTo("none");
    }

    @Test
    void aWriteNotReplicatedAheadIsLoggedAsAlways() {
        ReplicatingStore store = syncStore();
        store.replicateSent(messageType(true), 5, message());

        store.storeNextOutgoingSeqNum(3);
        store.storeMessageSent(5, message());

        assertThat(nextEvent()).startsWith("0 0 5 ");
        assertThat(nextEvent()).as("an operator setting it lower meanwhile").isEqualTo("0 2 3 ");
        assertThat(nextEvent()).isEqualTo("none");
    }

    @Test
    void aSendWhoseWriteNeverCameIsForgottenByTheNext() {
        ReplicatingStore store = syncStore();
        store.replicateSent(messageType(true), 5, message());
        store.replicateSent(messageType(true), 6, message());

        store.storeMessageSent(6, message());
        store.storeMessageSent(5, message());

        assertThat(nextEvent()).startsWith("0 0 5 ");
        assertThat(nextEvent()).startsWith("0 0 6 ");
        assertThat(nextEvent()).as("5 dropped from those replicated ahead").startsWith("0 0 5 ");
        assertThat(nextEvent()).isEqualTo("none");
    }

    @Test
    void aResetForgetsWhatWasReplicatedAhead() {
        ReplicatingStore store = syncStore();
        store.replicateSent(messageType(true), 5, message());

        store.resetSequenceNumbers();
        store.storeMessageSent(5, message());

        assertThat(nextEvent()).startsWith("0 0 5 ");
        assertThat(nextEvent()).startsWith("0 3 0");
        assertThat(nextEvent()).startsWith("0 0 5 ");
    }

    @Test
    @Timeout(5)
    void aSyncSendWithoutAFollowerInSyncGoesOutAtOnceUnacknowledged() {
        ReplicatingStore store = replicatingStore(FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of("main", FixRegularVersion.VERSION_44, "SENDER", "TARGET"))
                .fixSessionType(FixSessionType.INITIATOR)
                .highAvailability(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.SYNC_APPLIED).build())
                .build(), local, new RecordingProbes());

        store.awaitReplicated();
        assertThat(sessions.all().get(0).getUnacknowledgedSends()).as("nothing written yet").isZero();

        store.storeNextOutgoingSeqNum(2);
        store.awaitReplicated();
        store.storeNextOutgoingSeqNum(3);
        store.awaitReplicated();

        assertThat(sessions.all().get(0).getUnacknowledgedSends()).isEqualTo(2);
        assertThat(sessions.all().get(0).isLastSendUnacknowledged()).isTrue();
    }

    @Test
    void anAcknowledgedSyncSendIsNotCountedUnacknowledged() {
        writers.setAck(StreamWriter.Ack.ACKED);
        ReplicatingStore store = replicatingStore(session("main", FixSessionType.INITIATOR).toBuilder()
                .highAvailability(FixSessionSettings.HaSessionSettings.builder()
                        .replicationMode(ReplicationMode.SYNC_APPLIED).build())
                .build(), local, new RecordingProbes());

        store.storeNextOutgoingSeqNum(2);
        store.awaitReplicated();

        assertThat(sessions.all().get(0).getUnacknowledgedSends()).isZero();
        assertThat(sessions.all().get(0).isLastSendUnacknowledged()).isFalse();
    }

    @Test
    void aStoppedStoreClosesItsWriter() {
        ReplicatingStore store = replicatingStore(session("main", FixSessionType.INITIATOR), local, new RecordingProbes());
        assertThat(writers.getOpenWriters()).isEqualTo(1);

        store.stop(Deadline.unlimited());

        assertThat(writers.getOpenWriters()).isZero();
    }

    @Test
    void writesAllocateNothingPerWrite() {
        local.recording = false;
        writers.setRecording(false);
        ReplicatingStore store = replicatingStore(session("main", FixSessionType.INITIATOR), local, new RecordingProbes());
        ByteBuffer message = message();
        for (int i = 0; i < 20_000; i++) {
            store.storeMessageSent(NEW_ORDER, i, message);
            store.storeNextIncomingSeqNum(i);
        }

        long before = AllocatedBytes.ofCurrentThread();
        for (int i = 0; i < 100_000; i++) {
            store.storeMessageSent(NEW_ORDER, i, message);
            store.storeNextIncomingSeqNum(i);
        }

        assertThat(AllocatedBytes.ofCurrentThread() - before).isLessThan(200_000);
    }

    /**
     * Records the writes and, like the memory store, consumes the message it is given.
     */
    private static final class ConsumingStore implements FixSessionMessagesStore {

        final List<String> calls;
        boolean recording;

        private ConsumingStore() {
            this.calls = new ArrayList<>();
            this.recording = true;
        }

        private void call(String call, long sequenceNumber) {
            if (recording) {
                calls.add(call + sequenceNumber);
            }
        }

        @Override
        public FixSessionMessagesStore start() {
            return this;
        }

        @Override
        public FixSessionMessagesStore stop(Deadline stopDeadline) {
            return this;
        }

        @Override
        public boolean isStarted() {
            return true;
        }

        @Override
        public boolean filter(MessageType messageType, ByteBuffer message) {
            return false;
        }

        @Override
        public void resetSequenceNumbers() {
            if (recording) {
                calls.add("reset");
            }
        }

        @Override
        public long getIncomingSeqNum() {
            return 21;
        }

        @Override
        public long getOutgoingSeqNum() {
            return 12;
        }

        @Override
        public long getNextOutgoingSeqNum() {
            return 11;
        }

        @Override
        public void storeNextIncomingSeqNum(long nextIncomingSeqNum) {
            call("incoming ", nextIncomingSeqNum);
        }

        @Override
        public void storeNextOutgoingSeqNum(long nextOutgoingSeqNum) {
            call("outgoing ", nextOutgoingSeqNum);
        }

        @Override
        public void storeMessageSent(long outgoingSeqNum, ByteBuffer message) {
            call("message ", outgoingSeqNum);
            message.position(message.limit());
        }

        @Override
        public void find(long startSequenceNumber, long stopSequenceNumber, StoreMessagesConsumer consumer) {
            // nothing stored
        }
    }
}
