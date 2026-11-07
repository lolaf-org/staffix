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
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
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
import java.util.function.Supplier;

/**
 * The follower's connections to the leader, one per replication lane the leader announces, each made again whenever
 * it drops through a new {@link LeaderConnection} on the same {@link ReplicaSessions}. Lane 0 connects first; its
 * WELCOME, or any lane's, says how many the leader has, and the others are opened or closed to match. Each lane has its
 * own IO thread, so the lanes are applied in parallel.
 */
@Slf4j
public final class ReplicationClient {

    private static final Duration LANE_STOP = Duration.ofSeconds(1);

    private final InetSocketAddress leaderAddress;
    private final ReplicaSessions replicaSessions;
    private final LongSupplier nodeEpoch;
    private final String nodeId;
    private final int maxFrameBytes;
    private final Duration connectionRetry;
    private final SelectStrategy selectStrategy;
    private final @Nullable StoreWriterThreads storeWriterThreads;
    private volatile LaneClient[] laneClients = new LaneClient[0];
    private boolean stopped;

    /**
     * @param maxFrameBytes the largest frame the leader sends, see
     *                      {@link org.lolaf.staffix.ha.log.ReplicationLog#getMaxEventFrameBytes()}
     */
    public ReplicationClient(InetSocketAddress leaderAddress, ReplicaSessions replicaSessions, LongSupplier nodeEpoch,
                             String nodeId, int maxFrameBytes, Duration connectionRetry, SelectStrategy selectStrategy) {
        this(leaderAddress, replicaSessions, nodeEpoch, nodeId, maxFrameBytes, connectionRetry, selectStrategy, 0,
                BackoffIdleStrategy::new);
    }

    /**
     * @param storeWriterThreads threads writing what the lanes read into the stores, 0 to write on the lanes' network
     *                           threads
     */
    public ReplicationClient(InetSocketAddress leaderAddress, ReplicaSessions replicaSessions, LongSupplier nodeEpoch,
                             String nodeId, int maxFrameBytes, Duration connectionRetry, SelectStrategy selectStrategy,
                             int storeWriterThreads, Supplier<IdleStrategy> storeWriterIdleStrategy) {
        this.storeWriterThreads = storeWriterThreads == 0 ? null
                : new StoreWriterThreads(storeWriterThreads, storeWriterIdleStrategy, nodeId);
        this.selectStrategy = selectStrategy;
        this.leaderAddress = leaderAddress;
        this.replicaSessions = replicaSessions;
        this.nodeEpoch = nodeEpoch;
        this.nodeId = nodeId;
        this.maxFrameBytes = maxFrameBytes;
        this.connectionRetry = connectionRetry;
    }

    public synchronized void start() {
        laneClients = new LaneClient[]{new LaneClient(0)};
        log.info("Replication follower {} connecting to its leader at {}", nodeId, leaderAddress);
    }

    /**
     * Stops reading, then waits, within the deadline, for each lane to write what it read: an event acknowledged as
     * received is written before a promotion starts the sessions.
     */
    public synchronized void stop(Deadline deadline) {
        stopped = true;
        for (LaneClient laneClient : laneClients) {
            laneClient.stop(deadline, true);
        }
        if (storeWriterThreads != null) {
            storeWriterThreads.stop();
        }
    }

    /**
     * Called on the IO thread of a lane below the count, so it never stops its caller.
     */
    private synchronized void lanesAnnounced(int lanes) {
        replicaSessions.setLanes(lanes);
        LaneClient[] current = laneClients;
        if (stopped || lanes == current.length) {
            return;
        }
        LaneClient[] resized = Arrays.copyOf(current, lanes);
        for (int lane = current.length; lane < lanes; lane++) {
            resized[lane] = new LaneClient(lane);
        }
        for (int lane = lanes; lane < current.length; lane++) {
            current[lane].stop(Deadline.of(LANE_STOP), false);
        }
        laneClients = resized;
        log.info("Replication follower {} replicates over {} lanes from its leader at {}", nodeId, lanes, leaderAddress);
    }

    /**
     * The lane's connection to the leader now, null between connections or for a lane not opened.
     */
    public @Nullable LeaderConnection getConnection(int lane) {
        LaneClient[] current = laneClients;
        return lane < current.length ? current[lane].connection : null;
    }

    public HaStatus.Leader getStatus() {
        LeaderConnection first = getConnection(0);
        return HaStatus.Leader.builder()
                .leaderId(first == null ? null : first.getLeaderId())
                .address(String.valueOf(leaderAddress))
                .connected(isConnected())
                .inSync(isInSync())
                .build();
    }

    /**
     * Every lane the leader announced is streaming.
     */
    public boolean isConnected() {
        LaneClient[] current = laneClients;
        for (LaneClient laneClient : current) {
            LeaderConnection connection = laneClient.connection;
            if (connection == null || connection.getState() != LeaderConnection.State.STREAMING) {
                return false;
            }
        }
        return current.length > 0;
    }

    public boolean isInSync() {
        return isConnected() && replicaSessions.isInSync();
    }

    /**
     * One lane's client, connecting to the leader again whenever its connection drops.
     */
    private final class LaneClient implements IOEventsListener {

        private final int lane;
        private final FrameDecoder decoder = new FrameDecoder(maxFrameBytes);
        private final IOWorkersGroup ioWorkers;
        private final Client client;
        private volatile @Nullable LeaderConnection connection;
        private final @Nullable LaneWriteQueue writeQueue;

        LaneClient(int lane) {
            this.lane = lane;
            this.writeQueue = storeWriterThreads == null ? null
                    : new LaneWriteQueue(lane, maxFrameBytes, replicaSessions, storeWriterThreads.forLane(lane));
            String id = "staffix-ha-follower-" + lane;
            ioWorkers = IOWorkersGroupSettings.builder().id(id)
                    .ioThreadGroup(IOWorkersGroupSettings.IOThreadGroup.builder().selectStrategy(selectStrategy).build())
                    .build().newInstance();
            client = ClientBuilder.builder()
                    .id(id)
                    .connectAddress(leaderAddress)
                    .connectionRetry(connectionRetry)
                    .ioWorkersGroup(ioWorkers)
                    .ioSettings(IOSettings.builder()
                            .readBufferSize(WireFormat.READ_BUFFER_BYTES)
                            .maxReadBufferSize(WireFormat.bufferBytes(maxFrameBytes))
                            .socketOptions(Map.of(StandardSocketOptions.TCP_NODELAY, true))
                            .build())
                    .ioEventsListener(this)
                    .build().newInstance();
            client.start();
        }

        /**
         * @param drain whether to wait, within the deadline, for the lane's store writer thread to write what was read;
         *              not when the lane is dropped from a store writer thread, which may be the one to drain it
         */
        void stop(Deadline deadline, boolean drain) {
            client.stop(deadline);
            ioWorkers.stop(deadline);
            if (writeQueue != null) {
                writeQueue.stop(deadline, drain);
            }
        }

        @Override
        public void onConnected(IOSession session) {
            if (writeQueue == null) {
                LeaderConnection connected = new LeaderConnection(session, replicaSessions, nodeEpoch, nodeId, lane,
                        ReplicationClient.this::lanesAnnounced);
                session.setAttachment(connected);
                connection = connected;
                connected.hello();
                return;
            }
            LeaderConnection connected = new LeaderConnection(session, replicaSessions, nodeEpoch, nodeId, lane,
                    ReplicationClient.this::lanesAnnounced, LeaderConnection.CHECK_INTERVAL_NANOS,
                    new AnswerSender.ThroughNetworkThread(session), true);
            session.setAttachment(connected);
            connection = connected;
            writeQueue.connected(connected);
        }

        @Override
        public void onRead(IOSession session, ByteBuffer message, long localReceiveTimeInNanos) {
            LeaderConnection reading = session.getAttachment();
            if (writeQueue == null) {
                reading.read(message, decoder);
            } else {
                writeQueue.queueRead(reading, session, message);
            }
        }

        @Override
        public void onDisconnected(IOSession session) {
            if (session == null) {
                return;
            }
            LeaderConnection disconnected = session.getAttachment();
            if (disconnected != null) {
                if (writeQueue == null) {
                    disconnected.disconnected();
                } else {
                    writeQueue.disconnected();
                }
                if (connection == disconnected) {
                    connection = null;
                }
            }
        }
    }
}
