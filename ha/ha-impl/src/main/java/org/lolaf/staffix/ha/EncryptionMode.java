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

/**
 * Whether a node encrypts its replication, decided per connection with its peer's mode.
 */
public enum EncryptionMode {

    /**
     * Replicates in clear; a {@link #REQUIRED} peer refuses it.
     */
    DISABLED,

    /**
     * Encrypts when the peer does, replicates in clear with an error logged when it does not: the step between
     * clear and {@link #REQUIRED} while the other node is not yet set up.
     */
    PREFERRED,

    /**
     * Refuses a peer that does not encrypt. Only this mode stops an attacker on the network downgrading the
     * connection to clear.
     */
    REQUIRED
}
