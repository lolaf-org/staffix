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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.lolaf.betty.api.ss.SelectStrategy;
import org.lolaf.betty.api.ss.WakeupSelectStrategy;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.ha.replication.spi.LeaderStreams;
import org.lolaf.staffix.ha.replication.spi.ReplicaSinks;
import org.lolaf.staffix.ha.replication.spi.ReplicationClock;
import org.lolaf.staffix.ha.replication.spi.ReplicationMetrics;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Builds a {@link ReplicationNode}. The node id, the bind address, the streams to replicate and the replica sinks are
 * required; everything else has a default.
 */
@Getter(AccessLevel.PACKAGE)
@Setter
@Accessors(fluent = true, chain = true)
public final class ReplicationNodeBuilder {

    /**
     * Names this node to its peers in the handshake, and in their logs.
     */
    private String nodeId;

    /**
     * Where the node accepts its followers while it leads.
     */
    private InetSocketAddress bindAddress;

    /**
     * What the node replicates while it leads.
     */
    private LeaderStreams streamsToReplicate;

    /**
     * Where the node applies its leader's streams while it follows.
     */
    private ReplicaSinks replicaSinks;

    /**
     * How many lanes the streams are split over, each with its own log, sender thread and connection per follower: a
     * snapshot, or a follower too slow for one lane, then holds back only that lane's streams, and the follower applies
     * the lanes in parallel. Read on the leader, which tells its followers. 1 by default.
     */
    private int lanes;

    /**
     * Bytes of off-heap memory of each lane's log, a power of two. A follower this far behind makes appends wait for
     * it, at most {@link #logFullTimeout(Duration)}; the longest payload is half of it. 16 MiB by default.
     */
    private int logCapacity;

    /**
     * Encrypts the replication with a secret the nodes share; off by default.
     */
    private ReplicationEncryptionSettings encryption;

    /**
     * How long an append waits for a follower a whole log behind before the follower is dropped as too slow, and
     * caught up by snapshot. The wait holds the appending thread, so it stays short; positive, since a log that never
     * waits would lap its followers. 1 s by default.
     */
    private Duration logFullTimeout;

    /**
     * How long a follower dropped as too slow is refused before it may resync, since catching it up adds to the load
     * that made it too slow; doubled at each drop up to a minute. Zero never holds a follower off. 1 s by default.
     */
    private Duration slowFollowerHoldOff;

    /**
     * How often a leader with nothing to send tells its followers where the log ends, so they can tell an idle leader
     * from a dead connection. 1 s by default.
     */
    private Duration heartbeatInterval;

    /**
     * How long a follower owing an ack may stay silent before the leader drops it as hung. Zero never drops. 10 s by
     * default.
     */
    private Duration followerStallTimeout;

    /**
     * The leader's send buffers per follower lane, a power of two of at least 2; with all of them in flight the sender
     * waits for one. More help a follower far away. 4 by default.
     */
    private int sendBuffers;

    /**
     * The most bytes of a snapshot the leader sends between two batches of the lane's other records: larger finishes a
     * snapshot sooner under load, smaller delays the other streams less. 64 KiB by default.
     */
    private int snapshotChunkBytes;

    /**
     * How the leader's sender, a thread per follower lane, waits for records to send, woken by an append. A strategy
     * that ignores {@code wakeup()} parks its whole period, which an ack wait then waits on.
     */
    private Supplier<IdleStrategy> senderIdleStrategy;

    /**
     * How a {@link StreamWriter} waits for its followers' acks, on the appending thread, woken by the acks.
     */
    private Supplier<IdleStrategy> ackIdleStrategy;

    /**
     * How long a follower waits between two attempts to connect to its leader. 1 s by default.
     */
    private Duration reconnectInterval;

    /**
     * How often a follower checks again its sinks' pending writes, which it reports to its leader, and whether a
     * stream refused until its configuration changes may be opened now. 1 s by default.
     */
    private Duration followerCheckInterval;

    /**
     * Threads writing what a follower receives into its sinks, its lanes spread over them; 0, the default, writes on
     * each lane's network thread. With writer threads the network thread keeps reading while a sink writes, so a
     * {@code SYNC_RECEIVED} record is acknowledged without waiting for the write before it.
     */
    private int sinkWriterThreads;

    /**
     * How a sink writer thread waits for what to write, woken by the network thread that read it.
     */
    private Supplier<IdleStrategy> sinkWriterIdleStrategy;

    /**
     * How the network threads wait, the leader's and the follower's.
     */
    private Supplier<SelectStrategy> selectStrategy;

    private ReplicationClock clock;

    private ReplicationMetrics metrics;

    ReplicationNodeBuilder() {
        this.lanes = 1;
        this.logCapacity = 16 << 20;
        this.encryption = ReplicationEncryptionSettings.builder().build();
        this.logFullTimeout = Duration.ofSeconds(1);
        this.slowFollowerHoldOff = Duration.ofSeconds(1);
        this.heartbeatInterval = Duration.ofSeconds(1);
        this.followerStallTimeout = Duration.ofSeconds(10);
        this.sendBuffers = 4;
        this.snapshotChunkBytes = 64 << 10;
        this.senderIdleStrategy = BackoffIdleStrategy::new;
        this.ackIdleStrategy = BackoffIdleStrategy::new;
        this.reconnectInterval = Duration.ofSeconds(1);
        this.followerCheckInterval = Duration.ofSeconds(1);
        this.sinkWriterThreads = 0;
        this.sinkWriterIdleStrategy = BackoffIdleStrategy::new;
        this.selectStrategy = () -> new WakeupSelectStrategy(10);
        this.clock = ReplicationClock.SYSTEM;
        this.metrics = ReplicationMetrics.NONE;
    }

    /**
     * @throws IllegalStateException for a required field missing
     */
    public ReplicationNode build() {
        requireSet(nodeId, "nodeId");
        requireSet(bindAddress, "bindAddress");
        requireSet(streamsToReplicate, "streamsToReplicate");
        requireSet(replicaSinks, "replicaSinks");
        return new ReplicationNodeImpl(this);
    }

    private static void requireSet(Object value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " is required");
        }
    }
}
