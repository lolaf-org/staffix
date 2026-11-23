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

import org.lolaf.replicos.follower.LeaderConnection;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.replicos.wire.CloseReason;
import org.lolaf.replicos.wire.FrameEncoder;
import org.lolaf.replicos.wire.WireFormat;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What only replicating into FIX sessions does with what a leader connection reads: store events written into the
 * stores, the seqnums of a snapshot, outgoing seqnums moved ahead after a handover that did not happen.
 */
class TestFixLeaderConnection extends FixLeaderConnectionHarness {

    @Test
    void aRecordOfAContentTypeNoStoreEventHasClosesTheStream() {
        streaming();
        read((out, index) -> {
            int body = FrameEncoder.recordBody(out, index + WireFormat.RECORD_BODY_OFFSET, 0, (byte) 9, (byte) 0, 1, null, 0, 0);
            return FrameEncoder.recordHeader(out, index, EPOCH, 64, body);
        });

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(sent.frames).last().asString().isEqualTo("CLOSE epoch=7 PROTOCOL_ERROR Unknown store event type 9");
    }

    @Test
    void aSnapshotStateThatIsNotTwoSeqNumsClosesTheStream() {
        streaming();
        read((out, index) -> FrameEncoder.snapshotBegin(out, index, EPOCH, 0, 512, ByteBuffer.allocate(3), 0, 3));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(sent.frames).last().asString()
                .isEqualTo("CLOSE epoch=7 PROTOCOL_ERROR A FIX session's snapshot state is 16 bytes, got 3");
    }

    @Test
    void anAckCarriesWhatTheStoresHaveYetToStore() {
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN));
        engine.store(MAIN).pendingWrites = 7;
        sent.frames.clear();

        read(heartbeat());

        assertThat(sent.frames).containsExactly("ACK epoch=7 applied=-1 inSync=false pending=7");
    }

    @Test
    void aReconnectionKeepsTheStoresAndResumesAfterTheLastEvent() {
        streaming();
        read(event(EPOCH, 0, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 3, null));
        RecordingEngine.RecordingStore store = engine.store(MAIN);

        LeaderConnection next = newConnection(0);
        sent.frames.clear();
        next.hello();
        decode(writeFrames(welcome(EPOCH, 48), session(0, MAIN)), next);

        assertThat(sent.frames).startsWith("HELLO epoch=7 version=1 node=follower-b lastEpoch=7 lastOffset=0 lane=0");
        assertThat(engine.store(MAIN)).isSameAs(store);
        assertThat(store.started).isTrue();
    }

    @Test
    void aSnapshotResetsTheStoreThenFillsItAndPutsTheSessionInSync() {
        streaming();

        read((out, index) -> FixFrames.snapshotBegin(out, index, EPOCH, 0, 512, 21, 13),
                snapshotMessage(11, "eleven"),
                snapshotMessage(12, "twelve"),
                (out, index) -> FixFrames.snapshotEnd(out, index, EPOCH, 0, 2, 22, 14));

        assertThat(engine.store(MAIN).writes).containsExactly("reset", "incoming 21", "outgoing 13", "message 11 eleven",
                "message 12 twelve", "incoming 22", "outgoing 14");
        assertThat(replicaSessions.isInSync()).isTrue();
        assertThat(sent.frames).containsExactly("STREAM_ANSWER epoch=7 key=0 REPLICATING", "ACK epoch=7 applied=-1 inSync=true");
    }

    @Test
    void aSnapshotIntoABatchingStoreWritesTheMessagesOfAReadInOneBatchAndLeavesTheOutgoingSeqnum() {
        engine.batchingStores = true;
        streaming();

        read((out, index) -> FixFrames.snapshotBegin(out, index, EPOCH, 0, 512, 21, 13),
                snapshotMessage(11, "eleven"),
                snapshotMessage(12, "twelve"));
        read(snapshotFragment(13, "thir", FIRST), snapshotFragment(13, "teen", LAST),
                (out, index) -> FixFrames.snapshotEnd(out, index, EPOCH, 0, 3, 22, 14));

        assertThat(engine.store(MAIN).writes).containsExactly("reset", "incoming 21", "outgoing 13",
                "batch 11 eleven, 12 twelve", "batch 13 thirteen", "incoming 22", "outgoing 14");
        assertThat(sent.frames).contains("STREAM_ANSWER epoch=7 key=0 REPLICATING");
    }

    @Test
    void aNewSnapshotDropsWhatTheReadLeftPendingForItsSession() {
        engine.batchingStores = true;
        streaming();

        read((out, index) -> FixFrames.snapshotBegin(out, index, EPOCH, 0, 512, 21, 13),
                snapshotMessage(11, "eleven"),
                event(EPOCH, 600, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 22, null),
                snapshotMessage(12, "twelve"),
                (out, index) -> FixFrames.snapshotBegin(out, index, EPOCH, 0, 700, 21, 13),
                snapshotMessage(13, "thirteen"));

        assertThat(engine.store(MAIN).writes).containsExactly("reset", "incoming 21", "outgoing 13",
                "reset", "incoming 21", "outgoing 13", "batch 13 thirteen");
    }

    @Test
    void theFragmentsOfAMessageAreAppliedAsOneMessageOnceTheLastArrives() {
        FixSessionSettings other = RecordingEngine.session("other", FixSessionType.INITIATOR);
        streaming();
        read(session(3, other));

        read(fragment(0, 0, 5, "8=FIX.4.4\u0001", FIRST), event(EPOCH, 64, 3, StoreEventType.STORE_INCOMING_SEQ_NUM, 2, null));
        assertThat(engine.store(MAIN).writes).as("nothing until the last fragment").isEmpty();
        read(fragment(96, 0, 5, "9=5\u0001", MIDDLE), fragment(128, 0, 5, "35=D\u000110=000\u0001", LAST));

        assertThat(engine.store(MAIN).writes).containsExactly("message 5 " + MESSAGE);
        assertThat(followerStreams.getLastAppliedOffset(0)).isEqualTo(128);
    }

    @Test
    void aStreamResumedInTheMiddleOfAMessageCatchesItsSessionUpBySnapshot() {
        streaming();
        complete();
        read(fragment(0, 0, 5, "8=FIX.4.4", FIRST));

        LeaderConnection resumed = newConnection(0);
        sent.frames.clear();
        decode(writeFrames(welcome(EPOCH, 0), session(0, MAIN)), resumed);

        assertThat(sent.frames).containsExactly("STREAM_ANSWER epoch=7 key=0 SNAPSHOT_NEEDED");
        decode(writeFrames(fragment(64, 0, 5, "9=5", LAST)), resumed);
        assertThat(engine.store(MAIN).writes).as("the rest of a message alone is not applied").noneMatch(w -> w.startsWith("message"));
    }

    @Test
    void aCleanShutdownHandsOverEveryCompleteSessionWithoutAJump() {
        streaming();
        complete();

        read(close(CloseReason.SHUTDOWN));
        replicaSessions.jumpOutgoingSeqNums(1_000);

        assertThat(engine.store(MAIN).writes).isEmpty();
    }

    @Test
    void aSessionIncompleteAtTheShutdownIsMovedAhead() {
        streaming();

        read(close(CloseReason.SHUTDOWN));
        replicaSessions.jumpOutgoingSeqNums(1_000);

        assertThat(engine.store(MAIN).writes).containsExactly("outgoing 1001");
    }

    @Test
    void aCloseForAnyOtherReasonIsNoHandOver() {
        streaming();
        complete();

        read(close(CloseReason.TOO_SLOW));
        replicaSessions.jumpOutgoingSeqNums(1_000);

        assertThat(engine.store(MAIN).writes).containsExactly("outgoing 1001");
    }

    @Test
    void aHandOverLastsUntilTheNextStream() {
        streaming();
        complete();
        read(close(CloseReason.SHUTDOWN));

        decode(writeFrames(welcome(EPOCH, 48)), newConnection(0));
        replicaSessions.jumpOutgoingSeqNums(1_000);

        assertThat(engine.store(MAIN).writes).containsExactly("outgoing 1001");
    }

    @Test
    void closingTheReplicaStopsItsStores() {
        streaming();

        followerStreams.close(Deadline.unlimited());

        assertThat(engine.store(MAIN).started).isFalse();
    }
}
