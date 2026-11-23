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

import org.lolaf.staffix.ha.replication.testkit.TestingKeyExchanges;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lolaf.staffix.ha.replication.EncryptionMode;
import org.lolaf.staffix.ha.replication.wire.WireFormat;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lolaf.staffix.ha.replication.EncryptionMode.DISABLED;
import static org.lolaf.staffix.ha.replication.EncryptionMode.PREFERRED;
import static org.lolaf.staffix.ha.replication.EncryptionMode.REQUIRED;

class TestKeyExchange {

    private static final byte[] HELLO = "the hello".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WELCOME = "the welcome".getBytes(StandardCharsets.US_ASCII);

    private static KeyExchange of(EncryptionMode mode) {
        return mode == DISABLED ? TestingKeyExchanges.CLEAR : TestingKeyExchanges.of(mode);
    }

    @ParameterizedTest(name = "{0} facing {1} is {2}")
    @CsvSource({
            "DISABLED, DISABLED, CLEAR",
            "DISABLED, PREFERRED, CLEAR",
            "DISABLED, REQUIRED, REFUSED",
            "PREFERRED, DISABLED, CLEAR_AGAINST_PREFERENCE",
            "PREFERRED, PREFERRED, ENCRYPTED",
            "PREFERRED, REQUIRED, ENCRYPTED",
            "REQUIRED, DISABLED, REFUSED",
            "REQUIRED, PREFERRED, ENCRYPTED",
            "REQUIRED, REQUIRED, ENCRYPTED"})
    void theModesDecideTheConnection(EncryptionMode mode, EncryptionMode peerMode, Negotiation negotiation) {
        assertThat(of(mode).negotiate(peerMode)).isEqualTo(negotiation);
    }

    @Test
    void aNodeNotEncryptingSendsZerosAndAnEncryptingOneANewNonceEachTime() {
        KeyExchange encrypting = TestingKeyExchanges.of(PREFERRED);

        assertThat(TestingKeyExchanges.CLEAR.newNonce()).isEqualTo(new byte[WireFormat.NONCE_BYTES]);
        assertThat(encrypting.newNonce()).hasSize(WireFormat.NONCE_BYTES).isNotEqualTo(encrypting.newNonce());
    }

    @Test
    void eachConnectionAndEachDirectionHasItsOwnKeys() {
        KeyExchange exchange = TestingKeyExchanges.of(REQUIRED);
        byte[] follower = exchange.newNonce();
        byte[] leader = exchange.newNonce();

        ConnectionEncryptionKeys keys = exchange.deriveKeys(follower, leader);
        ConnectionEncryptionKeys again = exchange.deriveKeys(follower, leader);
        ConnectionEncryptionKeys another = exchange.deriveKeys(follower, exchange.newNonce());

        assertThat(keys.getLeaderToFollower().getCipherKey()).hasSize(32).isEqualTo(again.getLeaderToFollower().getCipherKey())
                .isNotEqualTo(keys.getFollowerToLeader().getCipherKey())
                .isNotEqualTo(another.getLeaderToFollower().getCipherKey());
        assertThat(keys.getLeaderToFollower().getMacKey()).hasSize(32);
        assertThat(keys.getLeaderToFollower().getIv()).hasSize(16);
        assertThat(keys.getConfirmationKey()).hasSize(WireFormat.KEY_CONFIRMATION_BYTES);
    }

    @Test
    void theConfirmationHoldsWithTheSameSecretAndCipherOnly() {
        KeyExchange leader = TestingKeyExchanges.of(REQUIRED);
        byte[] followerNonce = leader.newNonce();
        byte[] leaderNonce = leader.newNonce();
        ConnectionEncryptionKeys keys = leader.deriveKeys(followerNonce, leaderNonce);

        assertThat(leader.confirms(keys, HELLO, WELCOME, confirmationBy(TestingKeyExchanges.of(PREFERRED), followerNonce, leaderNonce)))
                .as("same secret and cipher, other mode").isTrue();
        assertThat(leader.confirms(keys, HELLO, WELCOME, confirmationBy(TestingKeyExchanges.of(REQUIRED, "another-secret-entirely",
                "AES/GCM/NoPadding"), followerNonce, leaderNonce))).as("other secret").isFalse();
        assertThat(leader.confirms(keys, HELLO, WELCOME, confirmationBy(TestingKeyExchanges.of(REQUIRED, TestingKeyExchanges.SECRET,
                "ChaCha20-Poly1305"), followerNonce, leaderNonce))).as("other cipher").isFalse();
    }

    @Test
    void theConfirmationCoversEveryByteOfTheHelloAndTheWelcome() {
        KeyExchange exchange = TestingKeyExchanges.of(REQUIRED);
        ConnectionEncryptionKeys keys = exchange.deriveKeys(exchange.newNonce(), exchange.newNonce());
        byte[] confirmation = exchange.confirm(keys, HELLO, WELCOME);
        byte[] alteredHello = HELLO.clone();
        alteredHello[3] ^= 1;

        assertThat(exchange.confirms(keys, HELLO, WELCOME, confirmation)).isTrue();
        assertThat(exchange.confirms(keys, alteredHello, WELCOME, confirmation)).isFalse();
        assertThat(exchange.confirms(keys, WELCOME, HELLO, confirmation)).as("frames swapped").isFalse();
    }

    @Test
    void theCipherNamedIsWhatThePeersMustShare() {
        assertThat(TestingKeyExchanges.of(REQUIRED, TestingKeyExchanges.SECRET, "AES/GCM/NoPadding").describeCipher())
                .isEqualTo("AES/GCM/NoPadding 256");
        assertThat(TestingKeyExchanges.of(REQUIRED, TestingKeyExchanges.SECRET, "AES/CTR/NoPadding").describeCipher())
                .isEqualTo("AES/CTR/NoPadding 256 HmacSHA256");
    }

    private static byte[] confirmationBy(KeyExchange follower, byte[] followerNonce, byte[] leaderNonce) {
        return follower.confirm(follower.deriveKeys(followerNonce, leaderNonce), HELLO, WELCOME);
    }
}
