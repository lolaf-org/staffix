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
 * Why a peer closes the replication stream, sent in a {@link FrameType#CLOSE} before the connection drops.
 */
@Getter
public enum CloseReason {

    SHUTDOWN(0),
    VERSION_MISMATCH(1),
    /**
     * The other side's epoch is older than this side's: it leads, or follows, a leadership that is over.
     */
    STALE_EPOCH(2),
    PROTOCOL_ERROR(3),
    /**
     * The leader could not read a session's store for a snapshot; the follower reconnects to try again.
     */
    SNAPSHOT_FAILED(4),
    /**
     * The follower could not apply what it received, a store write failing: it reconnects to resume after the last
     * event it applied.
     */
    APPLY_FAILED(5),
    /**
     * The follower owed an acknowledgement and stayed silent for the stall timeout: it reconnects and is caught up.
     */
    STALLED(6),
    /**
     * The follower read the log so slowly that a write waited the log full timeout for it: it reconnects and is
     * caught up by snapshot.
     */
    TOO_SLOW(7),
    /**
     * The follower was dropped as too slow and is held off before it may resync: it tries again at its next reconnect.
     */
    RETRY_LATER(8),
    /**
     * The follower opened a lane past the count the leader announced in its WELCOME.
     */
    NO_SUCH_LANE(9),
    /**
     * Any other code, from a newer peer; kept rather than refused so the close is still reported.
     */
    UNKNOWN(0xFFFF);

    private static final CloseReason[] BY_CODE = new CloseReason[NO_SUCH_LANE.code + 1];

    static {
        for (CloseReason reason : values()) {
            if (reason != UNKNOWN) {
                BY_CODE[reason.code] = reason;
            }
        }
    }

    private final int code;

    CloseReason(int code) {
        this.code = code;
    }

    public static CloseReason forCode(int code) {
        return code >= 0 && code < BY_CODE.length ? BY_CODE[code] : UNKNOWN;
    }
}
