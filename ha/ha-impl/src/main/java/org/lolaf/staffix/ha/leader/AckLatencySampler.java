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
 * Times a follower's round trip, from an event handed to betty to the ack covering it, one event at a time: an ack
 * covers every event before it, so timing them all would need a queue, while one sample per round trip is enough for
 * the latency's distribution. Sampled by the sender thread, recorded by the connection's IO thread; allocates nothing.
 */
@RequiredArgsConstructor
final class AckLatencySampler {

    private final Timer timer;
    private volatile long sampledOffset = -1;
    private long sentNanos;

    /**
     * Samples the event unless one is still waiting for its ack.
     */
    void onSent(long eventOffset, long nowNanos) {
        if (sampledOffset < 0) {
            sentNanos = nowNanos;
            sampledOffset = eventOffset;
        }
    }

    void onAck(long appliedOffset) {
        long offset = sampledOffset;
        if (offset >= 0 && appliedOffset >= offset) {
            timer.record(System.nanoTime() - sentNanos, TimeUnit.NANOSECONDS);
            sampledOffset = -1;
        }
    }
}
