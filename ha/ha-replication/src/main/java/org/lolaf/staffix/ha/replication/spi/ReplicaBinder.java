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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.replication.wire.StreamAnswer;
import org.lolaf.staffix.ha.replication.wire.StreamReplicationMode;

/**
 * How a follower holds the streams its leader announces, from its own configuration. Called on a lane's network
 * thread, a stream being announced rarely.
 */
public interface ReplicaBinder {

    /**
     * Sent in HELLO; a leader replicating another application is refused.
     */
    int getApplicationId();

    /**
     * Opens what the stream is applied into, or refuses it. Never called for a stream the leader does not replicate.
     *
     * @param leaderStorageIdentity where the leader stores the stream, null when it does not know: a stream stored in
     *                              the same place here must be refused, as replicating into it would rewrite the
     *                              leader's data
     */
    Binding bind(String streamName, StreamReplicationMode leaderMode, @Nullable String leaderStorageIdentity);

    /**
     * The mode this node's configuration gives the stream, null when it has none: a stream refused as unknown or for
     * a different mode is bound again once this changes.
     */
    @Nullable StreamReplicationMode modeHere(String streamName);

    /**
     * A stream replicated into a sink, or refused.
     */
    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    final class Binding {

        private final @Nullable ReplicaSink sink;
        private final StreamAnswer answer;

        public static Binding replicated(ReplicaSink sink) {
            return new Binding(sink, StreamAnswer.SNAPSHOT_NEEDED);
        }

        /**
         * @param refusal one of the {@link StreamAnswer#isRefusal() refusals}
         */
        public static Binding refused(StreamAnswer refusal) {
            if (!refusal.isRefusal()) {
                throw new IllegalArgumentException(refusal + " is not a refusal");
            }
            return new Binding(null, refusal);
        }
    }
}
