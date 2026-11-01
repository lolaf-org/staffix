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
    /**
     * The follower connected to a node that does not lead.
     */
    NOT_LEADER(3),
    PROTOCOL_ERROR(4),
    /**
     * The leader could not read a session's store for a snapshot; the follower reconnects to try again.
     */
    SNAPSHOT_FAILED(6),
    /**
     * The follower could not apply what it received, a store write failing: it reconnects to resume after the last
     * event it applied.
     */
    APPLY_FAILED(7),
    /**
     * The follower owed an acknowledgement and stayed silent for the stall timeout: it reconnects and is caught up.
     */
    STALLED(8),
    /**
     * The follower read the log so slowly that a write waited the log full timeout for it: it reconnects and is
     * caught up by snapshot.
     */
    TOO_SLOW(9),
    /**
     * The follower was dropped as too slow and is held off before it may resync: it tries again at its next reconnect.
     */
    RETRY_LATER(10),
    /**
     * The follower opened a link past the count the leader announced in its WELCOME.
     */
    NO_SUCH_LINK(11),
    /**
     * Any other code, from a newer peer; kept rather than refused so the close is still reported.
     */
    UNKNOWN(0xFFFF);

    private final int code;

    CloseReason(int code) {
        this.code = code;
    }

    public static CloseReason forCode(int code) {
        switch (code) {
            case 0:
                return SHUTDOWN;
            case 1:
                return VERSION_MISMATCH;
            case 2:
                return STALE_EPOCH;
            case 3:
                return NOT_LEADER;
            case 4:
                return PROTOCOL_ERROR;
            case 6:
                return SNAPSHOT_FAILED;
            case 7:
                return APPLY_FAILED;
            case 8:
                return STALLED;
            case 9:
                return TOO_SLOW;
            case 10:
                return RETRY_LATER;
            case 11:
                return NO_SUCH_LINK;
            default:
                return UNKNOWN;
        }
    }
}
