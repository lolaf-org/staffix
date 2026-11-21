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
package org.lolaf.staffix.ha.encryption;

import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.wire.WireFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.DigestException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * The tag of a stream cipher's frame: a MAC over the frame's number in its direction, then its bytes, cut to
 * {@link WireFormat#TAG_BYTES}.
 */
abstract class FrameMac {

    /**
     * The digest and its block size behind each HMAC computed by hand, which allocates nothing where
     * {@link Mac#doFinal(byte[], int)} allocates on every call.
     */
    private static final Map<String, Digest> HMAC_DIGESTS = Map.of(
            "HmacSHA224", new Digest("SHA-224", 64),
            "HmacSHA256", new Digest("SHA-256", 64),
            "HmacSHA384", new Digest("SHA-384", 128),
            "HmacSHA512", new Digest("SHA-512", 128),
            "HmacSHA512/224", new Digest("SHA-512/224", 128),
            "HmacSHA512/256", new Digest("SHA-512/256", 128),
            "HmacSHA3-256", new Digest("SHA3-256", 136),
            "HmacSHA3-384", new Digest("SHA3-384", 104),
            "HmacSHA3-512", new Digest("SHA3-512", 72));

    final byte[] frameNumber;

    FrameMac() {
        this.frameNumber = new byte[Long.BYTES];
    }

    /**
     * @param algorithm a JCE {@link Mac} name, validated
     */
    static FrameMac of(String algorithm, @Nullable String provider, byte[] key) throws GeneralSecurityException {
        Digest digest = HMAC_DIGESTS.get(algorithm);
        if (digest != null) {
            try {
                return new DigestHmac(digest.messageDigest(provider), digest.messageDigest(provider), digest.blockBytes, key);
            } catch (NoSuchAlgorithmException e) {
                // the provider has the MAC without its digest
            }
        }
        Mac mac = provider == null ? Mac.getInstance(algorithm) : Mac.getInstance(algorithm, provider);
        mac.init(new SecretKeySpec(key, algorithm));
        return new JceMac(mac);
    }

    abstract void start(long frame);

    abstract void update(byte[] bytes, int offset, int length);

    /**
     * Writes the tag and makes ready for the next {@link #start}.
     */
    abstract void finish(byte[] tag);

    private static final class Digest {
        private final String name;
        private final int blockBytes;

        Digest(String name, int blockBytes) {
            this.name = name;
            this.blockBytes = blockBytes;
        }

        MessageDigest messageDigest(@Nullable String provider) throws GeneralSecurityException {
            return provider == null ? MessageDigest.getInstance(name) : MessageDigest.getInstance(name, provider);
        }
    }

    /**
     * HMAC as RFC 2104 defines it, the key padded once into the two blocks every tag starts with.
     */
    static final class DigestHmac extends FrameMac {

        private static final byte INNER_PAD = 0x36;
        private static final byte OUTER_PAD = 0x5c;

        private final MessageDigest inner;
        private final MessageDigest outer;
        private final byte[] innerPad;
        private final byte[] outerPad;
        private final byte[] hash;

        DigestHmac(MessageDigest inner, MessageDigest outer, int blockBytes, byte[] key) {
            this.inner = inner;
            this.outer = outer;
            byte[] blockKey = key.length > blockBytes ? inner.digest(key) : key;
            innerPad = new byte[blockBytes];
            outerPad = new byte[blockBytes];
            for (int i = 0; i < blockBytes; i++) {
                byte keyByte = i < blockKey.length ? blockKey[i] : 0;
                innerPad[i] = (byte) (keyByte ^ INNER_PAD);
                outerPad[i] = (byte) (keyByte ^ OUTER_PAD);
            }
            hash = new byte[inner.getDigestLength()];
        }

        @Override
        void start(long frame) {
            inner.update(innerPad);
            FrameBytes.putLong(frameNumber, 0, frame);
            inner.update(frameNumber);
        }

        @Override
        void update(byte[] bytes, int offset, int length) {
            inner.update(bytes, offset, length);
        }

        @Override
        void finish(byte[] tag) {
            try {
                inner.digest(hash, 0, hash.length);
                outer.update(outerPad);
                outer.update(hash);
                outer.digest(hash, 0, hash.length);
            } catch (DigestException e) {
                throw new IllegalStateException("A digest of " + hash.length + " bytes does not fit its own length", e);
            }
            System.arraycopy(hash, 0, tag, 0, WireFormat.TAG_BYTES);
        }
    }

    /**
     * Any other MAC, through the JCE: it allocates a little per tag.
     */
    static final class JceMac extends FrameMac {

        private final Mac mac;
        private final byte[] full;

        JceMac(Mac mac) {
            this.mac = mac;
            this.full = new byte[mac.getMacLength()];
        }

        @Override
        void start(long frame) {
            FrameBytes.putLong(frameNumber, 0, frame);
            mac.update(frameNumber);
        }

        @Override
        void update(byte[] bytes, int offset, int length) {
            mac.update(bytes, offset, length);
        }

        @Override
        void finish(byte[] tag) {
            try {
                mac.doFinal(full, 0);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(mac.getAlgorithm() + " refused its own length", e);
            }
            System.arraycopy(full, 0, tag, 0, WireFormat.TAG_BYTES);
        }
    }
}
