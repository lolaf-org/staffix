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

import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.replication.wire.StreamReplicationMode;

/**
 * One stream as its leader announces and snapshots it. Its mode and storage identity may change as the application
 * reconfigures it; the leader announces it again then. Any thread.
 */
public interface LeaderStream {

    int getKey();

    /**
     * What a follower finds its own copy of the stream by.
     */
    String getStreamName();

    StreamReplicationMode getStreamReplicationMode();

    default boolean isReplicated() {
        return getStreamReplicationMode() != StreamReplicationMode.NONE;
    }

    /**
     * Identifies where the leader stores the stream, so a follower storing into the same place refuses it: replicating
     * into it would rewrite the leader's data. Null when unknown, which a follower refuses too.
     */
    @Nullable String getStorageIdentity();

    /**
     * Called as a snapshot of the stream starts, on the sender thread of the follower lane that needs it.
     */
    SnapshotSource newSnapshotSource();
}
