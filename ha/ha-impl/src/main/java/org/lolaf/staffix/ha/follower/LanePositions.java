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
package org.lolaf.staffix.ha.follower;

import java.util.Arrays;

/**
 * Where each of the leader's lanes stands on this follower, kept across connections so a reconnection resumes: the
 * last event written, and the last applied but not written yet. A lane's offsets are those of its own log. A lane's
 * position is written by its own thread only; the last written is read by any. Allocates nothing but on a lane count
 * change.
 */
final class LanePositions {

    private static final Position NOTHING_APPLIED = new Position();

    private volatile Position[] positions = {new Position()};

    int count() {
        return positions.length;
    }

    /**
     * A lane's position is kept while the count does not change.
     */
    synchronized void setCount(int lanes) {
        Position[] current = positions;
        if (lanes == current.length) {
            return;
        }
        Position[] resized = Arrays.copyOf(current, lanes);
        for (int lane = current.length; lane < lanes; lane++) {
            resized[lane] = new Position();
        }
        positions = resized;
    }

    long getLastAppliedEpoch(int lane) {
        return position(lane).lastAppliedEpoch;
    }

    long getLastAppliedOffset(int lane) {
        return position(lane).lastAppliedOffset;
    }

    /**
     * See {@link ReplicaSessions#takeReceivedToAck(int)}.
     */
    long takeReceivedToAck(int lane) {
        Position position = position(lane);
        if (!position.receivedToAck) {
            return -1;
        }
        position.receivedToAck = false;
        return position.pendingOffset;
    }

    /**
     * @param resumed false leaves nothing to resume from
     */
    void streamStarted(int lane, boolean resumed) {
        Position position = positions[lane];
        if (!resumed) {
            position.lastAppliedOffset = -1;
        }
        position.pendingEpoch = position.lastAppliedEpoch;
        position.pendingOffset = position.lastAppliedOffset;
        position.receivedToAck = false;
    }

    /**
     * @param acksOnReceipt the event's session is acknowledged as received
     */
    void applied(int lane, long epoch, long offset, boolean acksOnReceipt) {
        Position position = positions[lane];
        position.pendingEpoch = epoch;
        position.pendingOffset = offset;
        position.receivedToAck |= acksOnReceipt;
    }

    /**
     * What was applied is now written.
     */
    void written(int lane) {
        Position position = positions[lane];
        position.lastAppliedEpoch = position.pendingEpoch;
        position.lastAppliedOffset = position.pendingOffset;
    }

    private Position position(int lane) {
        Position[] current = positions;
        return lane < current.length ? current[lane] : NOTHING_APPLIED;
    }

    private static final class Position {
        private volatile long lastAppliedEpoch;
        private volatile long lastAppliedOffset = -1;
        private long pendingEpoch;
        private long pendingOffset = -1;
        private boolean receivedToAck;
    }
}
