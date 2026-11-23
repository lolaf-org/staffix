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
package org.lolaf.staffix.ha.replication.leader;

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.replication.spi.LatencyRecorder;
import org.lolaf.staffix.ha.replication.spi.ReplicationMetrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * What the leader's connections did over the engine's life, across reconnections, read by the meters on another thread.
 */
public final class ReplicationStats {

    private final AtomicLong droppedTooSlow;
    @Getter
    private final SlowFollowers slowFollowers;
    private final AtomicLong snapshots;
    private final AtomicLong snapshotRecords;
    private final AtomicLong sendWaits;
    private final AtomicLong sendBuffersAllocated;
    private final ReplicationMetrics metrics;

    public ReplicationStats(SlowFollowers slowFollowers, ReplicationMetrics metrics) {
        this.slowFollowers = slowFollowers;
        this.metrics = metrics;
        this.droppedTooSlow = new AtomicLong();
        this.snapshots = new AtomicLong();
        this.snapshotRecords = new AtomicLong();
        this.sendWaits = new AtomicLong();
        this.sendBuffersAllocated = new AtomicLong();
    }

    LatencyRecorder ackLatency(String followerId, int lane) {
        return metrics.followerAckLatency(followerId, lane);
    }

    LatencyRecorder lag(String followerId, int lane) {
        return metrics.followerLag(followerId, lane);
    }

    void droppedTooSlow(@Nullable String followerId, int lane) {
        droppedTooSlow.incrementAndGet();
        if (followerId != null) {
            slowFollowers.dropped(followerId, lane);
        }
    }

    void snapshotSent(int records) {
        snapshots.incrementAndGet();
        snapshotRecords.addAndGet(records);
    }

    void sendWaited() {
        sendWaits.incrementAndGet();
    }

    void sendBuffersAllocated(int count) {
        sendBuffersAllocated.addAndGet(count);
    }

    /**
     * Times a sender had something to send and could not: every send buffer in flight, or betty's write watermark reached.
     */
    public long getSendWaits() {
        return sendWaits.get();
    }

    /**
     * Write buffers allocated for want of a pooled one, 0 unless the senders' wait is defeated.
     */
    public long getSendBuffersAllocated() {
        return sendBuffersAllocated.get();
    }

    /**
     * Follower lanes dropped for reading the replication log so slowly that a write waited the log full timeout for them.
     */
    public long getDroppedTooSlow() {
        return droppedTooSlow.get();
    }

    public long getSnapshots() {
        return snapshots.get();
    }

    public long getSnapshotRecords() {
        return snapshotRecords.get();
    }
}
