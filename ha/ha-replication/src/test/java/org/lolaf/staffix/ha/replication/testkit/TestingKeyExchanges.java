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
package org.lolaf.staffix.ha.replication.testkit;

import lombok.experimental.UtilityClass;
import org.lolaf.staffix.ha.replication.EncryptionMode;
import org.lolaf.staffix.ha.replication.ReplicationEncryptionSettings;
import org.lolaf.staffix.ha.replication.encryption.KeyExchange;

import java.nio.charset.StandardCharsets;

/**
 * Key exchanges for the tests: one not encrypting, the nonce it sends, and encrypting ones, by default with the
 * engine's default cipher.
 */
@UtilityClass
public class TestingKeyExchanges {

    public static final KeyExchange CLEAR = new KeyExchange(ReplicationEncryptionSettings.builder().build());
    public static final byte[] NO_NONCE = CLEAR.newNonce();
    public static final String CIPHER = ReplicationEncryptionSettings.builder().build().getCipher();

    public static final String SECRET = "a-secret-of-the-pair";

    public static KeyExchange of(EncryptionMode mode) {
        return of(mode, SECRET, CIPHER);
    }

    public static KeyExchange of(EncryptionMode mode, String secret, String cipher) {
        return new KeyExchange(ReplicationEncryptionSettings.builder().mode(mode)
                .secret(secret.getBytes(StandardCharsets.UTF_8)).cipher(cipher).build());
    }
}
