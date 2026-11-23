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

import org.lolaf.staffix.ha.replication.ReplicationEncryptionSettings;

import javax.crypto.Cipher;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.spec.AlgorithmParameterSpec;

/**
 * Encrypt-then-MAC: one keystream run over the direction's encrypted bytes, initialised once rather than per frame,
 * and a MAC per frame. Allocates nothing per frame with the JVM's ciphers and an HMAC of {@link FrameMac}.
 *
 * <p>Frames go through two chunk arrays: the JCE copies its input when it is also its output.
 */
final class StreamFrameCipher extends SealingFrameCipher {

    /**
     * The keystream bytes taken under one IV, far below what wraps AES-CTR's counter or exhausts ChaCha20's.
     */
    static final long SEGMENT_BYTES = 1L << 32;
    private static final int CHUNK_BYTES = 8 * 1024;

    private final Cipher cipher;
    private final int mode;
    private final SecretKeySpec key;
    private final byte[] iv;
    private final boolean aes;
    private final FrameMac mac;
    private final long segmentBytes;
    private final byte[] in;
    private final byte[] out;
    /**
     * Where a chunk's encrypted bytes are once {@link #crypt} ran, the MAC going over the bytes as on the wire:
     * {@link #out} when sealing, {@link #in} when opening.
     */
    private final byte[] ciphertext;
    private final byte[] tag;
    private final byte[] receivedTag;
    private long segment;
    private long segmentLeft;

    /**
     * @param mode {@link Cipher#ENCRYPT_MODE} to seal, {@link Cipher#DECRYPT_MODE} to open
     * @param key  AES for AES-CTR, ChaCha20 for ChaCha20
     * @param iv   the direction's, which each segment's IV starts from
     */
    StreamFrameCipher(Cipher cipher, int mode, SecretKeySpec key, byte[] iv, FrameMac mac, long segmentBytes)
            throws GeneralSecurityException {
        this.in = new byte[CHUNK_BYTES];
        this.out = new byte[CHUNK_BYTES];
        this.ciphertext = mode == Cipher.ENCRYPT_MODE ? out : in;
        this.tag = new byte[TAG_BYTES];
        this.receivedTag = new byte[TAG_BYTES];
        this.cipher = cipher;
        this.mode = mode;
        this.key = key;
        this.aes = ReplicationEncryptionSettings.AES.equals(key.getAlgorithm());
        this.iv = iv.clone();
        this.mac = mac;
        this.segmentBytes = segmentBytes;
        startSegment();
    }

    @Override
    void seal(ByteBuffer frames, int frameIndex, int clearBytes, int contentLength, long frameNumber) {
        cryptAndMac(frames, frameIndex, clearBytes, contentLength, frameNumber);
        FrameBytes.put(frames, frameIndex + contentLength, tag, 0, TAG_BYTES);
    }

    /**
     * Decrypts as it authenticates, in one pass: a frame whose tag fails is never dispatched, and closes the
     * connection.
     */
    @Override
    boolean open(ByteBuffer frames, int frameIndex, int clearBytes, int contentLength, long frameNumber) {
        cryptAndMac(frames, frameIndex, clearBytes, contentLength, frameNumber);
        FrameBytes.get(frames, frameIndex + contentLength, receivedTag, 0, TAG_BYTES);
        return MessageDigest.isEqual(tag, receivedTag);
    }

    /**
     * Runs the keystream over the frame's bytes past {@code clearBytes}, in place, chunk by chunk, and MACs the
     * frame as it is on the wire into {@link #tag}.
     */
    private void cryptAndMac(ByteBuffer frames, int frameIndex, int clearBytes, int contentLength, long frameNumber) {
        mac.start(frameNumber);
        int encryptFrom = frameIndex + clearBytes;
        int end = frameIndex + contentLength;
        for (int index = frameIndex; index < end; ) {
            int chunk = Math.min(CHUNK_BYTES, end - index);
            FrameBytes.get(frames, index, in, 0, chunk);
            int clear = Math.max(0, Math.min(chunk, encryptFrom - index));
            mac.update(in, 0, clear);
            if (clear < chunk) {
                crypt(clear, chunk - clear);
                mac.update(ciphertext, clear, chunk - clear);
                FrameBytes.put(frames, index + clear, out, clear, chunk - clear);
            }
            index += chunk;
        }
        mac.finish(tag);
    }

    /**
     * Runs the keystream over {@code in} into {@code out}, moving to the next segment's IV where one ends.
     */
    private void crypt(int offset, int length) {
        try {
            while (length > 0) {
                if (segmentLeft == 0) {
                    startSegment();
                }
                int taken = (int) Math.min(length, segmentLeft);
                int done = cipher.update(in, offset, taken, out, offset);
                if (done != taken) {
                    throw new IllegalStateException(cipher.getAlgorithm() + " from " + cipher.getProvider()
                            + " held back " + (taken - done) + " bytes: not a stream cipher");
                }
                segmentLeft -= taken;
                offset += taken;
                length -= taken;
            }
        } catch (ShortBufferException e) {
            throw new IllegalStateException("A chunk array shorter than its own chunk", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(cipher.getAlgorithm() + " refused its next segment", e);
        }
    }

    private void startSegment() throws GeneralSecurityException {
        cipher.init(mode, key, segmentParameters(segment++));
        segmentLeft = segmentBytes;
    }

    /**
     * The segment's number XORed into the IV, its block counter from 0: the key is the direction's own, so the IV
     * only has to differ between segments.
     */
    private AlgorithmParameterSpec segmentParameters(long number) {
        if (aes) {
            byte[] counterBlock = new byte[16];
            System.arraycopy(iv, 0, counterBlock, 0, Long.BYTES);
            FrameBytes.xorLong(counterBlock, 0, number);
            return new IvParameterSpec(counterBlock);
        }
        byte[] nonce = new byte[12];
        System.arraycopy(iv, 0, nonce, 0, nonce.length);
        FrameBytes.xorLong(nonce, 4, number);
        return new ChaCha20ParameterSpec(nonce, 0);
    }
}
