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
package org.lolaf.staffix.ha.follower;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.betty.api.Client;
import org.lolaf.betty.api.ClientBuilder;
import org.lolaf.betty.api.io.IOEventsListener;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.betty.api.io.IOWorkersGroup;
import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.betty.api.settings.IOWorkersGroupSettings;
import org.lolaf.betty.api.ss.SelectStrategy;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.ha.wire.FrameDecoder;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The follower's connections to the leader, one per replication link the leader announces, each made again whenever
 * it drops through a new {@link LeaderLink} on the same {@link Replica}. Link 0 connects first; its WELCOME, or any
 * link's, says how many the leader has, and the others are opened or closed to match. Each link has its own IO
 * thread, so the links are applied in parallel.
 */
@Slf4j
public final class ReplicationReceiver {

    private static final Duration LINK_STOP = Duration.ofSeconds(1);

    private final InetSocketAddress leaderAddress;
    private final Replica replica;
    private final LongSupplier epoch;
    private final String nodeId;
    private final int maxFrameBytes;
    private final Duration connectionRetry;
    private final SelectStrategy selectStrategy;
    private volatile LinkConnection[] connections = new LinkConnection[0];
    private boolean stopped;

    /**
     * @param maxFrameBytes the largest frame the leader sends, see
     *                      {@link org.lolaf.staffix.ha.log.ReplicationLog#getMaxEventFrameBytes()}
     */
    public ReplicationReceiver(InetSocketAddress leaderAddress, Replica replica, LongSupplier epoch, String nodeId,
                               int maxFrameBytes, Duration connectionRetry, SelectStrategy selectStrategy) {
        this.selectStrategy = selectStrategy;
        this.leaderAddress = leaderAddress;
        this.replica = replica;
        this.epoch = epoch;
        this.nodeId = nodeId;
        this.maxFrameBytes = maxFrameBytes;
        this.connectionRetry = connectionRetry;
    }

    public synchronized void start() {
        connections = new LinkConnection[]{new LinkConnection(0)};
        log.info("Replication follower {} connecting to its leader at {}", nodeId, leaderAddress);
    }

    public synchronized void stop(Deadline deadline) {
        stopped = true;
        for (LinkConnection connection : connections) {
            connection.stop(deadline);
        }
    }

    /**
     * Called on the IO thread of a link below the count, so it never stops its caller.
     */
    private synchronized void linksAnnounced(int links) {
        replica.setLinks(links);
        LinkConnection[] current = connections;
        if (stopped || links == current.length) {
            return;
        }
        LinkConnection[] resized = Arrays.copyOf(current, links);
        for (int link = current.length; link < links; link++) {
            resized[link] = new LinkConnection(link);
        }
        for (int link = links; link < current.length; link++) {
            current[link].stop(Deadline.of(LINK_STOP));
        }
        connections = resized;
        log.info("Replication follower {} replicates over {} links from its leader at {}", nodeId, links, leaderAddress);
    }

    /**
     * The link's connection to the leader now, null between connections or for a link not opened.
     */
    public @Nullable LeaderLink getLink(int link) {
        LinkConnection[] current = connections;
        return link < current.length ? current[link].leader : null;
    }

    public HaStatus.Leader status() {
        LeaderLink first = getLink(0);
        return HaStatus.Leader.builder()
                .leaderId(first == null ? null : first.getLeaderId())
                .address(String.valueOf(leaderAddress))
                .connected(isConnected())
                .inSync(isInSync())
                .build();
    }

    /**
     * Every link the leader announced is streaming.
     */
    public boolean isConnected() {
        LinkConnection[] current = connections;
        for (LinkConnection connection : current) {
            LeaderLink leader = connection.leader;
            if (leader == null || leader.getState() != LeaderLink.State.STREAMING) {
                return false;
            }
        }
        return current.length > 0;
    }

    public boolean isInSync() {
        return isConnected() && replica.isComplete();
    }

    private final class LinkConnection implements IOEventsListener {

        private final int link;
        private final FrameDecoder decoder = new FrameDecoder(maxFrameBytes);
        private final IOWorkersGroup ioWorkers;
        private final Client client;
        private volatile @Nullable LeaderLink leader;

        LinkConnection(int link) {
            this.link = link;
            String id = "staffix-ha-follower-" + link;
            ioWorkers = IOWorkersGroupSettings.builder().id(id)
                    .ioThreadGroup(IOWorkersGroupSettings.IOThreadGroup.builder().selectStrategy(selectStrategy).build())
                    .build().newInstance();
            client = ClientBuilder.builder()
                    .id(id)
                    .connectAddress(leaderAddress)
                    .connectionRetry(connectionRetry)
                    .ioWorkersGroup(ioWorkers)
                    .ioSettings(IOSettings.builder()
                            .readBufferSize(WireFormat.bufferBytes(maxFrameBytes))
                            .socketOptions(Map.of(StandardSocketOptions.TCP_NODELAY, true))
                            .build())
                    .ioEventsListener(this)
                    .build().newInstance();
            client.start();
        }

        void stop(Deadline deadline) {
            client.stop(deadline);
            ioWorkers.stop(deadline);
        }

        @Override
        public void onConnected(IOSession session) {
            LeaderLink connected = new LeaderLink(session, replica, epoch, nodeId, link, ReplicationReceiver.this::linksAnnounced);
            session.setAttachment(connected);
            leader = connected;
            connected.hello();
        }

        @Override
        public void onRead(IOSession session, ByteBuffer message, long localReceiveTimeInNanos) {
            session.<LeaderLink>getAttachment().read(message, decoder);
        }

        @Override
        public void onDisconnected(IOSession session) {
            if (session == null) {
                return;
            }
            LeaderLink disconnected = session.getAttachment();
            if (disconnected != null) {
                disconnected.disconnected();
                if (leader == disconnected) {
                    leader = null;
                }
            }
        }
    }
}
