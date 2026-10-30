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
package org.lolaf.staffix.ha.leader;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.betty.api.Server;
import org.lolaf.betty.api.ServerBuilder;
import org.lolaf.betty.api.io.IOEventsListener;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.betty.api.io.IOWorkersGroup;
import org.lolaf.betty.api.settings.IOBufferPoolSettings;
import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.betty.api.settings.IOWorkersGroupSettings;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.ha.ReplicatedSessions;
import org.lolaf.staffix.ha.log.ReplicationLog;
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
 * The leader's replication server: accepts its follower, and streams the log to it through a {@link FollowerLink}.
 */
@Slf4j
public final class ReplicationPublisher {

    /**
     * Frames from a follower are small: a HELLO is the largest, its node id at most 64 KiB.
     */
    private static final int MAX_INBOUND_FRAME_BYTES = 128 * 1024;
    private static final int BATCH_BUFFERS = 4;

    private final InetSocketAddress bindAddress;
    private final ReplicationLog replicationLog;
    private final ReplicatedSessions sessions;
    private final LongSupplier epoch;
    private final String leaderId;
    private final Duration heartbeatInterval;
    private final int snapshotMaxMessages;
    private final Followers followers;
    private final ReplicationStats stats;
    private IOWorkersGroup ioWorkers;
    private Server server;

    public ReplicationPublisher(InetSocketAddress bindAddress, ReplicationLog log, ReplicatedSessions sessions, Followers followers,
                                LongSupplier epoch, String leaderId, Duration heartbeatInterval, int snapshotMaxMessages,
                                ReplicationStats stats) {
        this.stats = stats;
        this.snapshotMaxMessages = snapshotMaxMessages;
        this.followers = followers;
        this.bindAddress = bindAddress;
        this.replicationLog = log;
        this.sessions = sessions;
        this.epoch = epoch;
        this.leaderId = leaderId;
        this.heartbeatInterval = heartbeatInterval;
    }

    private static void launchSender(Runnable loop) {
        Thread thread = new Thread(loop, "staffix-ha-sender");
        thread.setDaemon(true);
        thread.start();
    }

    public void start() {
        int batchBytes = FollowerLink.batchBytesFor(replicationLog);
        ioWorkers = IOWorkersGroupSettings.builder().id("staffix-ha-leader").build().newInstance();
        server = ServerBuilder.builder()
                .id("staffix-ha-leader")
                .bindAddress(bindAddress)
                .ioWorkersGroup(ioWorkers)
                .ioSettings(IOSettings.builder()
                        .readBufferSize(MAX_INBOUND_FRAME_BYTES)
                        .socketOptions(Map.of(StandardSocketOptions.TCP_NODELAY, true))
                        .writeIoBufferPoolSettings(IOBufferPoolSettings.builder()
                                .zone(IOBufferPoolSettings.IOBufferPoolZone.builder().bufferSize(256).poolSize(64).build())
                                .zone(IOBufferPoolSettings.IOBufferPoolZone.builder().bufferSize(batchBytes).poolSize(BATCH_BUFFERS).build())
                                .build())
                        .build())
                .ioEventsListener(new Listener())
                .build().newInstance();
        server.start();
        log.info("Replication leader listening on {}", bindAddress);
    }

    /**
     * Hands over to every follower, for a leader stopping cleanly; see {@link FollowerLink#handOver(Deadline)}.
     *
     * @return whether a follower was told
     */
    public boolean handOver(Deadline deadline) {
        boolean told = false;
        for (FollowerLink link : followers.all()) {
            told |= link.handOver(deadline);
        }
        return told;
    }

    public void stop(Deadline deadline) {
        followers.all().forEach(FollowerLink::disconnected);
        if (server != null) {
            server.stop(deadline);
        }
        if (ioWorkers != null) {
            ioWorkers.stop(deadline);
        }
    }

    private final class Listener implements IOEventsListener {

        private final FrameDecoder decoder = new FrameDecoder(MAX_INBOUND_FRAME_BYTES);

        @Override
        public void onConnected(IOSession session) {
            FollowerLink link = new FollowerLink(session, replicationLog, sessions, epoch, leaderId, heartbeatInterval, snapshotMaxMessages,
                    stats, ReplicationPublisher::launchSender);
            session.setAttachment(link);
            followers.add(link);
        }

        @Override
        public void onRead(IOSession session, ByteBuffer message, long localReceiveTimeInNanos) {
            FollowerLink link = session.getAttachment();
            int index = message.position();
            try {
                int consumed;
                while ((consumed = decoder.decode(message, index, message.limit() - index, link)) > 0) {
                    index += consumed;
                }
            } catch (WireFormatException e) {
                link.close(CloseReason.PROTOCOL_ERROR, e.getMessage());
                index = message.limit();
            }
            message.position(index);
        }

        @Override
        public void onDisconnected(IOSession session) {
            if (session == null) {
                return;
            }
            FollowerLink link = session.getAttachment();
            if (link != null) {
                link.disconnected();
                followers.remove(link);
            }
        }

        @Override
        public void onWatermarkEvent(IOSession session, boolean highWatermarkReached, long bytesLeftToWrite) {
            FollowerLink link = session.getAttachment();
            link.pause(highWatermarkReached);
        }
    }
}
