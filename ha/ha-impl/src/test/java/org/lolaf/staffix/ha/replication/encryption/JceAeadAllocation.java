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

import lombok.experimental.UtilityClass;
import org.lolaf.staffix.ha.replication.wire.AllocatedBytes;
import org.lolaf.staffix.ha.replication.wire.WireFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.spec.AlgorithmParameterSpec;

/**
 * What the JCE allocates by itself to seal or open one AEAD frame, through the calls its API leaves no way round: a
 * parameter spec and an {@code init} per nonce, then the AAD and {@code doFinal} on arrays. The yardstick an
 * {@link AeadFrameCipher} is held to.
 */
@UtilityClass
public class JceAeadAllocation {

    /**
     * Enough that one object per frame, 16 bytes at least, shows far above the byte a test allows.
     */
    public static final int MEASURED = 20_000;
    /**
     * Allowed over the JCE's own per call: escape analysis removes some of the JCE's per-frame objects from one loop and
     * not from the other, up to 112 bytes seen between runs. Far below scratch arrays allocated per frame.
     */
    public static final int JIT_SLACK_BYTES = 128;

    /**
     * Frames to run before measuring, until the JIT has compiled the whole path: fewer for a long frame, whose loops
     * over its bytes get compiled sooner.
     */
    public static int warmUpFrames(int messageBytes) {
        return Math.max(50_000, 20_000_000 / Math.max(messageBytes, 1));
    }

    /**
     * @param mode {@link Cipher#ENCRYPT_MODE} or {@link Cipher#DECRYPT_MODE}
     */
    public static double bytesPerFrame(String algorithm, int mode, int aadBytes, int encryptedBytes) {
        try {
            Frames frames = new Frames(algorithm, mode, aadBytes, encryptedBytes);
            frames.run(warmUpFrames(aadBytes + encryptedBytes));
            long before = AllocatedBytes.ofCurrentThread();
            frames.run(MEASURED);
            return (double) (AllocatedBytes.ofCurrentThread() - before) / MEASURED;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Two frames taken in turn, each under its own nonce: an encryption refuses the nonce it was last given, and so
     * does a ChaCha20-Poly1305 decryption on JDK 17.
     */
    private static final class Frames {

        private final Cipher cipher;
        private final int mode;
        private final boolean gcm;
        private final SecretKeySpec key;
        private final byte[][] nonces;
        private final byte[][] inputs;
        private final byte[] output;
        private final int aadBytes;
        private final int inputBytes;
        private int next;

        Frames(String algorithm, int mode, int aadBytes, int encryptedBytes) throws GeneralSecurityException {
            this.cipher = Cipher.getInstance(algorithm);
            this.mode = mode;
            this.gcm = algorithm.startsWith("AES");
            this.key = new SecretKeySpec(new byte[32], gcm ? "AES" : "ChaCha20");
            this.nonces = new byte[][]{new byte[12], new byte[12]};
            this.inputs = new byte[2][aadBytes + encryptedBytes + WireFormat.TAG_BYTES];
            this.output = new byte[aadBytes + encryptedBytes + WireFormat.TAG_BYTES];
            this.aadBytes = aadBytes;
            this.next = 1;
            nonces[1][0] = 1;
            if (mode == Cipher.DECRYPT_MODE) {
                seal(0, encryptedBytes);
                this.inputBytes = seal(1, encryptedBytes);
            } else {
                this.inputBytes = encryptedBytes;
            }
        }

        private int seal(int frame, int encryptedBytes) throws GeneralSecurityException {
            cipher.init(Cipher.ENCRYPT_MODE, key, parameters(nonces[frame]));
            cipher.updateAAD(inputs[frame], 0, aadBytes);
            int sealed = cipher.doFinal(inputs[frame], aadBytes, encryptedBytes, output, 0);
            System.arraycopy(output, 0, inputs[frame], aadBytes, sealed);
            return sealed;
        }

        void run(int count) throws GeneralSecurityException {
            for (int i = 0; i < count; i++) {
                next ^= 1;
                cipher.init(mode, key, parameters(nonces[next]));
                cipher.updateAAD(inputs[next], 0, aadBytes);
                cipher.doFinal(inputs[next], aadBytes, inputBytes, output, 0);
            }
        }

        private AlgorithmParameterSpec parameters(byte[] nonce) {
            return gcm ? new GCMParameterSpec(WireFormat.TAG_BYTES * Byte.SIZE, nonce) : new IvParameterSpec(nonce);
        }
    }
}
