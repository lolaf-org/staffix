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
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.ha.wire.CloseReason;
import org.lolaf.staffix.ha.wire.FrameDecoder;
import org.lolaf.staffix.ha.wire.WireFormatException;

import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The follower's connection to the leader, made again whenever it drops, each through a new {@link LeaderLink} on
 * the same {@link Replica}.
 */
@Slf4j
public final class ReplicationReceiver {

    private final InetSocketAddress leaderAddress;
    private final Replica replica;
    private final LongSupplier epoch;
    private final String nodeId;
    private final int maxFrameBytes;
    private final Duration connectionRetry;
    private volatile @Nullable LeaderLink link;
    private IOWorkersGroup ioWorkers;
    private Client client;

    /**
     * @param maxFrameBytes the largest frame the leader sends, see
     *                      {@link org.lolaf.staffix.ha.log.ReplicationLog#getMaxEventFrameBytes()}
     */
    public ReplicationReceiver(InetSocketAddress leaderAddress, Replica replica, LongSupplier epoch, String nodeId,
                               int maxFrameBytes, Duration connectionRetry) {
        this.leaderAddress = leaderAddress;
        this.replica = replica;
        this.epoch = epoch;
        this.nodeId = nodeId;
        this.maxFrameBytes = maxFrameBytes;
        this.connectionRetry = connectionRetry;
    }

    public void start() {
        ioWorkers = IOWorkersGroupSettings.builder().id("staffix-ha-follower").build().newInstance();
        client = ClientBuilder.builder()
                .id("staffix-ha-follower")
                .connectAddress(leaderAddress)
                .connectionRetry(connectionRetry)
                .ioWorkersGroup(ioWorkers)
                .ioSettings(IOSettings.builder()
                        .readBufferSize(Math.max(64 * 1024, maxFrameBytes))
                        .socketOptions(Map.of(StandardSocketOptions.TCP_NODELAY, true))
                        .build())
                .ioEventsListener(new Listener())
                .build().newInstance();
        client.start();
        log.info("Replication follower {} connecting to its leader at {}", nodeId, leaderAddress);
    }

    public void stop(Deadline deadline) {
        if (client != null) {
            client.stop(deadline);
        }
        if (ioWorkers != null) {
            ioWorkers.stop(deadline);
        }
    }

    /**
     * The connection to the leader now, null between connections.
     */
    public @Nullable LeaderLink getLink() {
        return link;
    }

    private final class Listener implements IOEventsListener {

        private final FrameDecoder decoder = new FrameDecoder(maxFrameBytes);

        @Override
        public void onConnected(IOSession session) {
            LeaderLink connected = new LeaderLink(session, replica, epoch, nodeId);
            session.setAttachment(connected);
            link = connected;
            connected.hello();
        }

        @Override
        public void onRead(IOSession session, ByteBuffer message, long localReceiveTimeInNanos) {
            LeaderLink leader = session.getAttachment();
            int index = message.position();
            try {
                int consumed;
                while ((consumed = decoder.decode(message, index, message.limit() - index, leader)) > 0) {
                    index += consumed;
                }
            } catch (WireFormatException e) {
                leader.close(CloseReason.PROTOCOL_ERROR, e.getMessage());
                index = message.limit();
            }
            message.position(index);
            leader.afterRead();
        }

        @Override
        public void onDisconnected(IOSession session) {
            if (session == null) {
                return;
            }
            LeaderLink leader = session.getAttachment();
            if (leader != null) {
                leader.disconnected();
                if (link == leader) {
                    link = null;
                }
            }
        }
    }
}
