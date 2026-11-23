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
package org.lolaf.staffix.ha.replication.spi;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * What a stream's writes wait for, as its leader announces it.
 */
@Getter
@RequiredArgsConstructor
public enum StreamReplicationMode {

    /**
     * Announced but not replicated.
     */
    NONE((byte) 0),
    /**
     * Nothing waits for the follower.
     */
    ASYNC((byte) 1),
    /**
     * A write waits for the follower to have applied it.
     */
    SYNC_APPLIED((byte) 2),
    /**
     * A write waits for the follower to hold it in memory, not for it to apply it.
     */
    SYNC_RECEIVED((byte) 3);

    /**
     * Fixed rather than the ordinal, so the wire survives a reordering.
     */
    private final byte code;

    /**
     * Allocates nothing, unlike a search of {@code values()}.
     *
     * @throws IllegalArgumentException for a code no mode has
     */
    public static StreamReplicationMode from(byte code) {
        switch (code) {
            case 0:
                return NONE;
            case 1:
                return ASYNC;
            case 2:
                return SYNC_APPLIED;
            case 3:
                return SYNC_RECEIVED;
            default:
                throw new IllegalArgumentException("Unknown replication mode code " + code);
        }
    }

    /**
     * Whether a write waits for the follower.
     */
    public boolean isSync() {
        return this == SYNC_RECEIVED || this == SYNC_APPLIED;
    }
}
