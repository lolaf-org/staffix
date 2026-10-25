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

/**
 * The follower's answer to a {@link FrameType#SESSION}: whether it replicates the session, and why not.
 */
@Getter
public enum SessionStatus {

    REPLICATING(0),
    /**
     * The follower's store is the leader's own storage: replaying into it would rewrite the leader's data.
     */
    SHARED_STORAGE(1),
    /**
     * The follower cannot identify its storage, so it cannot rule out sharing the leader's.
     */
    UNKNOWN_STORAGE(2),
    /**
     * The follower has no settings for this session.
     */
    UNKNOWN_SESSION(3),
    /**
     * The follower replicates the session but misses part of its history: it needs a snapshot to be in sync.
     */
    SNAPSHOT_NEEDED(4),
    /**
     * The leader announced the session with replication mode {@code NONE}: its settings stopped its replication.
     */
    NOT_REPLICATED(5);

    private final int code;

    SessionStatus(int code) {
        this.code = code;
    }

    /**
     * @return the status, or null for an unknown code
     */
    public static SessionStatus forCode(int code) {
        switch (code) {
            case 0:
                return REPLICATING;
            case 1:
                return SHARED_STORAGE;
            case 2:
                return UNKNOWN_STORAGE;
            case 3:
                return UNKNOWN_SESSION;
            case 4:
                return SNAPSHOT_NEEDED;
            case 5:
                return NOT_REPLICATED;
            default:
                return null;
        }
    }
}
