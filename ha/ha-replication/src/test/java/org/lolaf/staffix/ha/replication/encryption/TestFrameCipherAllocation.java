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

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lolaf.staffix.ha.replication.journal.JournalStream;
import org.lolaf.staffix.ha.replication.encryption.EncryptionMode;
import org.lolaf.staffix.ha.replication.encryption.ReplicationEncryptionSettings;
import org.lolaf.staffix.ha.replication.wire.AllocatedBytes;
import org.lolaf.staffix.ha.replication.wire.FrameCipher;
import org.lolaf.staffix.ha.replication.wire.FrameDecoder;
import org.lolaf.staffix.ha.replication.wire.FrameEncoder;
import org.lolaf.staffix.ha.replication.wire.FrameHandler;
import org.lolaf.staffix.ha.replication.wire.FrameType;
import org.lolaf.staffix.ha.replication.wire.WireFormat;

import javax.crypto.Cipher;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What sealing a record on the leader and opening it on the follower allocate per frame in steady state, on the
 * direct buffers both use: nothing for a stream cipher and its MAC, no more than the JCE itself for an AEAD.
 */
@Slf4j
class TestFrameCipherAllocation {

    private static final int MEASURED = JceAeadAllocation.MEASURED;
    private static final int CLEAR_BYTES = WireFormat.HEADER_BYTES + FrameType.RECORD.getFixedPayloadBytes();

    private final ByteBuffer frames;
    private final OpenedCount opened;
    private final FrameDecoder decoder;
    private ByteBuffer message;
    private boolean unencrypted;

    TestFrameCipherAllocation() {
        this.frames = ByteBuffer.allocateDirect(1 << 16);
        this.opened = new OpenedCount();
        this.decoder = new FrameDecoder(1 << 16);
    }

    private static KeyExchange exchange(String cipher) {
        return new KeyExchange(ReplicationEncryptionSettings.builder().mode(EncryptionMode.REQUIRED)
                .secret(TestingKeyExchanges.SECRET.getBytes(StandardCharsets.UTF_8)).cipher(cipher).build());
    }

    @ParameterizedTest(name = "{0}, {1} byte message, unencrypted {2}")
    @CsvSource({
            "AES/CTR/NoPadding, 200,   false",
            "AES/CTR/NoPadding, 20000, false",
            "AES/CTR/NoPadding, 200,   true",
            "ChaCha20,          200,   false",
            "ChaCha20,          20000, false",
            "ChaCha20,          200,   true"})
    void aStreamCipherAndItsMacAllocateNothing(String cipher, int messageBytes, boolean unencrypted) {
        Allocation allocation = measure(cipher, messageBytes, unencrypted);

        assertThat(allocation.getSeal()).as("bytes per frame sealed").isLessThan(1);
        assertThat(allocation.getOpen()).as("bytes per frame opened").isLessThan(1);
    }

    @ParameterizedTest(name = "{0}, {1} byte message, unencrypted {2}")
    @CsvSource({
            "AES/GCM/NoPadding, 200,   false",
            "AES/GCM/NoPadding, 20000, false",
            "AES/GCM/NoPadding, 200,   true",
            "ChaCha20-Poly1305, 200,   false",
            "ChaCha20-Poly1305, 20000, false",
            "ChaCha20-Poly1305, 200,   true"})
    void anAeadAllocatesNoMoreThanTheJceItself(String cipher, int messageBytes, boolean unencrypted) {
        Allocation allocation = measure(cipher, messageBytes, unencrypted);
        int aadBytes = unencrypted ? CLEAR_BYTES + messageBytes : CLEAR_BYTES;
        int encryptedBytes = unencrypted ? 0 : messageBytes;
        double jceSeal = JceAeadAllocation.bytesPerFrame(cipher, Cipher.ENCRYPT_MODE, aadBytes, encryptedBytes);
        double jceOpen = JceAeadAllocation.bytesPerFrame(cipher, Cipher.DECRYPT_MODE, aadBytes, encryptedBytes);
        log.info("{}, {} byte message, unencrypted {}: the JCE alone {} bytes per seal, {} per open", cipher, messageBytes,
                unencrypted, jceSeal, jceOpen);

        assertThat(allocation.getSeal()).as("bytes per frame sealed, the JCE's %s", jceSeal)
                .isLessThan(jceSeal + JceAeadAllocation.JIT_SLACK_BYTES);
        // the open is what sealing and opening allocate over sealing alone, which the JIT may compile apart
        assertThat(allocation.getSeal() + allocation.getOpen()).as("bytes per frame sealed and opened, the JCE's %s",
                jceSeal + jceOpen).isLessThan(jceSeal + jceOpen + 2 * JceAeadAllocation.JIT_SLACK_BYTES);
    }

    private Allocation measure(String cipher, int messageBytes, boolean unencrypted) {
        this.message = ByteBuffer.wrap(new byte[messageBytes]);
        this.unencrypted = unencrypted;
        KeyExchange exchange = exchange(cipher);
        ConnectionEncryptionKeys keys = exchange.deriveKeys(exchange.newNonce(), exchange.newNonce());
        FrameCipher sealer = exchange.newSealer(keys.getLeaderToFollower());
        FrameCipher opener = exchange.newOpener(keys.getLeaderToFollower());
        FrameCipher sealerOnly = exchange.newSealer(keys.getFollowerToLeader());

        int warmUp = JceAeadAllocation.warmUpFrames(messageBytes);
        seal(sealerOnly, warmUp);
        long before = AllocatedBytes.ofCurrentThread();
        seal(sealerOnly, MEASURED);
        long sealed = AllocatedBytes.ofCurrentThread() - before;

        sealAndOpen(sealer, opener, warmUp);
        before = AllocatedBytes.ofCurrentThread();
        sealAndOpen(sealer, opener, MEASURED);
        long sealedAndOpened = AllocatedBytes.ofCurrentThread() - before;

        assertThat(opened.frames).isEqualTo(warmUp + MEASURED);
        Allocation allocation = new Allocation((double) sealed / MEASURED, (double) (sealedAndOpened - sealed) / MEASURED);
        log.info("{}, {} byte message, unencrypted {}: {} bytes per seal, {} per open", cipher, messageBytes, unencrypted,
                allocation.getSeal(), allocation.getOpen());
        return allocation;
    }

    private void seal(FrameCipher sealer, int count) {
        for (int i = 0; i < count; i++) {
            sealer.seal(frames, 0, entry(i));
        }
    }

    private void sealAndOpen(FrameCipher sealer, FrameCipher opener, int count) {
        for (int i = 0; i < count; i++) {
            int sealed = sealer.seal(frames, 0, entry(i));
            decoder.decode(frames, 0, sealed, opened, opener);
        }
    }

    private int entry(long position) {
        int body = FrameEncoder.recordBody(frames, WireFormat.RECORD_BODY_OFFSET, 3,
                JournalStream.TEXT, unencrypted ? WireFormat.PLAINTEXT : 0, position, message, 0,
                message.remaining());
        return FrameEncoder.recordHeader(frames, 0, 7, position, body);
    }

    @Value
    private static class Allocation {

        double seal;
        double open;
    }

    private static final class OpenedCount implements FrameHandler {

        long frames;

        OpenedCount() {
            this.frames = 0;
        }

        @Override
        public void onRecord(long epoch, long logOffset, int streamKey, byte contentType, byte flags, long position,
                             ByteBuffer buffer, int payloadIndex, int payloadLength) {
            frames++;
        }
    }
}
