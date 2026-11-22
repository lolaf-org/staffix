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
package org.lolaf.staffix.ha.wire;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * The follower's answer to a {@link FrameType#STREAM}: whether it replicates the stream, and why not.
 */
@Getter
public enum StreamAnswer {

    REPLICATING(0),
    /**
     * The follower's store is the leader's own storage, a configuration error: replicating into it would rewrite the
     * leader's data, and each node needs a copy of its own.
     */
    SHARED_STORAGE(1),
    /**
     * The follower cannot identify its storage, so it cannot rule out sharing the leader's.
     */
    UNKNOWN_STORAGE(2),
    /**
     * The follower's configuration does not know the stream.
     */
    UNKNOWN_STREAM(3),
    /**
     * The follower replicates the stream but misses part of its history: it needs a snapshot to be in sync.
     */
    SNAPSHOT_NEEDED(4),
    /**
     * The leader announced the stream with replication mode {@code NONE}: its configuration stopped replicating it.
     */
    NOT_REPLICATED(5),
    /**
     * The follower's configuration gives the stream another replication mode than the leader's: a configuration error.
     */
    MODE_MISMATCH(6);

    private final int code;

    StreamAnswer(int code) {
        this.code = code;
    }

    /**
     * The follower would replicate the stream but cannot: a problem to fix, unlike an answer the leader expects.
     */
    public boolean isRefusal() {
        return this == SHARED_STORAGE || this == UNKNOWN_STORAGE || this == UNKNOWN_STREAM || this == MODE_MISMATCH;
    }

    /**
     * @return the answer, or null for an unknown code
     */
    public static @Nullable StreamAnswer forCode(int code) {
        switch (code) {
            case 0:
                return REPLICATING;
            case 1:
                return SHARED_STORAGE;
            case 2:
                return UNKNOWN_STORAGE;
            case 3:
                return UNKNOWN_STREAM;
            case 4:
                return SNAPSHOT_NEEDED;
            case 5:
                return NOT_REPLICATED;
            case 6:
                return MODE_MISMATCH;
            default:
                return null;
        }
    }
}
