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
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.wire.CloseReason;
import org.lolaf.staffix.ha.wire.FrameDecoder;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.FrameHandler;
import org.lolaf.staffix.ha.wire.RecordingHandler;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.ToIntBiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLeaderLink {

    private static final long EPOCH = 7;
    private static final FixSessionSettings MAIN = RecordingEngine.session("main", FixSessionType.INITIATOR);
    private static final String MESSAGE = "8=FIX.4.4\u00019=5\u000135=D\u000110=000\u0001";

    private final IOSession connection = mock(IOSession.class);
    private final RecordingHandler sent = new RecordingHandler();
    private final RecordingEngine engine = new RecordingEngine().with(MAIN);
    private final Replica replica = new Replica(engine);
    private final LeaderLink link = new LeaderLink(connection, replica, () -> EPOCH, "follower-b");

    TestLeaderLink() {
        when(connection.borrow(anyInt())).then(invocation -> ByteBuffer.allocate(invocation.getArgument(0)));
        doAnswer(invocation -> {
            ByteBuffer frames = invocation.getArgument(0);
            decode(frames.duplicate().flip(), sent);
            return null;
        }).when(connection).send(any(ByteBuffer.class), anyBoolean());
    }

    private static void decode(ByteBuffer frames, FrameHandler handler) {
        FrameDecoder decoder = new FrameDecoder(1 << 20);
        int index = frames.position();
        int consumed;
        while (index < frames.limit() && (consumed = decoder.decode(frames, index, frames.limit() - index, handler)) > 0) {
            index += consumed;
        }
    }

    /**
     * Hands the link the frames written by each encoder in one read, as the receiver does.
     */
    @SafeVarargs
    private void read(ToIntBiFunction<ByteBuffer, Integer>... frames) {
        decode(writeFrames(frames), link);
        link.afterRead();
    }

    private static ToIntBiFunction<ByteBuffer, Integer> welcome(long epoch, long resumeOffset) {
        return (out, index) -> FrameEncoder.welcome(out, index, epoch, "leader-a", resumeOffset);
    }

    private static ToIntBiFunction<ByteBuffer, Integer> session(int key, FixSessionSettings settings) {
        return session(key, settings, "leader-storage");
    }

    private static ToIntBiFunction<ByteBuffer, Integer> session(int key, FixSessionSettings settings, String leaderStorageIdentity) {
        return (out, index) -> FrameEncoder.session(out, index, EPOCH, key, settings.getFixSessionType(), ReplicationMode.SYNC,
                settings.getFixSessionId().toString(), leaderStorageIdentity);
    }

    private static ToIntBiFunction<ByteBuffer, Integer> event(long epoch, long offset, int key, StoreEventType type, long seq,
                                                              String message) {
        return (out, index) -> FrameEncoder.storeEvent(out, index, epoch, offset, key, type, seq,
                message == null ? null : ByteBuffer.wrap(message.getBytes(StandardCharsets.US_ASCII)));
    }

    private static ToIntBiFunction<ByteBuffer, Integer> snapshotMessage(long seq, String message) {
        return (out, index) -> FrameEncoder.snapshotMessage(out, index, EPOCH, 0, seq,
                ByteBuffer.wrap(message.getBytes(StandardCharsets.US_ASCII)));
    }

    private void streaming() {
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN));
        sent.frames.clear();
    }

    @Test
    void theHelloCarriesTheLastEventApplied() {
        link.hello();

        assertThat(sent.frames).containsExactly("HELLO epoch=7 version=1 node=follower-b lastEpoch=0 lastOffset=-1");
    }

    @Test
    void aWelcomeStartsTheStreamAndIsAcknowledged() {
        read(welcome(EPOCH, WireFormat.NO_RESUME));

        assertThat(link.getState()).isEqualTo(LeaderLink.State.STREAMING);
        assertThat(link.getLeaderId()).isEqualTo("leader-a");
        assertThat(sent.frames).as("nothing replicated yet, nothing missing").containsExactly("ACK epoch=7 applied=-1 inSync=true");
    }

    @Test
    void aLeaderOfAnOlderEpochIsRefused() {
        read(welcome(EPOCH - 1, 0));

        assertThat(link.getState()).isEqualTo(LeaderLink.State.CLOSED);
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 STALE_EPOCH");
        verify(connection).stop(any());
    }

    @Test
    void aSessionConfiguredHereIsReplicatedIntoItsOwnStartedStoreOnceASnapshotFillsIt() {
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN));

        assertThat(engine.store(MAIN).started).isTrue();
        assertThat(sent.frames).containsExactly("SESSION_STATUS epoch=7 key=0 SNAPSHOT_NEEDED", "ACK epoch=7 applied=-1 inSync=false");
    }

    @Test
    void aSessionUnknownHereIsReportedAndNotReplicated() {
        FixSessionSettings other = RecordingEngine.session("other", FixSessionType.INITIATOR);
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(3, other),
                event(EPOCH, 0, 3, StoreEventType.STORE_INCOMING_SEQ_NUM, 2, null));

        assertThat(engine.stores).isEmpty();
        assertThat(sent.frames).containsExactly("SESSION_STATUS epoch=7 key=3 UNKNOWN_SESSION", "ACK epoch=7 applied=0 inSync=true");
    }

    @Test
    void aSessionWhoseStorageIsTheLeadersOwnIsNeitherStartedNorWrittenTo() {
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN, "follower-storage"),
                event(EPOCH, 0, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 2, null));

        assertThat(engine.store(MAIN).started).isFalse();
        assertThat(engine.store(MAIN).writes).isEmpty();
        assertThat(sent.frames).containsExactly("SESSION_STATUS epoch=7 key=0 SHARED_STORAGE", "ACK epoch=7 applied=0 inSync=true");
    }

    @Test
    void aSessionWhoseStorageCannotBeIdentifiedIsRefused() {
        engine.nextStorageIdentity = null;

        read(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN));

        assertThat(engine.store(MAIN).started).isFalse();
        assertThat(sent.frames).first().isEqualTo("SESSION_STATUS epoch=7 key=0 UNKNOWN_STORAGE");
    }

    @Test
    void aSessionTheLeaderCannotIdentifyIsRefused() {
        read(welcome(EPOCH, WireFormat.NO_RESUME), (out, index) -> FrameEncoder.session(out, index, EPOCH, 0,
                MAIN.getFixSessionType(), ReplicationMode.SYNC, MAIN.getFixSessionId().toString(), null));

        assertThat(sent.frames).first().isEqualTo("SESSION_STATUS epoch=7 key=0 UNKNOWN_STORAGE");
    }

    @Test
    void aRefusedSessionIsLookedAtAgainOnTheNextConnection() {
        engine.nextStorageIdentity = null;
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN));
        engine.nextStorageIdentity = "follower-storage-back";

        LeaderLink next = new LeaderLink(connection, replica, () -> EPOCH, "follower-b");
        sent.frames.clear();
        decode(writeFrames(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN)), next);

        assertThat(sent.frames).containsExactly("SESSION_STATUS epoch=7 key=0 SNAPSHOT_NEEDED");
        assertThat(engine.store(MAIN).started).isTrue();
    }

    @Test
    void storeEventsAreAppliedInOrderAndAcknowledgedOncePerRead() {
        streaming();

        read(event(EPOCH, 0, 0, StoreEventType.STORE_MESSAGE, 5, MESSAGE),
                event(EPOCH, 64, 0, StoreEventType.STORE_OUTGOING_SEQ_NUM, 7, null),
                event(EPOCH, 96, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 3, null),
                event(EPOCH, 128, 0, StoreEventType.RESET_SEQ_NUM, 0, null));

        assertThat(engine.store(MAIN).writes).containsExactly("message 5 " + MESSAGE, "outgoing 7", "incoming 3", "reset");
        assertThat(replica.getLastAppliedEpoch()).isEqualTo(EPOCH);
        assertThat(replica.getLastAppliedOffset()).isEqualTo(128);
        assertThat(sent.frames).containsExactly("ACK epoch=7 applied=128 inSync=false");
    }

    @Test
    void aReconnectionKeepsTheStoresAndResumesAfterTheLastEvent() {
        streaming();
        read(event(EPOCH, 0, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 3, null));
        RecordingEngine.RecordingStore store = engine.store(MAIN);

        LeaderLink next = new LeaderLink(connection, replica, () -> EPOCH, "follower-b");
        sent.frames.clear();
        next.hello();
        decode(writeFrames(welcome(EPOCH, 48), session(0, MAIN)), next);

        assertThat(sent.frames).startsWith("HELLO epoch=7 version=1 node=follower-b lastEpoch=7 lastOffset=0");
        assertThat(engine.store(MAIN)).isSameAs(store);
        assertThat(store.started).isTrue();
    }

    @Test
    void aSnapshotResetsTheStoreThenFillsItAndPutsTheSessionInSync() {
        streaming();

        read((out, index) -> FrameEncoder.snapshotBegin(out, index, EPOCH, 0, 512, 21, 13),
                snapshotMessage(11, "eleven"),
                snapshotMessage(12, "twelve"),
                (out, index) -> FrameEncoder.snapshotEnd(out, index, EPOCH, 0, 2));

        assertThat(engine.store(MAIN).writes).containsExactly("reset", "incoming 21", "outgoing 13", "message 11 eleven",
                "message 12 twelve", "outgoing 13");
        assertThat(replica.isComplete()).isTrue();
        assertThat(sent.frames).containsExactly("SESSION_STATUS epoch=7 key=0 REPLICATING", "ACK epoch=7 applied=-1 inSync=true");
    }

    @Test
    void aSnapshotEndingWithAnotherCountThanReceivedIsAProtocolError() {
        streaming();

        read((out, index) -> FrameEncoder.snapshotBegin(out, index, EPOCH, 0, 512, 21, 13),
                snapshotMessage(11, "eleven"),
                (out, index) -> FrameEncoder.snapshotEnd(out, index, EPOCH, 0, 2));

        assertThat(replica.isComplete()).isFalse();
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
    }

    @Test
    void aResumedStreamKeepsTheSessionsCompleteAndOneThatDidNotResumeLosesThem() {
        streaming();
        read((out, index) -> FrameEncoder.snapshotBegin(out, index, EPOCH, 0, 0, 1, 1),
                (out, index) -> FrameEncoder.snapshotEnd(out, index, EPOCH, 0, 0));
        read(event(EPOCH, 0, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 3, null));

        LeaderLink resumed = new LeaderLink(connection, replica, () -> EPOCH, "follower-b");
        sent.frames.clear();
        decode(writeFrames(welcome(EPOCH, 48), session(0, MAIN)), resumed);
        assertThat(sent.frames).containsExactly("SESSION_STATUS epoch=7 key=0 REPLICATING");

        LeaderLink notResumed = new LeaderLink(connection, replica, () -> EPOCH, "follower-b");
        sent.frames.clear();
        decode(writeFrames(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN)), notResumed);
        assertThat(sent.frames).containsExactly("SESSION_STATUS epoch=7 key=0 SNAPSHOT_NEEDED");
        assertThat(replica.getLastAppliedOffset()).as("nothing left to resume from").isEqualTo(-1);
    }

    private void complete() {
        read((out, index) -> FrameEncoder.snapshotBegin(out, index, EPOCH, 0, 0, 1, 1),
                (out, index) -> FrameEncoder.snapshotEnd(out, index, EPOCH, 0, 0));
        engine.store(MAIN).writes.clear();
    }

    private static ToIntBiFunction<ByteBuffer, Integer> close(CloseReason reason) {
        return (out, index) -> FrameEncoder.close(out, index, EPOCH, reason, "test");
    }

    @Test
    void aCleanShutdownHandsOverEveryCompleteSessionWithoutAJump() {
        streaming();
        complete();

        read(close(CloseReason.SHUTDOWN));
        replica.jumpOutgoingSeqNums(1_000);

        assertThat(engine.store(MAIN).writes).isEmpty();
    }

    @Test
    void aSessionIncompleteAtTheShutdownIsMovedAhead() {
        streaming();

        read(close(CloseReason.SHUTDOWN));
        replica.jumpOutgoingSeqNums(1_000);

        assertThat(engine.store(MAIN).writes).containsExactly("outgoing 1001");
    }

    @Test
    void aCloseForAnyOtherReasonIsNoHandOver() {
        streaming();
        complete();

        read(close(CloseReason.LAPPED));
        replica.jumpOutgoingSeqNums(1_000);

        assertThat(engine.store(MAIN).writes).containsExactly("outgoing 1001");
    }

    @Test
    void aHandOverLastsUntilTheNextStream() {
        streaming();
        complete();
        read(close(CloseReason.SHUTDOWN));

        decode(writeFrames(welcome(EPOCH, 48)), new LeaderLink(connection, replica, () -> EPOCH, "follower-b"));
        replica.jumpOutgoingSeqNums(1_000);

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
    void aFrameBeforeTheWelcomeIsAProtocolError() {
        read((out, index) -> FrameEncoder.heartbeat(out, index, EPOCH, 0));

        assertThat(link.getState()).isEqualTo(LeaderLink.State.CLOSED);
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
    }

    @Test
    void closingTheReplicaStopsItsStores() {
        streaming();

        replica.close(Deadline.unlimited());

        assertThat(engine.store(MAIN).started).isFalse();
    }

    @SafeVarargs
    private static ByteBuffer writeFrames(ToIntBiFunction<ByteBuffer, Integer>... frames) {
        ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
        int length = 0;
        for (ToIntBiFunction<ByteBuffer, Integer> frame : frames) {
            length += frame.applyAsInt(buffer, length);
        }
        return buffer.limit(length);
    }
}
