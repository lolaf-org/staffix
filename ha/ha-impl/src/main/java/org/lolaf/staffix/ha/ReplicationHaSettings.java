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
package org.lolaf.staffix.ha;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.experimental.SuperBuilder;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.ha.HaSettings;
import org.lolaf.staffix.api.ha.LeaderElectionSettings;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;

/**
 * Replicates the stores of the sessions asking for it to a standby engine over TCP.
 */
@NullMarked
@Getter
@SuperBuilder(toBuilder = true)
public class ReplicationHaSettings extends HaSettings {

    /**
     * Decides which engine leads, and so which one runs the sessions while the other replicates.
     */
    private final @Nullable LeaderElectionSettings leaderElection;

    /**
     * The instance id of the engine's monitoring manager publishing the replication's meters; none when unset.
     */
    private final @Nullable String monitoringInstanceId;

    /**
     * Bytes of off-heap memory holding the store events not yet sent to the follower, a power of two. A follower
     * falling this far behind is caught up by snapshot instead; the largest message replicated is an eighth of it.
     */
    @Builder.Default
    private final int logCapacity = 16 << 20;

    /**
     * Names this engine to its peer in the replication handshake, and in their logs.
     */
    @Builder.Default
    private final String nodeId = "default";

    /**
     * Where each node accepts replication while it leads, by {@link #getNodeId() node id}, the same map on every node:
     * a node binds its own entry, and connects to the leader's. A leader election that advertises the leader's
     * endpoint overrides the leader's entry.
     */
    @Singular
    private final Map<String, InetSocketAddress> peers;

    /**
     * How long a follower waits between two attempts to connect to its leader.
     */
    @Builder.Default
    private final Duration reconnectInterval = Duration.ofSeconds(1);

    /**
     * How often a leader with nothing to send tells its follower where the log ends, so the follower can tell an idle
     * leader from a dead connection and measure how far behind it is.
     */
    @Builder.Default
    private final Duration heartbeatInterval = Duration.ofSeconds(1);

    /**
     * The most recent messages of a session a snapshot copies to a follower catching up. A resend asking for older
     * ones, after a failover to that follower, is answered with a gap fill.
     */
    @Builder.Default
    private final int snapshotMaxMessages = 10_000;

    /**
     * How far a node that becomes leader moves the outgoing MsgSeqNum(34) of every replicated session: past what the
     * old leader may have sent without this node receiving it, which the counterparty would refuse as too low. It
     * then asks for the gap and gets a gap fill. Must exceed the messages a session can send while unreplicated.
     */
    @Builder.Default
    private final long promotionOutgoingSeqNumJump = 10_000;

    /**
     * How long a leader stopping cleanly waits to send its follower every event before telling it so, which lets the
     * follower take over without moving its seqnums ahead. A follower not reached in time takes over with the jump.
     */
    @Builder.Default
    private final Duration handOverTimeout = Duration.ofSeconds(5);
}
