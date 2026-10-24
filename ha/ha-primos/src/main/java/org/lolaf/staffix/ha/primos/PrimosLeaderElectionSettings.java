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
package org.lolaf.staffix.ha.primos;

import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lolaf.primos.ClusterConfig;
import org.lolaf.primos.PrimosNode;
import org.lolaf.staffix.api.ha.LeaderElectionSettings;

import java.net.InetSocketAddress;
import java.nio.file.Path;

/**
 * Elects the leader with primos, from exactly one of: a {@link #getNode() node} the application runs, or a node
 * staffix builds from {@link #getCluster() cluster}, {@link #getStateDirectory() stateDirectory} and
 * {@link #getBindAddress() bindAddress}, and starts and stops with the engine.
 *
 * <p>A leader is named by its primos node id, as decimal text: the replication's node id and peers must use it.
 */
@NullMarked
@Getter
@SuperBuilder(toBuilder = true)
public class PrimosLeaderElectionSettings extends LeaderElectionSettings {

    /**
     * A node the application owns: staffix only listens to it, never starts or stops it, so the application's
     * leader-only work and the FIX sessions follow one election. Two nodes in one process would be two elections
     * that can disagree.
     */
    private final @Nullable PrimosNode node;

    /**
     * The cluster of the node staffix runs.
     */
    private final @Nullable ClusterConfig cluster;

    /**
     * Where the node staffix runs keeps its term and vote. Required with {@link #getCluster() cluster}: a node that
     * forgets them across a restart can elect two leaders.
     */
    private final @Nullable Path stateDirectory;

    /**
     * Where the node staffix runs receives its peers' datagrams. Required with {@link #getCluster() cluster}.
     */
    private final @Nullable InetSocketAddress bindAddress;

    /**
     * Signs every datagram of the node staffix runs. Strongly recommended outside a trusted network: without it a
     * forged datagram can make a leader no majority elected.
     */
    private final byte @Nullable [] clusterSecret;
}
