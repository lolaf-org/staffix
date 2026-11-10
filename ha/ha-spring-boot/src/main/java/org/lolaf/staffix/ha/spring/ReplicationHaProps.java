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
package org.lolaf.staffix.ha.spring;

import lombok.Data;
import org.lolaf.staffix.api.metadata.ConfiguresComponent;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Properties for replicating the sessions' stores to a follower engine.
 *
 * <p>Bound from {@code staffix.ha-replication.*}. The leader election is the application context's
 * {@code LeaderElectionSettings} bean, such as the one {@code staffix-ha-primos-spring-boot} declares.
 */
@ConfiguresComponent(ReplicationHaSettings.class)
@Data
@ConfigurationProperties(prefix = "staffix.ha-replication")
public class ReplicationHaProps {

    /**
     * Names this engine to its peer in the replication handshake, and in their logs. With primos, its node id.
     */
    private String nodeId;

    /**
     * Where each node accepts replication while it leads, as host:port by node id, the same map on every node: a node
     * binds its own entry, and connects to the leader's. A leader election that advertises the leader's endpoint
     * overrides the leader's entry.
     */
    private Map<String, String> peers = new LinkedHashMap<>();

    /**
     * Names the pair in the status, the same on both nodes; unset takes the leader election's name, such as primos's
     * application name.
     */
    private String pairName;

    /**
     * The instance id of the engine's monitoring manager publishing the replication's meters, such as the one
     * staffix.monitoring sets up; none when unset.
     */
    private String monitoringInstanceId;

    /**
     * The instance id of the engine's alerts publisher publishing the replication's probes, of component {@code ha};
     * none when unset. The probes of the replicated sessions go to each session's own publisher regardless.
     */
    private String alertsPublisherInstanceId;

    /**
     * Bytes of off-heap memory holding the store events not yet sent to the follower, a power of two; unset keeps
     * 16 MiB. A follower this far behind makes writes wait for it, at most {@code logFullTimeout}; the largest message
     * replicated is half of it, past an eighth in fragments. Size it to several times the largest message, which
     * otherwise takes most of the log.
     */
    private Integer logCapacity;

    /**
     * How many lanes the sessions are split over, each with its own {@code logCapacity} log, sender thread and
     * connection per follower; unset keeps 1. A snapshot, or a follower too slow for one lane, then holds back only
     * that lane's sessions, and the follower applies the lanes in parallel. Costs a log, a thread and a connection
     * each. Read on the leader, which tells its followers.
     */
    private Integer replicationLanes;

    /**
     * How long a follower waits between two attempts to connect to its leader; unset keeps 1s.
     */
    private Duration reconnectInterval;

    /**
     * How often a leader with nothing to send tells its follower where the log ends, so the follower can tell an idle
     * leader from a dead connection and measure how far behind it is; unset keeps 1s.
     */
    private Duration heartbeatInterval;

    /**
     * The leader's write buffers per follower, a power of two of at least 2, carrying batches of writes not yet taken by
     * the socket; unset keeps 4. With all of them in flight the sender waits for one. More help a follower far away;
     * each costs off-heap memory per follower, allocated as first needed: the largest event frame, 64 KiB at least,
     * about 2 MiB with the default {@code logCapacity}.
     */
    private Integer sendBuffers;

    /**
     * The most bytes of a snapshot the leader sends between two batches of the other sessions' writes, a session's
     * longer message going whole anyway; unset keeps 64 KiB. The sender reads each chunk from the session's store, the
     * cost of a snapshot, while the lane's live writes wait. Larger finishes a snapshot sooner under load, smaller
     * delays the other sessions less. Capped at the largest event frame's batch.
     */
    private Integer snapshotChunkBytes;

    /**
     * How the leader's sender, a thread per follower, waits for writes to send. Unset backs off from spinning to
     * parking, and a write wakes it; {@code busy-spin}, at the cost of a core per follower, saves the wake-up.
     */
    private Waiting senderIdleStrategy;

    /**
     * How a {@code SYNC} send waits for its follower's ack, on the session's thread. Unset backs off as the sender's
     * does, and the ack wakes it; {@code busy-spin} sees the ack at once, at the cost of the session thread's core while
     * it waits.
     */
    private Waiting syncAckIdleStrategy;

    /**
     * Threads writing what a follower receives into its stores, its lanes spread over them; unset keeps 0, writing on
     * each lane's network thread. With store writer threads the network thread keeps reading while a store writes, so
     * a {@code SYNC_RECEIVED} send is acknowledged without waiting for the write before it: worth it with a durable
     * follower store, while a memory one pays the hand-off for nothing. Lanes sharing a thread wait for each other's
     * writes, so give one per lane.
     */
    private Integer followerStoreWriterThreads;

    /**
     * False stops the leader changing the follower's session settings stores: they are only compared. Unset replicates.
     */
    private Boolean sessionSettingsReplicated;

    /**
     * Instance ids of the session settings stores the leader replicates to the follower; empty replicates every store
     * that can be, those with a storage identity. The others are only compared. Change a replicated store through the
     * leader only.
     */
    private List<String> replicatedSettingsStores = new ArrayList<>();

    /**
     * How a follower's store writer thread waits for what to write, woken by the network thread that read it; unset
     * backs off. {@code busy-spin} writes at once, at the cost of a core per writer thread.
     */
    private Waiting followerStoreWriterIdleStrategy;

    /**
     * How the replication's network threads wait, the leader's and the follower's: unset keeps betty's default, which
     * blocks in the selector, woken by a system call; {@code busy-spin} saves it, at the cost of a core on each node.
     */
    private Waiting selectStrategy;

    /**
     * How far a node that becomes leader moves the outgoing MsgSeqNum(34) of every replicated session: past what the
     * old leader may have sent without this node receiving it, which the counterparty would refuse as too low. It
     * then asks for the gap and gets a gap fill. Must exceed the messages a session can send while unreplicated; unset
     * keeps 10000, 0 disables it.
     */
    private Long promotionOutgoingSeqNumJump;

    /**
     * How long a leader stopping cleanly waits to send its follower every event before telling it so, which lets the
     * follower take over without moving its seqnums ahead; unset keeps 5s. A follower not reached in time takes over
     * with the jump.
     */
    private Duration handOverTimeout;

    /**
     * How long a switchover waits for a follower ready to lead, its sessions running meanwhile; once it has one, how
     * long it may take from logging them out to leadership leaving this node, past which this node leads them again.
     * Unset keeps 30s. Zero does not wait: a follower not ready now is refused, and {@code handOverTimeout} bounds the
     * rest.
     */
    private Duration switchoverTimeout;

    /**
     * The most a follower may have left to store for a switchover to go ahead: it must store all of it before it can
     * lead, with the sessions down. Unset keeps 1000; never 0 under traffic, whose writes are always in flight.
     */
    private Long switchoverMaxPendingWrites;

    /**
     * How long a connected follower may go without applying a write before the {@code ha-followers} probe raises a
     * major alert: a failover then loses what it has not applied. Unset keeps 5s. Well above a healthy lag, so it means
     * a stalled or overloaded follower rather than a busy one.
     */
    private Duration followerLagAlertThreshold;

    /**
     * How long a follower owing an acknowledgement may stay silent before the leader drops it as hung; it reconnects
     * and is caught up. Unset keeps 10s. A slow follower still acknowledges each read, so this does not drop one that
     * keeps up badly. Zero never drops.
     */
    private Duration followerStallTimeout;

    /**
     * How long a write waits for a follower a whole log behind before that follower is dropped as too slow and caught
     * up by snapshot; unset keeps 1s. The wait holds the FIX IO thread writing, and every session on it, so it stays
     * short; positive, since a log that never waits would lap its followers.
     */
    private Duration logFullTimeout;

    /**
     * How long a follower dropped as too slow is refused before it may resync, since catching it up adds to the load
     * that made it too slow; unset keeps 1s. Doubled at each drop up to a minute, and started over once it stayed in
     * sync that long. Zero never holds a follower off.
     */
    private Duration slowFollowerHoldOff;

    /**
     * Whether an operator may, through staffix, transfer the leadership of the application's primos node or withdraw
     * it from elections, when that node is the primos starter's shared with staffix-ha-primos-spring-boot. Off by
     * default: that leadership is the application's too. Always allowed for a node staffix runs.
     */
    private boolean leadershipControl;

    /**
     * How much sooner than any successor's election this node's leadership stops being valid, when the election is
     * primos: the time to drop the sessions, plus clock drift. Unset keeps 50ms. Less than the cluster's minimum
     * election timeout, which bounds the lease.
     */
    private Duration fencingMargin;

    /**
     * How a replication thread waits for work: as by default, or busy spinning for the lowest {@code SYNC} latency.
     */
    public enum Waiting {
        DEFAULT,
        BUSY_SPIN
    }
}
