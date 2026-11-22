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
package org.lolaf.staffix.ha.replication.wire;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lolaf.staffix.ha.replication.journal.JournalStream;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.lolaf.staffix.ha.replication.encryption.EncryptionMode;
import org.lolaf.staffix.ha.replication.encryption.ConnectionEncryptionKeys;
import org.lolaf.staffix.ha.replication.encryption.KeyExchange;
import org.lolaf.staffix.ha.replication.encryption.TestingKeyExchanges;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Isolated
class TestFrameCodec {

    private static final int MAX_FRAME_BYTES = 4096;
    private static final byte[] MESSAGE = "an entry of the journal".getBytes(StandardCharsets.US_ASCII);
    private static final byte CONTENT_TYPE = JournalStream.TEXT;

    private final FrameDecoder decoder;
    private final RecordingHandler handler;

    TestFrameCodec() {
        this.decoder = new FrameDecoder(MAX_FRAME_BYTES);
        this.handler = new RecordingHandler();
    }

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
        int length = TestingFrameEncoder.hello(buffer, 0, 7, "node-b", 6, 1234, 3, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        decodeWhole(buffer, 0, length);

        assertThat(handler.frames).containsExactly("HELLO epoch=7 version=1 node=node-b lastEpoch=6 lastOffset=1234 lane=3");
    }

    @Test
    void anEncryptingHelloAndWelcomeCarryTheModeAndTheNonce() {
        ByteBuffer buffer = buffer(false);
        byte[] nonce = TestingKeyExchanges.of(EncryptionMode.REQUIRED).newNonce();
        int hello = TestingFrameEncoder.hello(buffer, 0, 7, "node-b", 6, 1234, 3, EncryptionMode.REQUIRED, nonce);
        int welcome = TestingFrameEncoder.welcome(buffer, hello, 7, "node-a", 99, 4, EncryptionMode.PREFERRED, nonce);

        decodeWhole(buffer, 0, hello);
        assertThat(handler.lastNonce).isEqualTo(nonce);
        decodeWhole(buffer, hello, welcome);

        assertThat(handler.frames).containsExactly(
                "HELLO epoch=7 version=1 node=node-b lastEpoch=6 lastOffset=1234 lane=3 encryption=REQUIRED nonce=random",
                "WELCOME epoch=7 version=1 leader=node-a resume=99 lanes=4 encryption=PREFERRED nonce=random");
        assertThat(handler.lastNonce).isEqualTo(nonce);
    }

    @Test
    void keyConfirmation() {
        ByteBuffer buffer = buffer(false);
        byte[] confirmation = new byte[WireFormat.KEY_CONFIRMATION_BYTES];
        confirmation[0] = 42;
        int length = FrameEncoder.keyConfirmation(buffer, 0, 7, confirmation);

        decodeWhole(buffer, 0, length);

        assertThat(handler.frames).containsExactly("KEY_CONFIRMATION epoch=7 bytes=32");
        assertThat(handler.lastKeyConfirmation).isEqualTo(confirmation);
    }

    @Test
    void anUnknownEncryptionModeIsRefused() {
        ByteBuffer buffer = buffer(false);
        int length = TestingFrameEncoder.hello(buffer, 0, 7, "node-b", 6, 1234, 3, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        buffer.put(WireFormat.HEADER_BYTES + 26, (byte) 9);

        assertThatThrownBy(() -> decoder.decode(buffer, 0, length, handler))
                .isInstanceOf(WireFormatException.class).hasMessageContaining("encryption mode");
    }

    @Test
    void streamsAnnounced() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.streamsAnnounced(buffer, 0, 7, 3);

        decodeWhole(buffer, 0, length);

        assertThat(handler.frames).containsExactly("STREAMS_ANNOUNCED epoch=7 count=3");
    }

    @Test
    void welcome() {
        ByteBuffer buffer = buffer(false);
        int length = TestingFrameEncoder.welcome(buffer, 0, 7, "node-a", WireFormat.NO_RESUME, 4, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        decodeWhole(buffer, 0, length);

        assertThat(handler.frames).containsExactly("WELCOME epoch=7 version=1 leader=node-a resume=-1 lanes=4");
    }

    @Test
    void streamWithAStorageIdentity() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.stream(buffer, 0, 7, 3, StreamReplicationMode.SYNC_APPLIED, "orders", "jdbc-postgres-1");

        decodeWhole(buffer, 0, length);

        assertThat(handler.frames).containsExactly("STREAM epoch=7 key=3 SYNC_APPLIED name=orders identity=jdbc-postgres-1");
    }

    @Test
    void streamWithoutAStorageIdentityIsToldApartFromAnEmptyOne() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.stream(buffer, 0, 7, 3, StreamReplicationMode.ASYNC, "s", null);

        decodeWhole(buffer, 0, length);

        assertThat(handler.frames).containsExactly("STREAM epoch=7 key=3 ASYNC name=s identity=null");
    }

    @ParameterizedTest
    @EnumSource(StreamReplicationMode.class)
    void everyReplicationModeRoundTrips(StreamReplicationMode mode) {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.stream(buffer, 0, 7, 3, mode, "s", null);

        decodeWhole(buffer, 0, length);

        assertThat(handler.frames).containsExactly("STREAM epoch=7 key=3 " + mode + " name=s identity=null");
    }

    @Test
    void anUnknownReplicationModeIsRefused() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.stream(buffer, 0, 7, 3, StreamReplicationMode.SYNC_APPLIED, "s", null);
        buffer.put(WireFormat.HEADER_BYTES + 4, (byte) 9);

        assertThatThrownBy(() -> decoder.decode(buffer, 0, length, handler))
                .isInstanceOf(WireFormatException.class).hasMessage("Unknown replication mode code 9");
    }

    @Test
    void streamAnswer() {
        ByteBuffer buffer = buffer(false);
        int length = FrameEncoder.streamAnswer(buffer, 0, 7, 3, StreamAnswer.SHARED_STORAGE);

        decodeWhole(buffer, 0, length);

        assertThat(handler.frames).containsExactly("STREAM_ANSWER epoch=7 key=3 SHARED_STORAGE");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRecordCarriesItsPayloadAndLeavesItsBufferAlone(boolean direct) {
        ByteBuffer buffer = buffer(direct);
        ByteBuffer message = message();
        int length = TestingFrameEncoder.record(buffer, 100, 7, 4096, 3, CONTENT_TYPE, (byte) 0, 42, message);

        decodeWhole(buffer, 100, length);

        assertThat(length).isEqualTo(TestingFrameEncoder.recordBytes(MESSAGE.length));
        assertThat(message.position()).isZero();
        assertThat(buffer.position()).isZero();
        assertThat(handler.frames).containsExactly("RECORD epoch=7 offset=4096 key=3 type=1 pos=42 payload="
                + new String(MESSAGE, StandardCharsets.US_ASCII));
    }

    @Test
    void aPlaintextFlagOnAClearConnectionIsIgnored() {
        ByteBuffer buffer = buffer(false);
        int record = plaintextRecord(buffer, 0, (byte) 0);
        int snapshot = FrameEncoder.snapshotRecord(buffer, record, 7, 3, CONTENT_TYPE, WireFormat.PLAINTEXT, 42,
                message(), 0, MESSAGE.length);

        decoder.decode(buffer, 0, record, handler);
        decoder.decode(buffer, record, snapshot, handler);

        assertThat(handler.frames).extracting(frame -> frame.substring(0, frame.indexOf(" payload=")))
                .containsExactly("RECORD epoch=7 offset=4096 key=3 type=1 pos=42", "SNAPSHOT_RECORD epoch=7 key=3 type=1 pos=42");
    }

    private static int plaintextRecord(ByteBuffer buffer, int index, byte flags) {
        int body = FrameEncoder.recordBody(buffer, index + WireFormat.RECORD_BODY_OFFSET, 3, CONTENT_TYPE,
                (byte) (WireFormat.PLAINTEXT | flags), 42, message(), 0, MESSAGE.length);
        return FrameEncoder.recordHeader(buffer, index, 7, 4096, body);
    }

    @Test
    void aPlaintextFlagIsOpenedNotHandedOver() {
        KeyExchange exchange = TestingKeyExchanges.of(EncryptionMode.REQUIRED);
        ConnectionEncryptionKeys keys = exchange.deriveKeys(exchange.newNonce(), exchange.newNonce());
        FrameCipher sealer = exchange.newSealer(keys.getLeaderToFollower());
        FrameCipher opener = exchange.newOpener(keys.getLeaderToFollower());
        ByteBuffer buffer = buffer(true);
        int event = sealer.seal(buffer, 0, plaintextRecord(buffer, 0, WireFormat.RESET));
        int snapshot = sealer.seal(buffer, event, FrameEncoder.snapshotRecord(buffer, event, 7, 3, CONTENT_TYPE,
                (byte) (WireFormat.PLAINTEXT | WireFormat.MORE_FRAGMENTS), 42, message(), 0, MESSAGE.length));
        List<Byte> flags = new ArrayList<>();
        FrameHandler flagsHandler = new FrameHandler() {
            @Override
            public void onRecord(long epoch, long logOffset, int streamKey, byte contentType, byte recordFlags,
                                 long position, ByteBuffer in, int payloadIndex, int payloadLength) {
                flags.add(recordFlags);
            }

            @Override
            public void onSnapshotRecord(long epoch, int streamKey, byte contentType, byte recordFlags, long position,
                                         ByteBuffer in, int payloadIndex, int payloadLength) {
                flags.add(recordFlags);
            }
        };

        assertThat(decoder.decode(buffer, 0, event, flagsHandler, opener)).isEqualTo(event);
        assertThat(decoder.decode(buffer, event, snapshot, flagsHandler, opener)).isEqualTo(snapshot);

        assertThat(flags).containsExactly(WireFormat.RESET, WireFormat.MORE_FRAGMENTS);
    }

    @Test
    void aRecordWithoutAPayload() {
        ByteBuffer buffer = buffer(false);
        int length = TestingFrameEncoder.record(buffer, 0, 7, 4096, 3, (byte) 2, (byte) 0, 18, null);

        decodeWhole(buffer, 0, length);

        assertThat(handler.frames).containsExactly("RECORD epoch=7 offset=4096 key=3 type=2 pos=18 payload=");
    }

    @Test
    void snapshot() {
        ByteBuffer buffer = buffer(false);
        int begin = TestingFrameEncoder.snapshotBegin(buffer, 0, 7, 3, 8192, "43");
        int message = TestingFrameEncoder.snapshotRecord(buffer, begin, 7, 3, CONTENT_TYPE, (byte) 0, 42, message());
        int end = TestingFrameEncoder.snapshotEnd(buffer, begin + message, 7, 3, 1, "44");

        decodeWhole(buffer, 0, begin);
        decodeWhole(buffer, begin, message);
        decodeWhole(buffer, begin + message, end);

        assertThat(handler.frames).containsExactly(
                "SNAPSHOT_BEGIN epoch=7 key=3 snapshotOffset=8192 state=43",
                "SNAPSHOT_RECORD epoch=7 key=3 type=1 pos=42 payload=" + new String(MESSAGE, StandardCharsets.US_ASCII),
                "SNAPSHOT_END epoch=7 key=3 count=1 state=44");
    }

    @Test
    void ackReceivedAndHeartbeat() {
        ByteBuffer buffer = buffer(false);
        int ack = FrameEncoder.ack(buffer, 0, 7, 4096, true, 12);
        int received = FrameEncoder.received(buffer, ack, 7, 6144);
        int heartbeat = FrameEncoder.heartbeat(buffer, ack + received, 7, 8192);

        decodeWhole(buffer, 0, ack);
        decodeWhole(buffer, ack, received);
        decodeWhole(buffer, ack + received, heartbeat);

        assertThat(handler.frames).containsExactly("ACK epoch=7 applied=4096 inSync=true pending=12", "RECEIVED epoch=7 received=6144",
                "HEARTBEAT epoch=7 tail=8192");
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
        int length = TestingFrameEncoder.hello(buffer, 0, 7, "node-b", 6, 1234, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        buffer.putInt(WireFormat.HEADER_BYTES, 0x47455420);

        assertThatThrownBy(() -> decoder.decode(buffer, 0, length, handler))
                .isInstanceOf(WireFormatException.class).hasMessageContaining("Not a replication peer");
    }

    @Test
    void aPayloadLengthDisagreeingWithTheFrameLengthIsRefused() {
        ByteBuffer buffer = buffer(false);
        int length = TestingFrameEncoder.record(buffer, 0, 7, 4096, 3, CONTENT_TYPE, (byte) 0, 42, message());
        buffer.putInt(WireFormat.RECORD_BODY_OFFSET + 16, MESSAGE.length + 1);

        assertThatThrownBy(() -> decoder.decode(buffer, 0, length, handler))
                .isInstanceOf(WireFormatException.class).hasMessageContaining("declares");
    }

    @Test
    void helloAndWelcomeCarryTheApplication() {
        ByteBuffer buffer = buffer(false);
        int hello = FrameEncoder.hello(buffer, 0, 7, 0x41505031, "node-b", 6, 1234, 3, EncryptionMode.DISABLED,
                TestingKeyExchanges.NO_NONCE);
        int welcome = FrameEncoder.welcome(buffer, hello, 7, 0x41505031, "node-a", 99, 2, EncryptionMode.DISABLED,
                TestingKeyExchanges.NO_NONCE);

        decodeWhole(buffer, 0, hello);
        decodeWhole(buffer, hello, welcome);

        assertThat(handler.frames).containsExactly(
                "HELLO epoch=7 version=1 application=41505031 node=node-b lastEpoch=6 lastOffset=1234 lane=3",
                "WELCOME epoch=7 version=1 application=41505031 leader=node-a resume=99 lanes=2");
    }

    @Test
    void aStreamNameAStateAndAContentTypeAreTheApplicationsAndHandedOverUntouched() {
        ByteBuffer buffer = buffer(false);
        ByteBuffer state = ByteBuffer.wrap("abc".getBytes(StandardCharsets.US_ASCII));
        int stream = FrameEncoder.stream(buffer, 0, 7, 3, StreamReplicationMode.SYNC_RECEIVED, "any name at all", null);
        int begin = FrameEncoder.snapshotBegin(buffer, stream, 7, 3, 64, state, 0, 3);
        int end = FrameEncoder.snapshotEnd(buffer, stream + begin, 7, 3, 0, state, 0, 0);
        int body = FrameEncoder.recordBody(buffer, stream + begin + end + WireFormat.RECORD_BODY_OFFSET, 3, (byte) 0xC9,
                (byte) 0, 5, null, 0, 0);
        int record = FrameEncoder.recordHeader(buffer, stream + begin + end, 7, 128, body);
        List<String> seen = new ArrayList<>();
        FrameHandler application = new FrameHandler() {
            @Override
            public void onStream(long epoch, int streamKey, StreamReplicationMode replicationMode, String streamName,
                                 String storageIdentity) {
                seen.add(replicationMode + " " + streamName);
            }

            @Override
            public void onSnapshotBegin(long epoch, int streamKey, long snapshotOffset, ByteBuffer in, int stateIndex,
                                        int stateLength) {
                seen.add("state " + new String(FrameDecoder.bytes(in, stateIndex, stateLength), StandardCharsets.US_ASCII));
            }

            @Override
            public void onSnapshotEnd(long epoch, int streamKey, int recordCount, ByteBuffer in, int stateIndex,
                                      int stateLength) {
                seen.add("state of " + stateLength);
            }

            @Override
            public void onRecord(long epoch, long logOffset, int streamKey, byte contentType, byte flags, long position,
                                 ByteBuffer in, int payloadIndex, int payloadLength) {
                seen.add("content type " + (contentType & 0xFF) + " position " + position);
            }
        };

        int index = 0;
        for (int length : new int[]{stream, begin, end, record}) {
            assertThat(decoder.decode(buffer, index, length, application)).isEqualTo(length);
            index += length;
        }

        assertThat(seen).containsExactly("SYNC_RECEIVED any name at all", "state abc", "state of 0",
                "content type 201 position 5");
    }

    @Test
    void aStateLongerThanItsLengthCanSayIsRefused() {
        ByteBuffer state = ByteBuffer.allocate(WireFormat.MAX_STATE_BYTES + 1);

        assertThatThrownBy(() -> FrameEncoder.snapshotBegin(ByteBuffer.allocate(1 << 17), 0, 7, 3, 64, state, 0,
                state.capacity())).isInstanceOf(IllegalArgumentException.class);
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
        int event = TestingFrameEncoder.record(buffer, 0, 7, i, 3, CONTENT_TYPE, (byte) 0, i, message);
        int ack = FrameEncoder.ack(buffer, event, 7, i, true, 0);
        int heartbeat = FrameEncoder.heartbeat(buffer, event + ack, 7, i);
        int decoded = 0;
        while (decoded < event + ack + heartbeat) {
            decoded += decoder.decode(buffer, decoded, MAX_FRAME_BYTES - decoded, counting);
        }
    }
}
