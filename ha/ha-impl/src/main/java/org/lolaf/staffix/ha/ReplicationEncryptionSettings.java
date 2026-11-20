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
package org.lolaf.staffix.ha;

import lombok.Builder;
import lombok.ToString;
import lombok.Value;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.util.List;

/**
 * Encrypts the replication between the two nodes with a secret they share. The cipher and the MAC are local policy,
 * never sent: both nodes must set the same, and a difference refuses the connection as a wrong secret does.
 */
@NullMarked
@Value
@Builder(toBuilder = true)
public class ReplicationEncryptionSettings {

    private static final int MIN_SECRET_BYTES = 16;
    private static final int MIN_MAC_BYTES = 16;

    private static final List<String> AEAD_CIPHERS = List.of("AES/GCM/NoPadding", "ChaCha20-Poly1305");
    private static final List<String> STREAM_CIPHERS = List.of("AES/CTR/NoPadding", "ChaCha20");

    /**
     * Whether this node encrypts, and whether it accepts a peer that does not.
     */
    @Builder.Default
    EncryptionMode mode = EncryptionMode.DISABLED;

    /**
     * The secret both nodes share, at least 16 bytes; required unless {@link EncryptionMode#DISABLED DISABLED}.
     */
    @ToString.Exclude
    byte @Nullable [] secret;

    /**
     * A JCE transformation, spelled exactly: an AEAD ({@code AES/GCM/NoPadding}, {@code ChaCha20-Poly1305}) sealing
     * each frame, or a stream cipher ({@code AES/CTR/NoPadding}, {@code ChaCha20}) authenticated by {@link #getMac()
     * mac}.
     */
    @Builder.Default
    String cipher = "AES/GCM/NoPadding";

    /**
     * The cipher's key size: 128, 192 or 256 for AES, 256 for ChaCha20.
     */
    @Builder.Default
    int keyBits = 256;

    /**
     * The JCE {@link Mac} authenticating each frame of a stream cipher, ignored with an AEAD; its tag is cut to 16
     * bytes, so it must make at least that.
     */
    @Builder.Default
    String mac = "HmacSHA256";

    /**
     * The JCE provider of the cipher and the MAC, such as a faster one than the JVM's; null takes the JVM's choice.
     */
    @Nullable
    String provider;

    /**
     * @throws IllegalArgumentException for an encrypting setting this JVM cannot run; nothing is checked when
     *                                  {@link EncryptionMode#DISABLED DISABLED}
     */
    void validate() {
        if (mode == EncryptionMode.DISABLED) {
            return;
        }
        if (secret == null || secret.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("encryption.secret must be at least " + MIN_SECRET_BYTES
                    + " bytes with encryption " + mode);
        }
        if (provider != null && Security.getProvider(provider) == null) {
            throw new IllegalArgumentException("encryption.provider " + provider + " is not installed");
        }
        boolean stream = STREAM_CIPHERS.contains(cipher);
        if (!stream && !AEAD_CIPHERS.contains(cipher)) {
            throw new IllegalArgumentException("encryption.cipher must be one of " + AEAD_CIPHERS + " or "
                    + STREAM_CIPHERS + ", got " + cipher);
        }
        validateKeyBits();
        checkCipherAvailable();
        if (stream) {
            validateMac();
        }
    }

    private void validateKeyBits() {
        List<Integer> allowed = cipher.startsWith("AES/") ? List.of(128, 192, 256) : List.of(256);
        if (!allowed.contains(keyBits)) {
            throw new IllegalArgumentException("encryption.keyBits must be one of " + allowed + " for " + cipher
                    + ", got " + keyBits);
        }
    }

    private void checkCipherAvailable() {
        try {
            if (provider == null) {
                Cipher.getInstance(cipher);
            } else {
                Cipher.getInstance(cipher, provider);
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("encryption.cipher " + cipher + " is not available"
                    + fromProvider() + ": " + e.getMessage(), e);
        }
    }

    private void validateMac() {
        int macBytes;
        try {
            macBytes = (provider == null ? Mac.getInstance(mac) : Mac.getInstance(mac, provider)).getMacLength();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("encryption.mac " + mac + " is not available" + fromProvider() + ": "
                    + e.getMessage(), e);
        }
        if (macBytes < MIN_MAC_BYTES) {
            throw new IllegalArgumentException("encryption.mac " + mac + " makes " + macBytes
                    + "-byte tags, at least " + MIN_MAC_BYTES + " needed");
        }
    }

    private String fromProvider() {
        return provider == null ? "" : " from " + provider;
    }

    public static class ReplicationEncryptionSettingsBuilder {

        /**
         * Shows what it would build, without the secret: Lombok's own builder {@code toString} ignores
         * {@code @ToString.Exclude}.
         */
        @Override
        public String toString() {
            return "ReplicationEncryptionSettingsBuilder(" + build() + ")";
        }
    }
}
