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

import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.api.monitoring.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Times how far a follower is behind: from an instant to the ack covering every event written to the log before it,
 * one sample at a time, as {@link AckLatencySampler} does. Unlike a timer, the pending sample's age keeps growing while
 * the follower applies nothing. Sampled by the sender thread, recorded by the connection's IO thread; allocates nothing.
 */
@RequiredArgsConstructor
final class LagSampler {

    private static final long NONE = -1;

    private final Timer timer;
    private long sampledTail = NONE;
    private volatile long sampledNanos;
    /**
     * The start offset of the event ending at or past the sampled tail, which acks name; {@link #NONE} until read.
     */
    private volatile long targetOffset = NONE;
    private volatile boolean pending;

    /**
     * A fill starting: samples the log's tail unless a sample is pending or nothing was written since the last one.
     *
     * @param readOffset      where the sender reads next
     * @param lastReadOffset  the start of the last event the sender read, -1 before any
     * @param appliedOffset   the start of the last event the follower acked, -1 before any
     */
    void onFill(long tail, long readOffset, long lastReadOffset, long appliedOffset) {
        if (pending || tail == sampledTail) {
            return;
        }
        sampledTail = tail;
        if (tail > readOffset) {
            targetOffset = NONE;
        } else if (lastReadOffset > appliedOffset) {
            targetOffset = lastReadOffset;
        } else {
            return;
        }
        sampledNanos = System.nanoTime();
        pending = true;
    }

    /**
     * An event the sender framed for the follower, from {@code eventOffset} up to {@code nextOffset}.
     */
    void onEventRead(long eventOffset, long nextOffset) {
        if (pending && targetOffset == NONE && nextOffset >= sampledTail) {
            targetOffset = eventOffset;
        }
    }

    void onAck(long appliedOffset) {
        long target = targetOffset;
        if (pending && target != NONE && appliedOffset >= target) {
            timer.record(System.nanoTime() - sampledNanos, TimeUnit.NANOSECONDS);
            targetOffset = NONE;
            pending = false;
        }
    }

    /**
     * How long the pending sample has waited, 0 when none is: the follower is that far behind at least.
     */
    long getPendingNanos(long nowNanos) {
        return pending ? nowNanos - sampledNanos : 0;
    }
}
