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

import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.replication.spi.ReplicaSink;
import org.lolaf.staffix.ha.replication.spi.StreamAnswer;
import org.lolaf.staffix.ha.replication.spi.StreamReplicationMode;

/**
 * One of the leader's streams as this follower holds it, kept across connections until the node leads. Any thread.
 */
public interface ReplicaStream {

    String getStreamName();

    StreamReplicationMode getLeaderMode();

    /**
     * Applied into a sink here, rather than refused.
     */
    boolean isReplicated();

    /**
     * Null when not {@link #isReplicated() replicated}.
     */
    @Nullable ReplicaSink getSink();

    /**
     * Why the stream is refused here, unused when it is replicated.
     */
    StreamAnswer getNotReplicatedAnswer();

    /**
     * Holds everything the leader sent of the stream since a snapshot filled it; false when not replicated.
     */
    boolean isInSync();

    /**
     * The leader stopped cleanly after sending every record of the stream, so the copy holds exactly what it last
     * wrote. Holds until the next connection.
     */
    boolean isHandedOver();
}
