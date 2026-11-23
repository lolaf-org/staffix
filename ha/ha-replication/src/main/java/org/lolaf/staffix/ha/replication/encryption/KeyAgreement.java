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

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.replication.wire.FrameCipher;

import java.util.Objects;

/**
 * One connection's side of the encrypted handshake: its nonce, the HELLO and WELCOME the KEY_CONFIRMATION covers, kept
 * until it is made or checked, and the keys both nonces give. IO thread until the stream starts.
 */
public final class KeyAgreement {

    private final KeyExchange keyExchange;
    private final boolean leader;
    @Getter
    private final byte[] nonce;
    private byte @Nullable [] hello;
    private byte @Nullable [] welcome;
    /**
     * Null until agreed, and on a clear connection.
     */
    @Getter
    private volatile @Nullable ConnectionEncryptionKeys keys;

    private KeyAgreement(KeyExchange keyExchange, boolean leader) {
        this.keyExchange = keyExchange;
        this.leader = leader;
        this.nonce = keyExchange.newNonce();
    }

    public static KeyAgreement ofLeader(KeyExchange keyExchange) {
        return new KeyAgreement(keyExchange, true);
    }

    public static KeyAgreement ofFollower(KeyExchange keyExchange) {
        return new KeyAgreement(keyExchange, false);
    }

    /**
     * @param hello the HELLO as the follower sent it
     */
    public void hello(byte[] hello) {
        this.hello = hello;
    }

    /**
     * Derives the keys, both peers encrypting.
     *
     * @param welcome the WELCOME as the leader sent it
     */
    public void agree(byte[] peerNonce, byte[] welcome) {
        this.welcome = welcome;
        keys = leader ? keyExchange.deriveKeys(peerNonce, nonce) : keyExchange.deriveKeys(nonce, peerNonce);
    }

    /**
     * The follower's KEY_CONFIRMATION.
     */
    public byte[] confirmation() {
        byte[] confirmation = keyExchange.confirm(agreedKeys(), Objects.requireNonNull(hello),
                Objects.requireNonNull(welcome));
        forgetHandshake();
        return confirmation;
    }

    /**
     * Whether the follower's KEY_CONFIRMATION proves it holds the same keys.
     */
    public boolean confirms(byte[] confirmation) {
        byte @Nullable [] sentHello = hello;
        byte @Nullable [] sentWelcome = welcome;
        forgetHandshake();
        return sentHello != null && sentWelcome != null
                && keyExchange.confirms(agreedKeys(), sentHello, sentWelcome, confirmation);
    }

    /**
     * Seals what this side sends, on the one thread sending it.
     */
    public FrameCipher newSealer() {
        ConnectionEncryptionKeys agreed = agreedKeys();
        return keyExchange.newSealer(leader ? agreed.getLeaderToFollower() : agreed.getFollowerToLeader());
    }

    /**
     * Opens what this side receives, on the one thread reading it.
     */
    public FrameCipher newOpener() {
        ConnectionEncryptionKeys agreed = agreedKeys();
        return keyExchange.newOpener(leader ? agreed.getFollowerToLeader() : agreed.getLeaderToFollower());
    }

    private ConnectionEncryptionKeys agreedKeys() {
        return Objects.requireNonNull(keys, "the keys are not agreed");
    }

    private void forgetHandshake() {
        hello = null;
        welcome = null;
    }
}
