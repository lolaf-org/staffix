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
package org.lolaf.staffix.ha.follower;

import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.wire.CloseReason;
import org.lolaf.staffix.ha.wire.FrameDecoder;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.WireFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

class TestLeaderConnection extends LeaderConnectionHarness {

    @Test
    void theWelcomeGivesTheReplicaTheLeadersLaneCount() {
        read(welcome(EPOCH, WireFormat.NO_RESUME, 3));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.STREAMING);
        assertThat(replicaSessions.getLanes()).isEqualTo(3);
    }

    @Test
    void aWelcomeOnALaneTheLeaderDoesNotHaveClosesTheStream() {
        LeaderConnection second = newConnection(1);

        decode(writeFrames(welcome(EPOCH, WireFormat.NO_RESUME, 1)), second);

        assertThat(second.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
    }

    @Test
    void aSessionOfAnotherLaneClosesTheStream() {
        read(welcome(EPOCH, WireFormat.NO_RESUME, 2), session(1, MAIN));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR session key 1 on lane 0 of 2");
    }

    @Test
    void eachLaneResumesFromTheLastEventItApplied() {
        LeaderConnection second = newConnection(1);
        read(welcome(EPOCH, WireFormat.NO_RESUME, 2));
        second.read(writeFrames(welcome(EPOCH, WireFormat.NO_RESUME, 2), session(1, MAIN),
                event(EPOCH, 96, 1, StoreEventType.STORE_INCOMING_SEQ_NUM, 2, null)), new FrameDecoder(1 << 20));
        sent.frames.clear();

        connection.hello();
        second.hello();

        assertThat(sent.frames).containsExactly(
                "HELLO epoch=7 version=1 node=follower-b lastEpoch=0 lastOffset=-1 lane=0",
                "HELLO epoch=7 version=1 node=follower-b lastEpoch=7 lastOffset=96 lane=1");
    }

    @Test
    void theHelloCarriesTheLastEventApplied() {
        connection.hello();

        assertThat(sent.frames).containsExactly("HELLO epoch=7 version=1 node=follower-b lastEpoch=0 lastOffset=-1 lane=0");
    }

    @Test
    void aWelcomeStartsTheStreamAndIsAcknowledged() {
        read(welcome(EPOCH, WireFormat.NO_RESUME));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.STREAMING);
        assertThat(connection.getLeaderId()).isEqualTo("leader-a");
        assertThat(sent.frames).as("nothing replicated yet, nothing missing").containsExactly("ACK epoch=7 applied=-1 inSync=true");
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
    void aNewConnectionAsksTheStoresForItsFirstAck() {
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN));
        engine.store(MAIN).pendingWrites = 7;
        sent.frames.clear();
        LeaderConnection checkingHourly = checkingHourly();

        checkingHourly.read(writeFrames(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN), heartbeat()), new FrameDecoder(1 << 20));

        assertThat(sent.frames).filteredOn(frame -> frame.startsWith("ACK")).first().asString().endsWith("pending=7");
    }

    @Test
    void theStoresAreAskedAtMostOnceACheckInterval() {
        LeaderConnection checkingHourly = checkingHourly();
        checkingHourly.read(writeFrames(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN)), new FrameDecoder(1 << 20));
        engine.store(MAIN).pendingWrites = 7;
        sent.frames.clear();

        checkingHourly.read(writeFrames(heartbeat()), new FrameDecoder(1 << 20));

        assertThat(sent.frames).containsExactly("ACK epoch=7 applied=-1 inSync=false");
    }

    @Test
    void theStoresAreAskedAtOnceOnTurningInSync() {
        LeaderConnection checkingHourly = checkingHourly();
        checkingHourly.read(writeFrames(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN)), new FrameDecoder(1 << 20));
        engine.store(MAIN).pendingWrites = 7;
        sent.frames.clear();

        checkingHourly.read(writeFrames((out, index) -> FrameEncoder.snapshotBegin(out, index, EPOCH, 0, 512, 21, 13),
                (out, index) -> FrameEncoder.snapshotEnd(out, index, EPOCH, 0, 0, 22, 14)), new FrameDecoder(1 << 20));

        assertThat(sent.frames).last().isEqualTo("ACK epoch=7 applied=-1 inSync=true pending=7");
    }

    @Test
    void aLeaderOfAnOlderEpochIsRefused() {
        read(welcome(EPOCH - 1, 0));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 STALE_EPOCH");
        verify(ioSession).stop(any());
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

        read((out, index) -> FrameEncoder.snapshotBegin(out, index, EPOCH, 0, 512, 21, 13),
                snapshotMessage(11, "eleven"),
                snapshotMessage(12, "twelve"),
                (out, index) -> FrameEncoder.snapshotEnd(out, index, EPOCH, 0, 2, 22, 14));

        assertThat(engine.store(MAIN).writes).containsExactly("reset", "incoming 21", "outgoing 13", "message 11 eleven",
                "message 12 twelve", "incoming 22", "outgoing 14");
        assertThat(replicaSessions.isInSync()).isTrue();
        assertThat(sent.frames).containsExactly("SESSION_ANSWER epoch=7 key=0 REPLICATING", "ACK epoch=7 applied=-1 inSync=true");
    }

    @Test
    void aSnapshotIntoABatchingStoreWritesTheMessagesOfAReadInOneBatchAndLeavesTheOutgoingSeqnum() {
        engine.batchingStores = true;
        streaming();

        read((out, index) -> FrameEncoder.snapshotBegin(out, index, EPOCH, 0, 512, 21, 13),
                snapshotMessage(11, "eleven"),
                snapshotMessage(12, "twelve"));
        read(snapshotFragment(13, "thir", FIRST), snapshotFragment(13, "teen", LAST),
                (out, index) -> FrameEncoder.snapshotEnd(out, index, EPOCH, 0, 3, 22, 14));

        assertThat(engine.store(MAIN).writes).containsExactly("reset", "incoming 21", "outgoing 13",
                "batch 11 eleven, 12 twelve", "batch 13 thirteen", "incoming 22", "outgoing 14");
        assertThat(sent.frames).contains("SESSION_ANSWER epoch=7 key=0 REPLICATING");
    }

    @Test
    void aNewSnapshotDropsWhatTheReadLeftPendingForItsSession() {
        engine.batchingStores = true;
        streaming();

        read((out, index) -> FrameEncoder.snapshotBegin(out, index, EPOCH, 0, 512, 21, 13),
                snapshotMessage(11, "eleven"),
                event(EPOCH, 600, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 22, null),
                snapshotMessage(12, "twelve"),
                (out, index) -> FrameEncoder.snapshotBegin(out, index, EPOCH, 0, 700, 21, 13),
                snapshotMessage(13, "thirteen"));

        assertThat(engine.store(MAIN).writes).containsExactly("reset", "incoming 21", "outgoing 13",
                "reset", "incoming 21", "outgoing 13", "batch 13 thirteen");
    }

    @Test
    void aSnapshotEndingWithAnotherCountThanReceivedIsAProtocolError() {
        streaming();

        read((out, index) -> FrameEncoder.snapshotBegin(out, index, EPOCH, 0, 512, 21, 13),
                snapshotMessage(11, "eleven"),
                (out, index) -> FrameEncoder.snapshotEnd(out, index, EPOCH, 0, 2, 21, 13));

        assertThat(replicaSessions.isInSync()).isFalse();
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
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
        assertThat(replicaSessions.getLastAppliedOffset(0)).isEqualTo(128);
    }

    @Test
    void aSnapshotMessageInFragmentsIsStoredOnceAndCountedOnce() {
        streaming();

        read((out, index) -> FrameEncoder.snapshotBegin(out, index, EPOCH, 0, 0, 1, 6),
                snapshotFragment(5, "8=FIX.4.4\u0001", FIRST), snapshotFragment(5, "9=5\u000135=D\u0001", MIDDLE),
                snapshotFragment(5, "10=000\u0001", LAST),
                (out, index) -> FrameEncoder.snapshotEnd(out, index, EPOCH, 0, 1, 1, 6));

        assertThat(engine.store(MAIN).writes).contains("message 5 " + MESSAGE);
        assertThat(sent.frames).contains("SESSION_ANSWER epoch=7 key=0 REPLICATING");
    }

    @Test
    void aFragmentOfAnotherMessageClosesTheStream() {
        streaming();

        read(fragment(0, 0, 5, "8=FIX.4.4", FIRST), fragment(64, 0, 6, "9=5", LAST));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(sent.frames).anyMatch(frame -> frame.startsWith("CLOSE epoch=7 PROTOCOL_ERROR"));
        assertThat(engine.store(MAIN).writes).isEmpty();
    }

    /**
     * Resuming starts after the last event applied, which may be past the message's first fragments: only a snapshot
     * gives the session that message.
     */
    @Test
    void aStreamResumedInTheMiddleOfAMessageCatchesItsSessionUpBySnapshot() {
        streaming();
        complete();
        read(fragment(0, 0, 5, "8=FIX.4.4", FIRST));

        LeaderConnection resumed = newConnection(0);
        sent.frames.clear();
        decode(writeFrames(welcome(EPOCH, 0), session(0, MAIN)), resumed);

        assertThat(sent.frames).containsExactly("SESSION_ANSWER epoch=7 key=0 SNAPSHOT_NEEDED");
        decode(writeFrames(fragment(64, 0, 5, "9=5", LAST)), resumed);
        assertThat(engine.store(MAIN).writes).as("the rest of a message alone is not applied").noneMatch(w -> w.startsWith("message"));
    }

    @Test
    void aResumedStreamKeepsTheSessionsCompleteAndOneThatDidNotResumeLosesThem() {
        streaming();
        read((out, index) -> FrameEncoder.snapshotBegin(out, index, EPOCH, 0, 0, 1, 1),
                (out, index) -> FrameEncoder.snapshotEnd(out, index, EPOCH, 0, 0, 1, 1));
        read(event(EPOCH, 0, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 3, null));

        LeaderConnection resumed = newConnection(0);
        sent.frames.clear();
        decode(writeFrames(welcome(EPOCH, 48), session(0, MAIN)), resumed);
        assertThat(sent.frames).containsExactly("SESSION_ANSWER epoch=7 key=0 REPLICATING");

        LeaderConnection notResumed = newConnection(0);
        sent.frames.clear();
        decode(writeFrames(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN)), notResumed);
        assertThat(sent.frames).containsExactly("SESSION_ANSWER epoch=7 key=0 SNAPSHOT_NEEDED");
        assertThat(replicaSessions.getLastAppliedOffset(0)).as("nothing left to resume from").isEqualTo(-1);
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
    void aFrameOfAnotherEpochClosesTheStream() {
        streaming();

        read(event(EPOCH + 1, 0, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 3, null));

        assertThat(engine.store(MAIN).writes).isEmpty();
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 STALE_EPOCH");
    }

    @Test
    void anEventOfASessionNeverAnnouncedIsAProtocolError() {
        streaming();

        read(event(EPOCH, 0, 1, StoreEventType.STORE_INCOMING_SEQ_NUM, 3, null));

        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
    }

    @Test
    void aSyncReceivedSessionsEventIsAcknowledgedAsReceivedBeforeItIsWritten() {
        streamingSyncReceived();

        read(event(EPOCH, 0, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 2, null));

        assertThat(sent.frames).containsExactly("RECEIVED epoch=7 received=0", "ACK epoch=7 applied=0 inSync=false");
    }

    @Test
    void anotherModesEventIsAcknowledgedAsAppliedOnly() {
        streaming();

        read(event(EPOCH, 0, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 2, null));

        assertThat(sent.frames).singleElement().asString().startsWith("ACK epoch=7 applied=0");
    }

    @Test
    void aReadWithoutNewEventsIsAcknowledgedAsAppliedOnly() {
        streamingSyncReceived();
        read(event(EPOCH, 0, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 2, null));
        sent.frames.clear();

        read((out, index) -> FrameEncoder.heartbeat(out, index, EPOCH, 0));

        assertThat(sent.frames).singleElement().asString().startsWith("ACK epoch=7 applied=0");
    }

    @Test
    void aFrameBeforeTheWelcomeIsAProtocolError() {
        read((out, index) -> FrameEncoder.heartbeat(out, index, EPOCH, 0));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
    }

    @Test
    void closingTheReplicaStopsItsStores() {
        streaming();

        replicaSessions.close(Deadline.unlimited());

        assertThat(engine.store(MAIN).started).isFalse();
    }
}
