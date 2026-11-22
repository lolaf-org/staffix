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

/**
 * What a connection does with the two peers' encryption modes.
 */
public enum Negotiation {

    /**
     * Both encrypt: the follower confirms it holds the same secret and cipher before anything streams.
     */
    ENCRYPTED,

    /**
     * Neither encrypts, or only the peer would like to.
     */
    CLEAR,

    /**
     * This node prefers encryption and the peer has it disabled: clear, with an error on this node.
     */
    CLEAR_AGAINST_PREFERENCE,

    /**
     * One requires encryption and the other has it disabled.
     */
    REFUSED
}
