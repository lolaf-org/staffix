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

import org.lolaf.staffix.ha.EncryptionMode;
import org.lolaf.staffix.ha.ReplicationEncryptionSettings;
import org.lolaf.staffix.ha.wire.FrameCipher;
import org.lolaf.staffix.ha.wire.WireFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Objects;

/**
 * The encryption part of the replication handshake, the same on both nodes: the two modes decide whether a connection
 * encrypts, and when it does, both nonces and the shared secret give its keys, which the follower confirms it holds by
 * an HMAC of the HELLO and WELCOME. The confirmation covers every field of both frames and the cipher, so a frame
 * altered in transit or a cipher the peers disagree on fails it as a wrong secret does.
 */
public final class KeyExchange {

    private static final byte[] NO_NONCE = new byte[WireFormat.NONCE_BYTES];
    private static final byte[] CONFIRMATION_LABEL = "staffix-ha key confirmation v1".getBytes(StandardCharsets.UTF_8);
    private static final String STAFFIX_HA = "staffix-ha ";

    private final ReplicationEncryptionSettings settings;
    private final SecureRandom random;

    /**
     * @param settings validated
     */
    public KeyExchange(ReplicationEncryptionSettings settings) {
        this.random = new SecureRandom();
        this.settings = settings;
    }

    private static void updateWithLength(Mac mac, byte[] bytes) {
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        mac.update(bytes);
    }

    public EncryptionMode getMode() {
        return settings.getMode();
    }

    /**
     * The cipher, its key size and, for a stream cipher, its MAC: what the peers must agree on, named in the logs.
     */
    public String describeCipher() {
        String cipher = settings.getCipher() + " " + settings.getKeyBits();
        return settings.isStreamCipher() ? cipher + " " + settings.getMac() : cipher;
    }

    /**
     * Zeros when this node does not encrypt.
     */
    public byte[] newNonce() {
        if (settings.getMode() == EncryptionMode.DISABLED) {
            return NO_NONCE.clone();
        }
        byte[] nonce = new byte[WireFormat.NONCE_BYTES];
        random.nextBytes(nonce);
        return nonce;
    }

    public Negotiation negotiate(EncryptionMode peerMode) {
        EncryptionMode mode = settings.getMode();
        if (mode == EncryptionMode.DISABLED) {
            return peerMode == EncryptionMode.REQUIRED ? Negotiation.REFUSED : Negotiation.CLEAR;
        }
        if (peerMode != EncryptionMode.DISABLED) {
            return Negotiation.ENCRYPTED;
        }
        return mode == EncryptionMode.REQUIRED ? Negotiation.REFUSED : Negotiation.CLEAR_AGAINST_PREFERENCE;
    }

    public ConnectionEncryptionKeys deriveKeys(byte[] followerNonce, byte[] leaderNonce) {
        byte[] salt = ByteBuffer.allocate(followerNonce.length + leaderNonce.length).put(followerNonce).put(leaderNonce)
                .array();
        byte[] prk = KeyDerivation.extract(salt, Objects.requireNonNull(settings.getSecret(), "secret"));
        return new ConnectionEncryptionKeys(KeyDerivation.expand(prk, "staffix-ha key confirmation", WireFormat.KEY_CONFIRMATION_BYTES),
                direction(prk, "leader-to-follower"), direction(prk, "follower-to-leader"));
    }

    /**
     * @param hello   the HELLO frame as the follower sent it
     * @param welcome the WELCOME frame as the leader sent it
     */
    public byte[] confirm(ConnectionEncryptionKeys keys, byte[] hello, byte[] welcome) {
        Mac mac = KeyDerivation.hmac(keys.getConfirmationKey());
        mac.update(CONFIRMATION_LABEL);
        updateWithLength(mac, hello);
        updateWithLength(mac, welcome);
        updateWithLength(mac, describeCipher().getBytes(StandardCharsets.UTF_8));
        return mac.doFinal();
    }

    /**
     * In constant time, so a forger learns nothing from how long a refusal takes.
     */
    public boolean confirms(ConnectionEncryptionKeys keys, byte[] hello, byte[] welcome, byte[] confirmation) {
        return MessageDigest.isEqual(confirm(keys, hello, welcome), confirmation);
    }

    /**
     * Seals what this node sends in the direction {@code keys} are for, on the one thread sending it.
     */
    public FrameCipher newSealer(ConnectionEncryptionKeys.DirectionKeys keys) {
        return newFrameCipher(keys, Cipher.ENCRYPT_MODE);
    }

    /**
     * Opens what this node receives in the direction {@code keys} are for, on the one thread reading it.
     */
    public FrameCipher newOpener(ConnectionEncryptionKeys.DirectionKeys keys) {
        return newFrameCipher(keys, Cipher.DECRYPT_MODE);
    }

    private FrameCipher newFrameCipher(ConnectionEncryptionKeys.DirectionKeys keys, int mode) {
        String provider = settings.getProvider();
        try {
            Cipher cipher = provider == null ? Cipher.getInstance(settings.getCipher())
                    : Cipher.getInstance(settings.getCipher(), provider);
            if (settings.isStreamCipher()) {
                return new StreamFrameCipher(cipher, mode, keys.getCipherKey(), keys.getIv(),
                        FrameMac.of(settings.getMac(), provider, keys.getMacKey()), StreamFrameCipher.SEGMENT_BYTES);
            }
            return new AeadFrameCipher(cipher, mode, keys.getCipherKey(), keys.getIv());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The settings validated " + describeCipher() + ", which now fails", e);
        }
    }

    private ConnectionEncryptionKeys.DirectionKeys direction(byte[] prk, String direction) {
        return new ConnectionEncryptionKeys.DirectionKeys(
                KeyDerivation.expand(prk, STAFFIX_HA + direction + " cipher", settings.getKeyBits() / Byte.SIZE),
                KeyDerivation.expand(prk, STAFFIX_HA + direction + " mac", 32),
                KeyDerivation.expand(prk, STAFFIX_HA + direction + " iv", 16));
    }
}
