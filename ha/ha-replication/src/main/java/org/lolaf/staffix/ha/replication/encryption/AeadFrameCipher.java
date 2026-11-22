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

import org.lolaf.staffix.ha.replication.encryption.ReplicationEncryptionSettings;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.spec.AlgorithmParameterSpec;

/**
 * An AEAD sealing each frame under its own nonce, the frame's number in the direction: the fixed part is its
 * additional data, the variable part its plaintext. The JCE needs an {@code init} per frame, which allocates.
 *
 * <p>A frame goes whole through two arrays, grown to the longest frame: the JCE copies its input when it is also its
 * output.
 */
final class AeadFrameCipher extends SealingFrameCipher {

    private static final int NONCE_BYTES = 12;
    private static final int INITIAL_BYTES = 8 * 1024;

    private final Cipher cipher;
    private final int mode;
    private final SecretKeySpec key;
    private final boolean aes;
    private final byte[] iv;
    private final byte[] nonce;
    private byte[] in;
    private byte[] out;

    /**
     * @param mode {@link Cipher#ENCRYPT_MODE} to seal, {@link Cipher#DECRYPT_MODE} to open
     * @param key  AES for GCM, ChaCha20 for ChaCha20-Poly1305
     * @param iv   the direction's, whose first 12 bytes each frame's nonce starts from
     */
    AeadFrameCipher(Cipher cipher, int mode, SecretKeySpec key, byte[] iv) {
        this.nonce = new byte[NONCE_BYTES];
        this.in = new byte[INITIAL_BYTES];
        this.out = new byte[INITIAL_BYTES];
        this.cipher = cipher;
        this.mode = mode;
        this.key = key;
        this.aes = ReplicationEncryptionSettings.AES.equals(key.getAlgorithm());
        this.iv = iv.clone();
    }

    @Override
    void seal(ByteBuffer frames, int frameIndex, int clearBytes, int contentLength, long frameNumber) {
        ensureCapacity(contentLength + TAG_BYTES);
        FrameBytes.get(frames, frameIndex, in, 0, contentLength);
        try {
            start(frameNumber);
            cipher.updateAAD(in, 0, clearBytes);
            int sealed = cipher.doFinal(in, clearBytes, contentLength - clearBytes, out, 0);
            FrameBytes.put(frames, frameIndex + clearBytes, out, 0, sealed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(cipher.getAlgorithm() + " failed to seal a frame", e);
        }
    }

    @Override
    boolean open(ByteBuffer frames, int frameIndex, int clearBytes, int contentLength, long frameNumber) {
        ensureCapacity(contentLength + TAG_BYTES);
        FrameBytes.get(frames, frameIndex, in, 0, contentLength + TAG_BYTES);
        try {
            start(frameNumber);
            cipher.updateAAD(in, 0, clearBytes);
            int opened = cipher.doFinal(in, clearBytes, contentLength - clearBytes + TAG_BYTES, out, 0);
            FrameBytes.put(frames, frameIndex + clearBytes, out, 0, opened);
            return true;
        } catch (AEADBadTagException e) {
            return false;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(cipher.getAlgorithm() + " failed to open a frame", e);
        }
    }

    private void start(long frameNumber) throws GeneralSecurityException {
        System.arraycopy(iv, 0, nonce, 0, NONCE_BYTES);
        FrameBytes.xorLong(nonce, NONCE_BYTES - Long.BYTES, frameNumber);
        AlgorithmParameterSpec parameters = aes
                ? new GCMParameterSpec(TAG_BYTES * Byte.SIZE, nonce) : new IvParameterSpec(nonce);
        cipher.init(mode, key, parameters);
    }

    private void ensureCapacity(int bytes) {
        if (in.length < bytes) {
            int capacity = Integer.highestOneBit(bytes - 1) << 1;
            in = new byte[capacity];
            out = new byte[capacity];
        }
    }
}
