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
package org.lolaf.staffix.ha.leader;

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.spi.LatencyRecorder;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * What the leader's connections did over the engine's life, across reconnections, read by the meters on another thread.
 */
public final class ReplicationStats {

    private final AtomicLong droppedTooSlow = new AtomicLong();
    @Getter
    private final SlowFollowers slowFollowers;
    private final AtomicLong snapshots = new AtomicLong();
    private final AtomicLong snapshotMessages = new AtomicLong();
    private final AtomicLong sendWaits = new AtomicLong();
    private final AtomicLong sendBuffersAllocated = new AtomicLong();
    private volatile BiFunction<String, Integer, LatencyRecorder> ackLatencyTimers = (followerId, lane) -> LatencyRecorder.NONE;
    private volatile BiFunction<String, Integer, LatencyRecorder> lagTimers = (followerId, lane) -> LatencyRecorder.NONE;

    public ReplicationStats(SlowFollowers slowFollowers) {
        this.slowFollowers = slowFollowers;
    }

    /**
     * Where the connections time their followers' round trips, by follower node id and lane, once the engine's meters
     * exist.
     */
    public void ackLatencyTimers(BiFunction<String, Integer, LatencyRecorder> timers) {
        ackLatencyTimers = timers;
    }

    LatencyRecorder ackLatency(String followerId, int lane) {
        return ackLatencyTimers.apply(followerId, lane);
    }

    /**
     * Where the connections time how far their followers are behind, by follower node id and lane, once the engine's
     * meters exist.
     */
    public void lagTimers(BiFunction<String, Integer, LatencyRecorder> timers) {
        lagTimers = timers;
    }

    LatencyRecorder lag(String followerId, int lane) {
        return lagTimers.apply(followerId, lane);
    }

    void droppedTooSlow(@Nullable String followerId, int lane) {
        droppedTooSlow.incrementAndGet();
        if (followerId != null) {
            slowFollowers.dropped(followerId, lane);
        }
    }

    void snapshotSent(int messages) {
        snapshots.incrementAndGet();
        snapshotMessages.addAndGet(messages);
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

    public long getSnapshotMessages() {
        return snapshotMessages.get();
    }
}
