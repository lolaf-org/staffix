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
package org.lolaf.staffix.ha.replication.spi;

/**
 * Where the replication times its followers and its writes, each recorder asked for once: as a follower lane
 * connects, or as the node is built. Records nothing by default.
 */
public interface ReplicationMetrics {

    ReplicationMetrics NONE = new ReplicationMetrics() {
    };

    /**
     * On the leader: a follower's round trip, from a batch of records sent to its ack.
     */
    default LatencyRecorder followerAckLatency(String followerId, int lane) {
        return LatencyRecorder.NONE;
    }

    /**
     * On the leader: how far a follower is behind, from a record written to the lane's log to its ack.
     */
    default LatencyRecorder followerLag(String followerId, int lane) {
        return LatencyRecorder.NONE;
    }

    /**
     * On a follower: how long a read's writes into the sinks took after its records were acknowledged as received.
     */
    default LatencyRecorder receivedWriteLatency() {
        return LatencyRecorder.NONE;
    }
}
