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

import lombok.AccessLevel;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.replication.spi.LatencyRecorder;
import org.lolaf.staffix.ha.replication.spi.ReplicationClock;

/**
 * What one follower acknowledged on its lane: written from its ACK and RECEIVED frames on the connection's IO thread,
 * read by the writers awaiting acks and by the sender.
 */
public final class FollowerAcks {

    private final ReplicationStats stats;
    private final ReplicationClock clock;
    /**
     * The offset of the last record the follower applied, -1 before it said.
     */
    @Getter
    private volatile long appliedOffset;
    /**
     * The offset of the last record the follower holds, written by its sink or not yet, -1 before it said.
     */
    @Getter
    private volatile long receivedOffset;
    /**
     * What the follower said of itself in its last ACK; whether a stream is in sync relies on the leader's own account.
     */
    @Getter
    private volatile boolean reportedInSync;
    /**
     * What the follower's sinks for this lane have yet to write, as of its last ack.
     */
    @Getter
    private volatile long pendingWrites;
    /**
     * When the last ack arrived, 0 before any.
     */
    @Getter(AccessLevel.PACKAGE)
    private volatile long lastAckNanos;
    /**
     * The offset a {@code SYNC} send gave up waiting for, -1 when none: the follower is not waited for again until it
     * applies it, so a hung follower costs one timeout rather than one per send.
     */
    private volatile long overdueOffset;
    private @Nullable String followerId;
    private int lane;
    /**
     * Dropped as too slow last time, until its first in-sync ack; IO thread only.
     */
    private boolean recoveringFromTooSlow;
    private AckLatencySampler ackLatency;
    private LagSampler lag;

    FollowerAcks(ReplicationStats stats, ReplicationClock clock) {
        this.appliedOffset = -1;
        this.receivedOffset = -1;
        this.overdueOffset = -1;
        this.ackLatency = new AckLatencySampler(LatencyRecorder.NONE);
        this.lag = new LagSampler(LatencyRecorder.NONE);
        this.stats = stats;
        this.clock = clock;
    }

    /**
     * The follower's HELLO, saying the last record it applied.
     */
    void hello(String followerId, int lane, long lastAppliedOffset) {
        this.followerId = followerId;
        this.lane = lane;
        recoveringFromTooSlow = stats.getSlowFollowers().isNotBackInSync(followerId, lane);
        appliedOffset = lastAppliedOffset;
        receivedOffset = lastAppliedOffset;
        ackLatency = new AckLatencySampler(stats.ackLatency(followerId, lane));
        lag = new LagSampler(stats.lag(followerId, lane));
    }

    void onReceived(long receivedOffset) {
        this.receivedOffset = receivedOffset;
    }

    void onAck(long appliedOffset, boolean inSync, long pendingWrites) {
        this.appliedOffset = appliedOffset;
        this.pendingWrites = pendingWrites;
        this.reportedInSync = inSync;
        if (inSync && recoveringFromTooSlow) {
            recoveringFromTooSlow = false;
            stats.getSlowFollowers().backInSync(followerId, lane);
        }
        lastAckNanos = clock.nanoTime();
        long overdue = overdueOffset;
        if (overdue >= 0 && appliedOffset >= overdue) {
            overdueOffset = -1;
        }
        ackLatency.onAck(appliedOffset);
        lag.onAck(appliedOffset);
    }

    boolean isOverdue() {
        return overdueOffset >= 0;
    }

    /**
     * A {@code SYNC} send stopped waiting for the follower to apply the record at {@code offset}.
     */
    void overdue(long offset) {
        overdueOffset = offset;
        if (appliedOffset >= offset) {
            overdueOffset = -1;
        }
    }

    /**
     * How long the follower has been behind a write it has not applied yet, 0 when it is not.
     */
    long getLagNanos(long nowNanos) {
        return lag.getPendingNanos(nowNanos);
    }

    /**
     * The sender starting a fill; see {@link LagSampler#onFill}.
     */
    void onFill(long tail, long readOffset, long lastReadOffset) {
        lag.onFill(tail, readOffset, lastReadOffset, appliedOffset);
    }

    void onRecordRead(long recordOffset, long nextOffset) {
        lag.onRecordRead(recordOffset, nextOffset);
    }

    /**
     * The sender handing the record to betty, before the send: an ack processed in between would otherwise leave the
     * sample to the next record.
     */
    void onSent(long recordOffset, long nowNanos) {
        ackLatency.onSent(recordOffset, nowNanos);
    }
}
