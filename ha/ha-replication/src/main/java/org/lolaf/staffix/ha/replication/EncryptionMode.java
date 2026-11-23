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
package org.lolaf.staffix.ha.replication;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Whether a node encrypts its replication, decided per connection with its peer's mode.
 */
@Getter
@RequiredArgsConstructor
public enum EncryptionMode {

    /**
     * Replicates in clear; a {@link #REQUIRED} peer refuses it.
     */
    DISABLED((byte) 0),

    /**
     * Encrypts when the peer does, replicates in clear with an error logged when it does not: the step between
     * clear and {@link #REQUIRED} while the other node is not yet set up.
     */
    PREFERRED((byte) 1),

    /**
     * Refuses a peer that does not encrypt. Only this mode stops an attacker on the network downgrading the
     * connection to clear.
     */
    REQUIRED((byte) 2);

    /**
     * Sent in HELLO and WELCOME; fixed rather than the ordinal, so the wire survives a reordering.
     */
    private final byte code;

    /**
     * @throws IllegalArgumentException for a code no mode has
     */
    public static EncryptionMode from(byte code) {
        for (EncryptionMode mode : values()) {
            if (mode.code == code) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown encryption mode code " + code);
    }
}
