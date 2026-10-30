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

import org.lolaf.staffix.api.monitoring.Timer;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * What the leader's links did over the engine's life, across connections, read by the meters on another thread.
 */
public final class ReplicationStats {

    private final AtomicLong laps = new AtomicLong();
    private final AtomicLong snapshots = new AtomicLong();
    private final AtomicLong snapshotMessages = new AtomicLong();
    private final AtomicLong sendBuffersExhausted = new AtomicLong();
    private final AtomicLong sendBuffersAllocated = new AtomicLong();
    private Function<String, Timer> ackLatencyTimers = followerId -> Timer.VoidTimer.getInstance();
    private Function<String, Timer> lagTimers = followerId -> Timer.VoidTimer.getInstance();

    /**
     * Where the links time their followers' round trips, by follower node id, once the engine's meters exist.
     */
    public void ackLatencyTimers(Function<String, Timer> timers) {
        ackLatencyTimers = timers;
    }

    Timer ackLatency(String followerId) {
        return ackLatencyTimers.apply(followerId);
    }

    /**
     * Where the links time how far their followers are behind, by follower node id, once the engine's meters exist.
     */
    public void lagTimers(Function<String, Timer> timers) {
        lagTimers = timers;
    }

    Timer lag(String followerId) {
        return lagTimers.apply(followerId);
    }

    void lapped() {
        laps.incrementAndGet();
    }

    void snapshotSent(int messages) {
        snapshots.incrementAndGet();
        snapshotMessages.addAndGet(messages);
    }

    void sendBuffersExhausted() {
        sendBuffersExhausted.incrementAndGet();
    }

    void sendBuffersAllocated(int count) {
        sendBuffersAllocated.addAndGet(count);
    }

    /**
     * Times a sender waited for a send buffer, every one holding a batch its follower's socket had not taken yet.
     */
    public long getSendBuffersExhausted() {
        return sendBuffersExhausted.get();
    }

    /**
     * Write buffers allocated for want of a pooled one, 0 unless the senders' wait is defeated.
     */
    public long getSendBuffersAllocated() {
        return sendBuffersAllocated.get();
    }

    /**
     * Followers dropped for falling a whole replication log behind.
     */
    public long getLaps() {
        return laps.get();
    }

    public long getSnapshots() {
        return snapshots.get();
    }

    public long getSnapshotMessages() {
        return snapshotMessages.get();
    }
}
