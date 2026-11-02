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
import org.lolaf.betty.api.ss.SelectStrategy;
import org.lolaf.betty.api.ss.WakeupSelectStrategy;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.api.ha.HaSettings;
import org.lolaf.staffix.api.ha.LeaderElectionSettings;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

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
     * The instance id of the engine's alerts publisher publishing the replication's probes, of component {@code ha};
     * none when unset. The probes of the replicated sessions go to each session's own publisher regardless.
     */
    private final @Nullable String alertsPublisherInstanceId;

    /**
     * Bytes of off-heap memory holding the store events not yet sent to the follower, a power of two. A follower this
     * far behind makes writes wait for it, at most {@link #getLogFullTimeout() logFullTimeout}; the largest message
     * replicated is half of it, past an eighth in fragments. Size it to several times the largest message, which
     * otherwise takes most of the log.
     */
    @Builder.Default
    private final int logCapacity = 16 << 20;

    /**
     * How many links the sessions are split over, each with its own {@link #getLogCapacity() logCapacity} log, sender
     * thread and connection per follower: a snapshot, or a follower too slow for one link, then holds back only that
     * link's sessions, and the follower applies the links in parallel. Costs a log, a thread and a connection each.
     * Read on the leader, which tells its followers.
     */
    @Builder.Default
    private final int replicationLinks = 1;

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
     * How the leader's sender, a thread per follower, waits for writes to send. The default backs off from spinning to
     * parking, and a write wakes it; a busy spin, at the cost of a core per follower, saves the wake-up. A strategy
     * that ignores {@code wakeup()} parks its whole period, which a {@code SYNC} send then waits on.
     */
    @Builder.Default
    private final Supplier<IdleStrategy> senderIdleStrategy = BackoffIdleStrategy::new;

    /**
     * How a {@code SYNC} send waits for its follower's ack, on the session's thread. The default backs off as the
     * sender's does, and the ack wakes it; a busy spin sees the ack at once, at the cost of the session thread's core
     * while it waits.
     */
    @Builder.Default
    private final Supplier<IdleStrategy> syncAckIdleStrategy = BackoffIdleStrategy::new;

    /**
     * How the replication's network threads wait, the leader's and the follower's: betty's default blocks in the
     * selector, woken by a system call; busy spinning on an {@code IdleStrategySelectStrategy} saves it, at the cost of
     * a core on each node.
     */
    @Builder.Default
    private final Supplier<SelectStrategy> selectStrategy = () -> new WakeupSelectStrategy(10);

    /**
     * The leader's write buffers per follower, a power of two of at least 2, carrying batches of writes not yet taken by
     * the socket; with all of them in flight the sender waits for one. More help a follower far away; each costs
     * off-heap memory per follower, allocated as first needed: the largest event frame, 64 KiB at least, about 2 MiB
     * with the default {@link #getLogCapacity() logCapacity}.
     */
    @Builder.Default
    private final int sendBuffers = 4;

    /**
     * The most bytes of a snapshot the leader sends between two batches of the other sessions' writes, a session's
     * longer message going whole anyway. The sender reads each chunk from the session's store, the cost of a snapshot,
     * while the link's live writes wait: 64 KiB takes about 0.2 ms from a file store. Larger finishes a snapshot sooner
     * under load, smaller delays the other sessions less. Capped at the largest event frame's batch.
     */
    @Builder.Default
    private final int snapshotChunkBytes = 64 << 10;

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

    /**
     * How long a switchover may take, from waiting for a follower in sync to leadership leaving this node; past it,
     * this node leads its sessions again.
     */
    @Builder.Default
    private final Duration switchoverTimeout = Duration.ofSeconds(30);

    /**
     * How long a connected follower may go without applying a write before the {@code ha-followers} probe raises a
     * major alert: a failover then loses what it has not applied. Well above a healthy lag, so it means a stalled or
     * overloaded follower rather than a busy one.
     */
    @Builder.Default
    private final Duration followerLagAlertThreshold = Duration.ofSeconds(5);

    /**
     * How long a follower owing an acknowledgement may stay silent before the leader drops it as hung; it reconnects
     * and is caught up. A slow follower still acknowledges each read, so this does not drop one that keeps up badly.
     * Zero never drops.
     */
    @Builder.Default
    private final Duration followerStallTimeout = Duration.ofSeconds(10);

    /**
     * How long a write waits for a follower a whole {@link #getLogCapacity() log} behind before that follower is
     * dropped as too slow and caught up by snapshot. The wait holds the FIX IO thread writing, and every session on
     * it, so it stays short; positive, since a log that never waits would lap its followers.
     */
    @Builder.Default
    private final Duration logFullTimeout = Duration.ofSeconds(1);

    /**
     * How long a follower dropped as too slow is refused before it may resync, since catching it up adds to the load
     * that made it too slow; doubled at each drop up to a minute, and started over once it stayed in sync that long.
     * Zero never holds a follower off.
     */
    @Builder.Default
    private final Duration slowFollowerHoldOff = Duration.ofSeconds(1);
}
