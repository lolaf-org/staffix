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
import org.lolaf.betty.api.ss.SelectStrategy;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.ha.replication.encryption.KeyExchange;
import org.lolaf.staffix.ha.replication.follower.FollowerStreams;
import org.lolaf.staffix.ha.replication.follower.ReplicationClient;
import org.lolaf.staffix.ha.replication.leader.Followers;
import org.lolaf.staffix.ha.replication.leader.ReplicationServer;
import org.lolaf.staffix.ha.replication.leader.ReplicationStats;
import org.lolaf.staffix.ha.replication.leader.SlowFollowers;
import org.lolaf.staffix.ha.replication.leader.StreamLogWriter;
import org.lolaf.staffix.ha.replication.log.ReplicationLogs;
import org.lolaf.staffix.ha.replication.spi.LeaderStreams;
import org.lolaf.staffix.ha.replication.spi.ReplicationClock;
import org.lolaf.staffix.ha.replication.spi.ReplicationMetrics;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Owns what lives as long as the node, its logs, followers and the streams it holds as a follower, and starts a
 * server to lead or a client to follow.
 */
final class ReplicationNodeImpl implements ReplicationNode {

    private final String nodeId;
    private final InetSocketAddress bindAddress;
    private final LeaderStreams streamsToReplicate;
    private final Duration logFullTimeout;
    private final Duration heartbeatInterval;
    private final Duration followerStallTimeout;
    private final int sendBuffers;
    private final int snapshotChunkBytes;
    private final Supplier<IdleStrategy> senderIdleStrategy;
    private final Supplier<IdleStrategy> ackIdleStrategy;
    private final Duration reconnectInterval;
    private final Duration followerCheckInterval;
    private final int sinkWriterThreads;
    private final Supplier<IdleStrategy> sinkWriterIdleStrategy;
    private final Supplier<SelectStrategy> selectStrategy;
    private final ReplicationClock clock;
    private final KeyExchange keyExchange;
    private final ReplicationLogs logs;
    private final Followers followers;
    private final ReplicationStats stats;
    private final FollowerStreams followerStreams;
    private final FollowersView followersView;
    private final LeaderView leaderView;
    private volatile @Nullable ReplicationServer server;
    private volatile @Nullable ReplicationClient client;

    ReplicationNodeImpl(ReplicationNodeBuilder builder) {
        this.nodeId = builder.nodeId();
        this.bindAddress = builder.bindAddress();
        this.streamsToReplicate = builder.streamsToReplicate();
        this.logFullTimeout = builder.logFullTimeout();
        this.heartbeatInterval = builder.heartbeatInterval();
        this.followerStallTimeout = builder.followerStallTimeout();
        this.sendBuffers = builder.sendBuffers();
        this.snapshotChunkBytes = builder.snapshotChunkBytes();
        this.senderIdleStrategy = builder.senderIdleStrategy();
        this.ackIdleStrategy = builder.ackIdleStrategy();
        this.reconnectInterval = builder.reconnectInterval();
        this.followerCheckInterval = builder.followerCheckInterval();
        this.sinkWriterThreads = builder.sinkWriterThreads();
        this.sinkWriterIdleStrategy = builder.sinkWriterIdleStrategy();
        this.selectStrategy = builder.selectStrategy();
        this.clock = builder.clock();
        this.keyExchange = new KeyExchange(builder.encryption());
        this.logs = new ReplicationLogs(builder.lanes(), builder.logCapacity());
        this.followers = new Followers();
        ReplicationMetrics metrics = builder.metrics();
        this.stats = new ReplicationStats(new SlowFollowers(builder.slowFollowerHoldOff(), clock));
        stats.ackLatencyTimers(metrics::followerAckLatency);
        stats.lagTimers(metrics::followerLag);
        this.followerStreams = new FollowerStreams(builder.replicaSinks());
        followerStreams.setReceivedWriteLatency(metrics.receivedWriteLatency());
        this.followersView = new FollowersViewImpl(followers, stats, logs, streamsToReplicate);
        this.leaderView = new LeaderViewImpl(() -> client, followerStreams);
    }

    @Override
    public synchronized void lead(long epoch, Deadline deadline) {
        standDown(deadline);
        followerStreams.close(deadline);
        ReplicationServer started = new ReplicationServer(bindAddress, logs, streamsToReplicate, followers, () -> epoch,
                nodeId, heartbeatInterval, followerStallTimeout, sendBuffers, snapshotChunkBytes, senderIdleStrategy,
                selectStrategy.get(), stats, clock, keyExchange);
        started.start();
        server = started;
    }

    @Override
    public synchronized void follow(InetSocketAddress leaderAddress, LongSupplier epoch, Deadline deadline) {
        standDown(deadline);
        ReplicationClient started = new ReplicationClient(leaderAddress, followerStreams, epoch, nodeId,
                logs.getMaxRecordFrameBytes(), reconnectInterval, selectStrategy.get(), sinkWriterThreads,
                sinkWriterIdleStrategy, followerCheckInterval, keyExchange);
        started.start();
        client = started;
    }

    @Override
    public boolean handOver(Deadline deadline) {
        ReplicationServer leading = server;
        return leading != null && leading.handOver(deadline);
    }

    @Override
    public synchronized void standDown(Deadline deadline) {
        ReplicationServer leading = server;
        if (leading != null) {
            server = null;
            leading.stop(deadline);
        }
        ReplicationClient following = client;
        if (following != null) {
            client = null;
            following.stop(deadline);
        }
    }

    @Override
    public synchronized void close(Deadline deadline) {
        standDown(deadline);
        followerStreams.close(deadline);
    }

    @Override
    public boolean isLeading() {
        return server != null;
    }

    @Override
    public boolean isFollowing() {
        return client != null;
    }

    @Override
    public StreamWriter writer(int streamKey) {
        return new StreamLogWriter(streamKey, logs.forStream(streamKey), followers, logFullTimeout,
                ackIdleStrategy.get());
    }

    @Override
    public void reannounce(int streamKey) {
        followers.reannounce(streamKey);
    }

    @Override
    public void resync(int streamKey) {
        followers.resync(streamKey);
    }

    @Override
    public FollowersView followers() {
        return followersView;
    }

    @Override
    public LeaderView leader() {
        return leaderView;
    }

    @Override
    public int getLanes() {
        return logs.count();
    }

    @Override
    public long getLogBytesWritten() {
        return logs.getBytesWritten();
    }

    @Override
    public String describeCipher() {
        return keyExchange.describeCipher();
    }

    @Override
    public boolean cipherAllocatesOnEveryFrame() {
        return keyExchange.allocatesOnEveryFrame();
    }
}
