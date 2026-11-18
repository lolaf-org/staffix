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
package org.lolaf.staffix.ha.log;

/**
 * The leader's replication lanes, each with its own log, so a snapshot or a slow follower on one lane holds back only
 * the sessions of that lane. A session belongs to lane {@code key % count}, keys being given out in registration
 * order.
 */
public final class ReplicationLogs {

    private final ReplicationLog[] logs;

    /**
     * @param capacity of each lane's log, bytes, a power of two of at least 1024
     */
    public ReplicationLogs(int lanes, int capacity) {
        if (lanes < 1) {
            throw new IllegalArgumentException("replicationLanes must be at least 1, got " + lanes);
        }
        this.logs = new ReplicationLog[lanes];
        for (int lane = 0; lane < lanes; lane++) {
            logs[lane] = new ReplicationLog(capacity);
        }
    }

    public int count() {
        return logs.length;
    }

    public ReplicationLog get(int lane) {
        return logs[lane];
    }

    public int laneOf(int sessionKey) {
        return sessionKey % logs.length;
    }

    public ReplicationLog forSession(int sessionKey) {
        return logs[laneOf(sessionKey)];
    }

    /**
     * The same for every lane, whose logs all have one capacity.
     */
    public int getMaxEventFrameBytes() {
        return logs[0].getMaxEventFrameBytes();
    }

    /**
     * The bytes written to every lane's log since the start.
     */
    public long getBytesWritten() {
        long written = 0;
        for (ReplicationLog log : logs) {
            written += log.getTailOffset();
        }
        return written;
    }
}
