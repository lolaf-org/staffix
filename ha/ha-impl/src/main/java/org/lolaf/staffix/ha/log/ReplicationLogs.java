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
 * The leader's replication links, each with its own log, so a snapshot or a slow follower on one link holds back only
 * the sessions of that link. A session belongs to link {@code key % count}, keys being given out in registration
 * order.
 */
public final class ReplicationLogs {

    private final ReplicationLog[] logs;

    /**
     * @param capacity of each link's log, bytes, a power of two of at least 1024
     */
    public ReplicationLogs(int links, int capacity) {
        if (links < 1) {
            throw new IllegalArgumentException("replicationLinks must be at least 1, got " + links);
        }
        this.logs = new ReplicationLog[links];
        for (int link = 0; link < links; link++) {
            logs[link] = new ReplicationLog(capacity);
        }
    }

    public int count() {
        return logs.length;
    }

    public ReplicationLog get(int link) {
        return logs[link];
    }

    public int linkOf(int sessionKey) {
        return sessionKey % logs.length;
    }

    public ReplicationLog forSession(int sessionKey) {
        return logs[linkOf(sessionKey)];
    }

    /**
     * The same for every link, whose logs all have one capacity.
     */
    public int getMaxEventFrameBytes() {
        return logs[0].getMaxEventFrameBytes();
    }

    /**
     * The bytes written to every link's log since the start.
     */
    public long getTailOffsets() {
        long written = 0;
        for (ReplicationLog log : logs) {
            written += log.getTailOffset();
        }
        return written;
    }
}
