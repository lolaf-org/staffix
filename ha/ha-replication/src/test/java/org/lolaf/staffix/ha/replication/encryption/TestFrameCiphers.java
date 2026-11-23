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
package org.lolaf.staffix.ha.replication.encryption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lolaf.staffix.ha.replication.journal.JournalStream;
import org.lolaf.staffix.ha.replication.spi.RecordFlag;
import org.lolaf.staffix.ha.replication.spi.StreamReplicationMode;
import org.lolaf.staffix.ha.replication.wire.TestingFrameEncoder;
import org.lolaf.staffix.ha.replication.encryption.EncryptionMode;
import org.lolaf.staffix.ha.replication.encryption.ReplicationEncryptionSettings;
import org.lolaf.staffix.ha.replication.wire.FrameCipher;
import org.lolaf.staffix.ha.replication.wire.FrameDecoder;
import org.lolaf.staffix.ha.replication.wire.FrameEncoder;
import org.lolaf.staffix.ha.replication.wire.FrameType;
import org.lolaf.staffix.ha.replication.wire.WireFormat;
import org.lolaf.staffix.ha.replication.wire.WireFormatException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sealing and opening frames with each supported cipher: what is encrypted, what stays readable, and that no byte of a
 * authenticated frame nor its place in the stream can change unnoticed.
 */
class TestFrameCiphers {

    private static final String MESSAGE = "entry=1|account=SENDER|text=worth sealing|";
    private static final int EVENT_FIXED_BYTES = WireFormat.HEADER_BYTES + FrameType.RECORD.getFixedPayloadBytes();

    private FrameCipher sealer;
    private FrameCipher opener;

    private void ciphers(String cipher, int keyBits, String mac) {
        KeyExchange exchange = new KeyExchange(ReplicationEncryptionSettings.builder().mode(EncryptionMode.REQUIRED)
                .secret(TestingKeyExchanges.SECRET.getBytes(StandardCharsets.UTF_8)).cipher(cipher).keyBits(keyBits)
                .mac(mac).build());
        ConnectionEncryptionKeys keys = exchange.deriveKeys(exchange.newNonce(), exchange.newNonce());
        sealer = exchange.newSealer(keys.getLeaderToFollower());
        opener = exchange.newOpener(keys.getLeaderToFollower());
    }

    private void streamCiphers(String cipher, ConnectionEncryptionKeys.DirectionKeys keys, long segmentBytes)
            throws Exception {
        SecretKeySpec key = new SecretKeySpec(keys.getCipherKey(), cipher.startsWith("AES")
                ? ReplicationEncryptionSettings.AES : ReplicationEncryptionSettings.CHACHA20);
        sealer = new StreamFrameCipher(Cipher.getInstance(cipher), Cipher.ENCRYPT_MODE, key, keys.getIv(),
                FrameMac.of("HmacSHA256", null, keys.getMacKey()), segmentBytes);
        opener = new StreamFrameCipher(Cipher.getInstance(cipher), Cipher.DECRYPT_MODE, key, keys.getIv(),
                FrameMac.of("HmacSHA256", null, keys.getMacKey()), segmentBytes);
    }

    /**
     * The encrypted bytes of the same message sealed twice in a row, as the second frame of the direction.
     */
    private byte[][] sealedTwice() {
        ByteBuffer frames = buffer(false);
        byte[][] encrypted = new byte[2][];
        for (int i = 0; i < 2; i++) {
            sealer.seal(frames, 0, entry(frames, 0, MESSAGE, false));
            encrypted[i] = FrameDecoder.bytes(frames, EVENT_FIXED_BYTES, MESSAGE.length());
        }
        return encrypted;
    }

    private static ByteBuffer buffer(boolean direct) {
        return direct ? ByteBuffer.allocateDirect(1 << 16) : ByteBuffer.allocate(1 << 16);
    }

    private static int entry(ByteBuffer out, int index, String message, boolean unencrypted) {
        ByteBuffer bytes = ByteBuffer.wrap(message.getBytes(StandardCharsets.US_ASCII));
        int body = FrameEncoder.recordBody(out, index + WireFormat.RECORD_BODY_OFFSET, 3,
                JournalStream.TEXT, unencrypted ? RecordFlag.PLAINTEXT.getBits() : 0, 42, bytes, 0,
                bytes.remaining());
        return FrameEncoder.recordHeader(out, index, 7, 1_000, body);
    }

    private static String text(ByteBuffer buffer, int index, int length) {
        return new String(FrameDecoder.bytes(buffer, index, length), StandardCharsets.US_ASCII);
    }

    @ParameterizedTest(name = "{0} {1} {2}, direct {3}")
    @CsvSource({
            "AES/GCM/NoPadding, 256, HmacSHA256, true",
            "AES/GCM/NoPadding, 128, HmacSHA256, false",
            "ChaCha20-Poly1305, 256, HmacSHA256, true",
            "AES/CTR/NoPadding, 256, HmacSHA256, true",
            "AES/CTR/NoPadding, 128, HmacSHA512, false",
            "ChaCha20,          256, HmacSHA256, true",
            "ChaCha20,          256, HmacSHA3-256, false",
            "AES/CTR/NoPadding, 256, HmacSHA1, true"})
    void aRecordsPayloadIsEncryptedItsFixedFieldsReadableAndItOpensAsSent(String cipher, int keyBits, String mac,
                                                                              boolean direct) {
        ciphers(cipher, keyBits, mac);
        ByteBuffer frames = buffer(direct);
        int length = entry(frames, 0, MESSAGE, false);
        byte[] clear = FrameDecoder.bytes(frames, 0, length);

        int sealed = sealer.seal(frames, 0, length);

        assertThat(sealed).isEqualTo(length + WireFormat.TAG_BYTES);
        assertThat(frames.getInt(WireFormat.OFFSET_LENGTH)).isEqualTo(sealed);
        assertThat(FrameDecoder.bytes(frames, 4, EVENT_FIXED_BYTES - 4)).as("the fixed fields").isEqualTo(FrameDecoder.bytes(ByteBuffer.wrap(clear), 4, EVENT_FIXED_BYTES - 4));
        assertThat(text(frames, EVENT_FIXED_BYTES, MESSAGE.length())).doesNotContain("entry=").doesNotContain("SENDER");

        assertThat(opener.open(frames, 0, sealed)).isEqualTo(length);
        assertThat(FrameDecoder.bytes(frames, 4, length - 4)).isEqualTo(FrameDecoder.bytes(ByteBuffer.wrap(clear), 4, length - 4));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/GCM/NoPadding", "ChaCha20-Poly1305", "AES/CTR/NoPadding", "ChaCha20"})
    void anUnencryptedMessageStaysReadableAndAuthenticated(String cipher) {
        ciphers(cipher, 256, "HmacSHA256");
        ByteBuffer frames = buffer(true);
        int length = entry(frames, 0, MESSAGE, true);
        int sealed = sealer.seal(frames, 0, length);
        assertThat(text(frames, EVENT_FIXED_BYTES, MESSAGE.length())).isEqualTo(MESSAGE);

        frames.put(EVENT_FIXED_BYTES + 12, (byte) 'F');

        assertThatThrownBy(() -> opener.open(frames, 0, sealed)).isInstanceOf(WireFormatException.class)
                .hasMessage("RECORD fails its authentication tag");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/GCM/NoPadding", "ChaCha20-Poly1305", "AES/CTR/NoPadding", "ChaCha20"})
    void flippingAnyBitOfAnAuthenticatedFrameButItsTypeFailsItsTag(String cipher) {
        ByteBuffer frames = buffer(false);
        int length = entry(frames, 0, MESSAGE, false);
        byte[] clear = FrameDecoder.bytes(frames, 0, length);
        for (int index = 0; index < length + WireFormat.TAG_BYTES; index++) {
            if (index == WireFormat.OFFSET_TYPE) {
                // another type is another frame, refused by its own decoding or the next authenticated frame's tag
                continue;
            }
            ciphers(cipher, 256, "HmacSHA256");
            frames.clear().put(clear).clear();
            int sealed = sealer.seal(frames, 0, length);
            frames.put(index, (byte) (frames.get(index) ^ 0x04));

            assertThatThrownBy(() -> opener.open(frames, 0, sealed)).as("byte %s flipped", index)
                    .isInstanceOf(WireFormatException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/GCM/NoPadding", "ChaCha20-Poly1305", "AES/CTR/NoPadding", "ChaCha20"})
    void aFrameOutOfOrderFailsItsTag(String cipher) {
        ciphers(cipher, 256, "HmacSHA256");
        ByteBuffer first = buffer(true);
        ByteBuffer second = buffer(true);
        int length = entry(first, 0, MESSAGE, false);
        entry(second, 0, MESSAGE, false);
        int sealed = sealer.seal(first, 0, length);
        sealer.seal(second, 0, length);

        assertThatThrownBy(() -> opener.open(second, 0, sealed)).isInstanceOf(WireFormatException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/GCM/NoPadding", "AES/CTR/NoPadding"})
    void aReplayedFrameFailsItsTag(String cipher) {
        ciphers(cipher, 256, "HmacSHA256");
        ByteBuffer frames = buffer(true);
        int length = entry(frames, 0, MESSAGE, false);
        int sealed = sealer.seal(frames, 0, length);
        byte[] onTheWire = FrameDecoder.bytes(frames, 0, sealed);
        opener.open(frames, 0, sealed);

        ByteBuffer replayed = ByteBuffer.wrap(onTheWire);

        assertThatThrownBy(() -> opener.open(replayed, 0, sealed)).isInstanceOf(WireFormatException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/GCM/NoPadding", "ChaCha20-Poly1305", "AES/CTR/NoPadding", "ChaCha20"})
    void framesOfEveryTypeLongerThanAChunkRoundTripAControlFrameAuthenticatedButReadable(String cipher) {
        ciphers(cipher, 256, "HmacSHA256");
        ByteBuffer frames = buffer(true);
        String longMessage = MESSAGE + "x".repeat(20_000);
        int[] lengths = new int[5];
        int index = 0;
        lengths[0] = entry(frames, index, longMessage, false);
        index += sealer.seal(frames, index, lengths[0]);
        int heartbeat = index;
        lengths[1] = FrameEncoder.heartbeat(frames, index, 7, 99);
        assertThat(sealer.seal(frames, index, lengths[1])).isEqualTo(lengths[1] + WireFormat.TAG_BYTES);
        assertThat(frames.getLong(index + WireFormat.HEADER_BYTES)).as("its log tail offset, readable").isEqualTo(99);
        index += lengths[1] + WireFormat.TAG_BYTES;
        lengths[2] = FrameEncoder.stream(frames, index, 7, 3, StreamReplicationMode.SYNC_APPLIED, "orders of SENDER",
                "storage");
        int session = index;
        index += sealer.seal(frames, index, lengths[2]);
        assertThat(text(frames, session, lengths[2])).doesNotContain("SENDER");
        ByteBuffer snapshotBytes = ByteBuffer.wrap(MESSAGE.getBytes(StandardCharsets.US_ASCII));
        lengths[3] = TestingFrameEncoder.snapshotRecord(frames, index, 7, 3, JournalStream.TEXT, (byte) 0, 42,
                snapshotBytes);
        index += sealer.seal(frames, index, lengths[3]);
        int end = index;

        index = 0;
        assertThat(opener.open(frames, index, lengths[0] + WireFormat.TAG_BYTES)).isEqualTo(lengths[0]);
        assertThat(text(frames, EVENT_FIXED_BYTES, longMessage.length())).isEqualTo(longMessage);
        index += lengths[0] + WireFormat.TAG_BYTES;
        assertThat(index).isEqualTo(heartbeat);
        assertThat(opener.open(frames, index, lengths[1] + WireFormat.TAG_BYTES)).isEqualTo(lengths[1]);
        index += lengths[1] + WireFormat.TAG_BYTES;
        assertThat(opener.open(frames, index, lengths[2] + WireFormat.TAG_BYTES)).isEqualTo(lengths[2]);
        assertThat(text(frames, session, lengths[2])).contains("orders of SENDER");
        index += lengths[2] + WireFormat.TAG_BYTES;
        assertThat(opener.open(frames, index, lengths[3] + WireFormat.TAG_BYTES)).isEqualTo(lengths[3]);
        assertThat(text(frames, index, lengths[3])).contains(MESSAGE);
        assertThat(index + lengths[3] + WireFormat.TAG_BYTES).isEqualTo(end);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/CTR/NoPadding", "ChaCha20"})
    void theKeystreamMovesToTheNextSegmentsIvMidFrameOnBothSides(String cipher) throws Exception {
        KeyExchange exchange = TestingKeyExchanges.of(EncryptionMode.REQUIRED, TestingKeyExchanges.SECRET, cipher);
        ConnectionEncryptionKeys.DirectionKeys keys = exchange.deriveKeys(exchange.newNonce(), exchange.newNonce())
                .getLeaderToFollower();
        streamCiphers(cipher, keys, 50);
        ByteBuffer frames = buffer(true);
        for (int frame = 0; frame < 5; frame++) {
            int length = entry(frames, 0, MESSAGE + frame, false);
            int sealed = sealer.seal(frames, 0, length);
            assertThat(text(frames, EVENT_FIXED_BYTES, MESSAGE.length())).doesNotContain("entry=");

            opener.open(frames, 0, sealed);

            assertThat(text(frames, EVENT_FIXED_BYTES, MESSAGE.length() + 1)).isEqualTo(MESSAGE + frame);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/GCM/NoPadding", "ChaCha20-Poly1305", "AES/CTR/NoPadding", "ChaCha20"})
    void theSameMessageSealedTwiceEncryptsDifferently(String cipher) {
        ciphers(cipher, 256, "HmacSHA256");

        byte[][] encrypted = sealedTwice();

        assertThat(encrypted[1]).as("a nonce or keystream used twice").isNotEqualTo(encrypted[0]);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/CTR/NoPadding", "ChaCha20"})
    void eachSegmentRunsItsOwnKeystream(String cipher) throws Exception {
        KeyExchange exchange = TestingKeyExchanges.of(EncryptionMode.REQUIRED, TestingKeyExchanges.SECRET, cipher);
        streamCiphers(cipher, exchange.deriveKeys(exchange.newNonce(), exchange.newNonce()).getLeaderToFollower(),
                MESSAGE.length());

        byte[][] encrypted = sealedTwice();

        assertThat(encrypted[1]).as("the second segment's keystream is the first's").isNotEqualTo(encrypted[0]);
    }

    @Test
    void anotherSecretsOpenerFailsTheTag() {
        ciphers("AES/GCM/NoPadding", 256, "HmacSHA256");
        KeyExchange other = TestingKeyExchanges.of(EncryptionMode.REQUIRED, "another-secret-entirely", "AES/GCM/NoPadding");
        FrameCipher otherOpener = other.newOpener(other.deriveKeys(other.newNonce(), other.newNonce()).getLeaderToFollower());
        ByteBuffer frames = buffer(true);
        int sealed = sealer.seal(frames, 0, entry(frames, 0, MESSAGE, false));

        assertThatThrownBy(() -> otherOpener.open(frames, 0, sealed)).isInstanceOf(WireFormatException.class);
    }

    @Test
    void anAuthenticatedFrameTooShortForItsTagIsRefused() {
        ciphers("AES/CTR/NoPadding", 256, "HmacSHA256");
        ByteBuffer frames = buffer(true);
        int length = entry(frames, 0, "", false);

        assertThatThrownBy(() -> opener.open(frames, 0, length)).isInstanceOf(WireFormatException.class)
                .hasMessage("RECORD of " + length + " bytes is shorter than its fixed part and tag");
    }

    @ParameterizedTest(name = "{0}, key of {1} bytes")
    @CsvSource({"HmacSHA256, 32", "HmacSHA256, 100", "HmacSHA512, 32", "HmacSHA512, 200", "HmacSHA3-256, 32"})
    void theHmacComputedOnDigestsIsTheJcesTruncated(String algorithm, int keyBytes) throws Exception {
        byte[] key = new byte[keyBytes];
        for (int i = 0; i < keyBytes; i++) {
            key[i] = (byte) (i * 7);
        }
        FrameMac frameMac = FrameMac.of(algorithm, null, key);
        assertThat(frameMac).isInstanceOf(FrameMac.DigestHmac.class);
        byte[] data = MESSAGE.getBytes(StandardCharsets.US_ASCII);
        Mac jce = Mac.getInstance(algorithm);
        jce.init(new SecretKeySpec(key, algorithm));
        for (long frame = 0; frame < 3; frame++) {
            byte[] tag = new byte[WireFormat.TAG_BYTES];
            frameMac.start(frame);
            frameMac.update(data, 0, data.length);
            frameMac.finish(tag);

            jce.update(ByteBuffer.allocate(Long.BYTES).putLong(frame).array());
            jce.update(data);
            byte[] expected = new byte[WireFormat.TAG_BYTES];
            System.arraycopy(jce.doFinal(), 0, expected, 0, expected.length);
            assertThat(MessageDigest.isEqual(tag, expected)).as("frame %s", frame).isTrue();
        }
    }
}
