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
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.ha.ReplicationFixEngineHa;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.ha.wire.FrameDecoder;
import org.lolaf.staffix.ha.wire.RecordingHandler;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestFollowerLink {

    private static final long EPOCH = 7;
    private static final String SESSION = "default.main:VERSION_44:SENDER->TARGET";

    private final IOSession connection = mock(IOSession.class);
    private final RecordingHandler sent = new RecordingHandler();
    private final FrameDecoder decoder = new FrameDecoder(1 << 20);
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
        return new FollowerLink(connection, ha.getLog(), ha.getSessions(), () -> EPOCH, "leader-a", Duration.ofMillis(100),
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

    private FixSessionMessagesStore replicated(String name) {
        return ha.replicatingStore(settings(name), mock(FixSessionMessagesStore.class));
    }

    private void decodeAll(ByteBuffer frames) {
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
    void aFreshFollowerIsWelcomedWithoutAResumeAndNeedsASnapshot() {
        replicated("main").storeNextIncomingSeqNum(5);

        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1);
        fill();

        assertThat(link.getState()).isEqualTo(FollowerLink.State.OUT_OF_SYNC);
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
        long third = ha.getLog().getTailOffset();
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
        assertThat(link.getState()).isEqualTo(FollowerLink.State.OUT_OF_SYNC);
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
    void aFollowerTheLogLapsIsOutOfSync() {
        ha = engine(1024);
        link = link();
        FixSessionMessagesStore store = replicated("main");
        store.storeNextIncomingSeqNum(1);
        link.onHello(EPOCH, WireFormat.VERSION, "follower-b", EPOCH, 0);
        for (int seq = 2; seq < 200; seq++) {
            store.storeNextIncomingSeqNum(seq);
        }

        fill();

        assertThat(link.getState()).isEqualTo(FollowerLink.State.OUT_OF_SYNC);
        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("STORE_EVENT"));
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
        assertThat(sent.frames).containsExactly("HEARTBEAT epoch=7 tail=" + ha.getLog().getTailOffset());
    }

    @Test
    void anAckRecordsWhatTheFollowerApplied() {
        link.onAck(EPOCH, 4096, true);

        assertThat(link.getAppliedOffset()).isEqualTo(4096);
        assertThat(link.isInSync()).isTrue();
        assertThat(List.copyOf(sent.frames)).isEmpty();
    }
}
