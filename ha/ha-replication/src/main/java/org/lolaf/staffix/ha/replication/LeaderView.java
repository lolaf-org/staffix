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

/**
 * The leader of a following node, and what this node holds of the leader's streams. Any thread.
 */
public interface LeaderView {

    /**
     * Null while the node does not follow.
     */
    @Nullable LeaderStatus getStatus();

    /**
     * Every lane the leader announced is streaming; false while the node does not follow.
     */
    boolean isConnected();

    /**
     * Connected, and holding every stream the leader replicates in sync.
     */
    boolean isInSync();

    /**
     * Every lane the leader announced is streaming encrypted.
     */
    boolean isEncrypted();

    /**
     * Every stream the leader replicates is in sync here, connected or not: what a node about to lead holds. One
     * refused is missing; a node that never followed misses nothing.
     */
    boolean isCopyInSync();

    /**
     * The leader's lane count, from its last WELCOME, 1 before any.
     */
    int getLanes();

    /**
     * Null for a stream the leader never announced, or once the node led.
     */
    @Nullable ReplicaStream getStream(String streamName);
}
