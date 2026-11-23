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
package org.lolaf.staffix.ha.replication;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * One follower's connection for one lane, as its leader sees it.
 */
/**
 * One lane of a follower, as its leader sees it.
 */
@Value
@Builder
public class FollowerLaneStatus {

    /**
     * Null until the follower said.
     */
    @Nullable String nodeId;
    int lane;
    String address;
    /**
     * Streaming the log, rather than greeting or closing.
     */
    boolean connected;
    /**
     * Holds every stream of the lane in sync, so a failover to it loses nothing acknowledged.
     */
    boolean inSync;
    /**
     * A stream's snapshot is waiting or on its way.
     */
    boolean snapshotInProgress;
    /**
     * The bytes of the log not sent to this follower yet, -1 before it started sending.
     */
    long unsentBytes;
    /**
     * How long the follower has been behind a record it has not applied yet, 0 when caught up.
     */
    long lagMillis;
    /**
     * What encrypts and authenticates the lane; null while it is not connected or replicates in clear.
     */
    @Nullable String cipher;
}
