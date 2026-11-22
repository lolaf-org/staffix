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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * Derives a connection's keys from the shared secret by HKDF-SHA256 (RFC 5869): extract condenses the secret and the
 * nonces into one key, expand draws each labelled key from it. Run once per connection, so it allocates freely.
 */
@UtilityClass
class KeyDerivation {

    private static final String HMAC = "HmacSHA256";
    private static final int HASH_BYTES = 32;

    static byte[] extract(byte[] salt, byte[] inputKey) {
        return hmac(salt).doFinal(inputKey);
    }

    static byte[] expand(byte[] pseudoRandomKey, String info, int length) {
        if (length > 255 * HASH_BYTES) {
            throw new IllegalArgumentException("HKDF-SHA256 expands to at most " + 255 * HASH_BYTES + " bytes");
        }
        Mac mac = hmac(pseudoRandomKey);
        byte[] infoBytes = info.getBytes(StandardCharsets.UTF_8);
        byte[] okm = new byte[length];
        byte[] block = new byte[0];
        for (int filled = 0, counter = 1; filled < length; counter++) {
            mac.update(block);
            mac.update(infoBytes);
            mac.update((byte) counter);
            block = mac.doFinal();
            int taken = Math.min(block.length, length - filled);
            System.arraycopy(block, 0, okm, filled, taken);
            filled += taken;
        }
        return okm;
    }

    static Mac hmac(byte[] key) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(HMAC + " is part of every JVM", e);
        }
    }
}
