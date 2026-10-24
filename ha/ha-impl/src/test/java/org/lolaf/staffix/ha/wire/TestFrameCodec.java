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
package org.lolaf.staffix.ha.wire;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestFrameCodec {

    private static final int MAX_FRAME_BYTES = 4096;
    private static final byte[] MESSAGE = "8=FIX.4.4\u00019=5\u000135=D\u000110=000\u0001".getBytes(StandardCharsets.US_ASCII);

    private final FrameDecoder decoder = new FrameDecoder(MAX_FRAME_BYTES);
    private final RecordingHandler handler = new RecordingHandler();

    private static ByteBuffer buffer(boolean direct) {
        return direct ? ByteBuffer.allocateDirect(MAX_FRAME_BYTES) : ByteBuffer.allocate(MAX_FRAME_BYTES);
    }

    private static ByteBuffer message() {
        return ByteBuffer.wrap(MESSAGE);
    }

    private void decodeWhole(ByteBuffer buffer, int index, int length) {
        assertThat(decoder.decode(buffer, index, length, handler)).isEqualTo(length);
    }

    @Test
    void hello() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.hello(buffer, 0, 7, "node-b", 6, 1234);

        decodeWhole(buffer, 0, length);

        assertThat(handler.frames).containsExactly("HELLO epoch=7 version=1 node=node-b lastEpoch=6 lastOffset=1234");
    }

    @Test
    void welcome() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.welcome(buffer, 0, 7, "node-a", WireFormat.NO_RESUME);

        decodeWhole(buffer, 0, length);

        assertThat(handler.frames).containsExactly("WELCOME epoch=7 version=1 leader=node-a resume=-1");
    }

    @Test
    void sessionWithAStorageIdentity() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.session(buffer, 0, 7, 3, FixSessionType.INITIATOR, ReplicationMode.SYNC,
                "default.main:VERSION_44:SENDER->TARGET", "jdbc-postgres-1");

        decodeWhole(buffer, 0, length);

        assertThat(handler.frames).containsExactly(
                "SESSION epoch=7 key=3 INITIATOR SYNC id=default.main:VERSION_44:SENDER->TARGET identity=jdbc-postgres-1");
    }

    @Test
    void sessionWithoutAStorageIdentityIsToldApartFromAnEmptyOne() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.session(buffer, 0, 7, 3, FixSessionType.ACCEPTOR, ReplicationMode.ASYNC, "s", null);

        decodeWhole(buffer, 0, length);

        assertThat(handler.frames).containsExactly("SESSION epoch=7 key=3 ACCEPTOR ASYNC id=s identity=null");
    }

    @ParameterizedTest
    @EnumSource(ReplicationMode.class)
    void everyReplicationModeAndSessionTypeRoundTrips(ReplicationMode mode) {
        for (FixSessionType type : FixSessionType.values()) {
            ByteBuffer buffer = buffer(false);
            int length = FrameEncoder.session(buffer, 0, 7, 3, type, mode, "s", null);

            decodeWhole(buffer, 0, length);
        }

        assertThat(handler.frames).containsExactly(
                "SESSION epoch=7 key=3 INITIATOR " + mode + " id=s identity=null",
                "SESSION epoch=7 key=3 ACCEPTOR " + mode + " id=s identity=null");
    }

    @Test
    void anUnknownReplicationModeIsRefused() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.session(buffer, 0, 7, 3, FixSessionType.ACCEPTOR, ReplicationMode.SYNC, "s", null);
        buffer.put(WireFormat.HEADER_BYTES + 5, (byte) 9);

        assertThatThrownBy(() -> decoder.decode(buffer, 0, length, handler))
                .isInstanceOf(WireFormatException.class).hasMessage("Unknown replication mode code 9");
    }

    @Test
    void sessionStatus() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.sessionStatus(buffer, 0, 7, 3, SessionStatus.SHARED_STORAGE);

        decodeWhole(buffer, 0, length);

        assertThat(handler.frames).containsExactly("SESSION_STATUS epoch=7 key=3 SHARED_STORAGE");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void storeEventCarriesTheMessageAndLeavesItsBufferAlone(boolean direct) {
        ByteBuffer buffer = buffer(direct);
        ByteBuffer message = message();
        int length = FrameEncoder.storeEvent(buffer, 100, 7, 4096, 3, StoreEventType.STORE_MESSAGE, 42, message);

        decodeWhole(buffer, 100, length);

        assertThat(length).isEqualTo(FrameEncoder.storeEventBytes(MESSAGE.length));
        assertThat(message.position()).isZero();
        assertThat(buffer.position()).isZero();
        assertThat(handler.frames).containsExactly("STORE_EVENT epoch=7 offset=4096 key=3 STORE_MESSAGE seq=42 message="
                + new String(MESSAGE, StandardCharsets.US_ASCII));
    }

    @Test
    void storeEventWithoutAMessage() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.storeEvent(buffer, 0, 7, 4096, 3, StoreEventType.STORE_INCOMING_SEQ_NUM, 18, null);

        decodeWhole(buffer, 0, length);

        assertThat(handler.frames).containsExactly("STORE_EVENT epoch=7 offset=4096 key=3 STORE_INCOMING_SEQ_NUM seq=18 message=");
    }

    @Test
    void snapshot() {
        ByteBuffer buffer = buffer(false);
        int begin = FrameEncoder.snapshotBegin(buffer, 0, 7, 3, 8192, 19, 43);
        int message = FrameEncoder.snapshotMessage(buffer, begin, 7, 3, 42, message());
        int end = FrameEncoder.snapshotEnd(buffer, begin + message, 7, 3, 1);

        decodeWhole(buffer, 0, begin);
        decodeWhole(buffer, begin, message);
        decodeWhole(buffer, begin + message, end);

        assertThat(handler.frames).containsExactly(
                "SNAPSHOT_BEGIN epoch=7 key=3 cut=8192 nextIn=19 nextOut=43",
                "SNAPSHOT_MESSAGE epoch=7 key=3 seq=42 message=" + new String(MESSAGE, StandardCharsets.US_ASCII),
                "SNAPSHOT_END epoch=7 key=3 count=1");
    }

    @Test
    void ackAndHeartbeat() {
        ByteBuffer buffer = buffer(false);
        int ack = FrameEncoder.ack(buffer, 0, 7, 4096, true);
        int heartbeat = FrameEncoder.heartbeat(buffer, ack, 7, 8192);

        decodeWhole(buffer, 0, ack);
        decodeWhole(buffer, ack, heartbeat);

        assertThat(handler.frames).containsExactly("ACK epoch=7 applied=4096 inSync=true", "HEARTBEAT epoch=7 tail=8192");
    }

    @Test
    void close() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.close(buffer, 0, 7, CloseReason.STALE_EPOCH, "epoch 7 is behind 8");

        decodeWhole(buffer, 0, length);

        assertThat(handler.frames).containsExactly("CLOSE epoch=7 STALE_EPOCH epoch 7 is behind 8");
    }

    @Test
    void aCloseReasonFromANewerPeerIsStillReported() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.close(buffer, 0, 7, CloseReason.SHUTDOWN, "");
        buffer.putShort(WireFormat.HEADER_BYTES, (short) 99);

        decodeWhole(buffer, 0, length);

        assertThat(handler.frames).containsExactly("CLOSE epoch=7 UNKNOWN ");
    }

    @Test
    void aPartialFrameWaitsForTheRest() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.heartbeat(buffer, 0, 7, 8192);

        assertThat(decoder.decode(buffer, 0, 3, handler)).isZero();
        assertThat(decoder.decode(buffer, 0, length - 1, handler)).isZero();
        assertThat(handler.frames).isEmpty();
    }

    @Test
    void aLengthBeyondTheMaximumIsRefusedRatherThanWaitedFor() {
        ByteBuffer buffer = buffer(false);
        buffer.putInt(0, MAX_FRAME_BYTES + 1);

        assertThatThrownBy(() -> decoder.decode(buffer, 0, 8, handler))
                .isInstanceOf(WireFormatException.class).hasMessageContaining("outside");
    }

    @Test
    void aLengthShorterThanTheHeaderIsRefused() {
        ByteBuffer buffer = buffer(false);
        buffer.putInt(0, WireFormat.HEADER_BYTES - 1);

        assertThatThrownBy(() -> decoder.decode(buffer, 0, 64, handler)).isInstanceOf(WireFormatException.class);
    }

    @Test
    void anUnknownFrameTypeIsRefused() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.heartbeat(buffer, 0, 7, 8192);
        buffer.put(WireFormat.OFFSET_TYPE, (byte) 200);

        assertThatThrownBy(() -> decoder.decode(buffer, 0, length, handler))
                .isInstanceOf(WireFormatException.class).hasMessage("Unknown frame type 200");
    }

    @Test
    void aFrameShorterThanItsFixedPartIsRefused() {
        ByteBuffer buffer = buffer(false);
        FrameEncoder.heartbeat(buffer, 0, 7, 8192);
        buffer.putInt(0, WireFormat.HEADER_BYTES + 4);

        assertThatThrownBy(() -> decoder.decode(buffer, 0, 64, handler))
                .isInstanceOf(WireFormatException.class).hasMessageContaining("shorter than its fixed part");
    }

    @Test
    void aPeerThatIsNotAReplicationEndpointIsRefused() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.hello(buffer, 0, 7, "node-b", 6, 1234);
        buffer.putInt(WireFormat.HEADER_BYTES, 0x47455420);

        assertThatThrownBy(() -> decoder.decode(buffer, 0, length, handler))
                .isInstanceOf(WireFormatException.class).hasMessageContaining("Not a staffix replication peer");
    }

    @Test
    void aMessageLengthDisagreeingWithTheFrameLengthIsRefused() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.storeEvent(buffer, 0, 7, 4096, 3, StoreEventType.STORE_MESSAGE, 42, message());
        buffer.putInt(WireFormat.HEADER_BYTES + 24, MESSAGE.length + 1);

        assertThatThrownBy(() -> decoder.decode(buffer, 0, length, handler))
                .isInstanceOf(WireFormatException.class).hasMessageContaining("declares");
    }

    @Test
    void anUnknownStoreEventTypeIsRefused() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.storeEvent(buffer, 0, 7, 4096, 3, StoreEventType.RESET_SEQ_NUM, 0, null);
        buffer.put(WireFormat.HEADER_BYTES + 12, (byte) 9);

        assertThatThrownBy(() -> decoder.decode(buffer, 0, length, handler))
                .isInstanceOf(WireFormatException.class).hasMessage("Unknown store event type 9");
    }

    /**
     * Fewer bytes than frames rather than zero: a one-off of a kilobyte or two sometimes lands in the window while the
     * JIT settles, whereas a single object per frame would cost at least 16 bytes each.
     */
    @Test
    void theFrequentFramesAllocateNothingPerFrame() {
        ByteBuffer buffer = buffer(true);
        ByteBuffer message = message();
        CountingHandler counting = new CountingHandler();
        for (int i = 0; i < 20_000; i++) {
            encodeAndDecodeFrequentFrames(buffer, message, counting, i);
        }
        long warmUpFrames = counting.frames;

        long before = AllocatedBytes.ofCurrentThread();
        for (int i = 0; i < 200_000; i++) {
            encodeAndDecodeFrequentFrames(buffer, message, counting, i);
        }
        long allocated = AllocatedBytes.ofCurrentThread() - before;

        long measuredFrames = counting.frames - warmUpFrames;
        assertThat(measuredFrames).isEqualTo(600_000);
        assertThat(allocated).isLessThan(measuredFrames);
    }

    private void encodeAndDecodeFrequentFrames(ByteBuffer buffer, ByteBuffer message, CountingHandler counting, long i) {
        int event = FrameEncoder.storeEvent(buffer, 0, 7, i, 3, StoreEventType.STORE_MESSAGE, i, message);
        int ack = FrameEncoder.ack(buffer, event, 7, i, true);
        int heartbeat = FrameEncoder.heartbeat(buffer, event + ack, 7, i);
        int decoded = 0;
        while (decoded < event + ack + heartbeat) {
            decoded += decoder.decode(buffer, decoded, MAX_FRAME_BYTES - decoded, counting);
        }
    }
}
