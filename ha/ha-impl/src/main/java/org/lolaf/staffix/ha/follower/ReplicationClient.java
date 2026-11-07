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
import org.lolaf.ringos.rb.ByteFrameHandler;
import org.lolaf.ringos.rb.ByteRingBuffer;
import org.lolaf.ringos.rb.ByteRingBufferFactory;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.ha.wire.FrameDecoder;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.FrameType;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The follower's connections to the leader, one per replication lane the leader announces, each made again whenever
 * it drops through a new {@link LeaderConnection} on the same {@link ReplicaSessions}. Lane 0 connects first; its WELCOME, or any
 * lane's, says how many the leader has, and the others are opened or closed to match. Each lane has its own IO
 * thread, so the lanes are applied in parallel.
 */
@Slf4j
public final class ReplicationClient {

    private static final Duration LANE_STOP = Duration.ofSeconds(1);
    private static final long QUEUE_FULL_PARK_NANOS = 50_000;
    private static final ByteBuffer NO_BYTES = ByteBuffer.allocate(0);

    private final InetSocketAddress leaderAddress;
    private final ReplicaSessions replicaSessions;
    private final LongSupplier nodeEpoch;
    private final String nodeId;
    private final int maxFrameBytes;
    private final Duration connectionRetry;
    private final SelectStrategy selectStrategy;
    private final @Nullable StoreWriterThreads storeWriterThreads;
    private volatile LaneConnection[] connections = new LaneConnection[0];
    private boolean stopped;

    /**
     * @param maxFrameBytes the largest frame the leader sends, see
     *                      {@link org.lolaf.staffix.ha.log.ReplicationLog#getMaxEventFrameBytes()}
     */
    public ReplicationClient(InetSocketAddress leaderAddress, ReplicaSessions replicaSessions, LongSupplier nodeEpoch, String nodeId,
                               int maxFrameBytes, Duration connectionRetry, SelectStrategy selectStrategy) {
        this(leaderAddress, replicaSessions, nodeEpoch, nodeId, maxFrameBytes, connectionRetry, selectStrategy, 0, BackoffIdleStrategy::new);
    }

    /**
     * @param storeWriterThreads threads writing what the lanes read into the stores, 0 to write on the lanes' network
     *                           threads
     */
    public ReplicationClient(InetSocketAddress leaderAddress, ReplicaSessions replicaSessions, LongSupplier nodeEpoch, String nodeId,
                               int maxFrameBytes, Duration connectionRetry, SelectStrategy selectStrategy,
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
        connections = new LaneConnection[]{new LaneConnection(0)};
        log.info("Replication follower {} connecting to its leader at {}", nodeId, leaderAddress);
    }

    /**
     * Stops reading, then waits, within the deadline, for each lane to write what it read: an event acknowledged as
     * received is written before a promotion starts the sessions.
     */
    public synchronized void stop(Deadline deadline) {
        stopped = true;
        for (LaneConnection connection : connections) {
            connection.stop(deadline, true);
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
        LaneConnection[] current = connections;
        if (stopped || lanes == current.length) {
            return;
        }
        LaneConnection[] resized = Arrays.copyOf(current, lanes);
        for (int lane = current.length; lane < lanes; lane++) {
            resized[lane] = new LaneConnection(lane);
        }
        for (int lane = lanes; lane < current.length; lane++) {
            current[lane].stop(Deadline.of(LANE_STOP), false);
        }
        connections = resized;
        log.info("Replication follower {} replicates over {} lanes from its leader at {}", nodeId, lanes, leaderAddress);
    }

    /**
     * The lane's connection to the leader now, null between connections or for a lane not opened.
     */
    public @Nullable LeaderConnection getConnection(int lane) {
        LaneConnection[] current = connections;
        return lane < current.length ? current[lane].leader : null;
    }

    public HaStatus.Leader status() {
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
        LaneConnection[] current = connections;
        for (LaneConnection connection : current) {
            LeaderConnection leader = connection.leader;
            if (leader == null || leader.getState() != LeaderConnection.State.STREAMING) {
                return false;
            }
        }
        return current.length > 0;
    }

    public boolean isInSync() {
        return isConnected() && replicaSessions.isComplete();
    }

    private final class LaneConnection implements IOEventsListener {

        private final int lane;
        private final FrameDecoder decoder = new FrameDecoder(maxFrameBytes);
        private final IOWorkersGroup ioWorkers;
        private final Client client;
        private volatile @Nullable LeaderConnection leader;
        private final @Nullable LaneWriteQueue writeQueue;
        private long receivedOffsetAcked = -1;

        LaneConnection(int lane) {
            this.lane = lane;
            this.writeQueue = storeWriterThreads == null ? null : new LaneWriteQueue(storeWriterThreads.forLane(lane));
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
                LeaderConnection connected = new LeaderConnection(session, replicaSessions, nodeEpoch, nodeId, lane, ReplicationClient.this::lanesAnnounced);
                session.setAttachment(connected);
                leader = connected;
                connected.hello();
                return;
            }
            LeaderConnection connected = new LeaderConnection(session, replicaSessions, nodeEpoch, nodeId, lane, ReplicationClient.this::lanesAnnounced,
                    LeaderConnection.REBIND_CHECK_NANOS, new AnswerSender.ThroughNetworkThread(session), true);
            session.setAttachment(connected);
            leader = connected;
            receivedOffsetAcked = -1;
            writeQueue.connected(connected);
        }

        @Override
        public void onRead(IOSession session, ByteBuffer message, long localReceiveTimeInNanos) {
            LeaderConnection leaderConnection = session.getAttachment();
            if (writeQueue == null) {
                leaderConnection.read(message, decoder);
            } else {
                queueForWriter(leaderConnection, session, message);
            }
        }

        /**
         * Queues the read's whole frames for the store writer thread, then acknowledges as received the last event of
         * a {@code SYNC_RECEIVED} session among them. A frame of an impossible length goes as its header alone, for the
         * writer's decoder to refuse, and the rest of the read with it.
         */
        private void queueForWriter(LeaderConnection leaderConnection, IOSession session, ByteBuffer message) {
            int index = message.position();
            int limit = message.limit();
            long lastReceivedOffset = -1;
            long lastReceivedEpoch = 0;
            while (limit - index >= WireFormat.HEADER_BYTES) {
                int length = message.getInt(index + WireFormat.OFFSET_LENGTH);
                if (length < WireFormat.HEADER_BYTES || length > maxFrameBytes) {
                    writeQueue.frame(message, index, WireFormat.HEADER_BYTES);
                    index = limit;
                    break;
                }
                if (limit - index < length) {
                    break;
                }
                writeQueue.frame(message, index, length);
                long frameEpoch = message.getLong(index + WireFormat.OFFSET_EPOCH);
                if (message.get(index + WireFormat.OFFSET_TYPE) == FrameType.STORE_EVENT.getCode()
                        && leaderConnection.followsEpoch(frameEpoch)
                        && replicaSessions.acksOnReceipt(lane, message.getInt(index + WireFormat.HEADER_BYTES + Long.BYTES))) {
                    lastReceivedOffset = message.getLong(index + WireFormat.HEADER_BYTES);
                    lastReceivedEpoch = frameEpoch;
                }
                index += length;
            }
            message.position(index);
            if (lastReceivedOffset > receivedOffsetAcked) {
                receivedOffsetAcked = lastReceivedOffset;
                ByteBuffer frame = session.borrow(FrameEncoder.fixedBytes(FrameType.RECEIVED)).clear();
                session.send(frame.position(FrameEncoder.received(frame, 0, lastReceivedEpoch, lastReceivedOffset)), true);
            }
            writeQueue.wakeup();
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
                if (leader == disconnected) {
                    leader = null;
                }
            }
        }

        /**
         * A lane written by a store writer thread: the network thread queues the frames it reads, and its connections
         * and disconnections in their place among them, so the writer takes each in order.
         */
        private final class LaneWriteQueue implements StoreWriterThreads.WriteQueue, ByteFrameHandler {

            private static final int FRAME = 0;
            private static final int CONNECTED = 1;
            private static final int DISCONNECTED = 2;

            private final StoreWriterThreads.StoreWriterThread thread;
            private final ByteRingBuffer queue = ByteRingBufferFactory.queue(queueCapacity(maxFrameBytes));
            private final Queue<LeaderConnection> connectedLanes = new ConcurrentLinkedQueue<>();
            private final FrameDecoder writerDecoder = new FrameDecoder(maxFrameBytes);
            private volatile boolean writing;
            private @Nullable LeaderConnection leaderConnection;
            private boolean framesUnwritten;
            /**
             * The queue's buffer, which a store write moves the position and limit of while the network thread
             * writes into it.
             */
            private @Nullable ByteBuffer queueBufferView;
            private @Nullable ByteBuffer viewedQueueBuffer;

            LaneWriteQueue(StoreWriterThreads.StoreWriterThread thread) {
                this.thread = thread;
                thread.add(this);
            }

            void connected(LeaderConnection connected) {
                connectedLanes.add(connected);
                queue(CONNECTED, NO_BYTES, 0, 0);
                wakeup();
            }

            void frame(ByteBuffer read, int index, int length) {
                queue(FRAME, read, index, length);
            }

            void disconnected() {
                queue(DISCONNECTED, NO_BYTES, 0, 0);
                wakeup();
            }

            void wakeup() {
                thread.wakeup();
            }

            /**
             * Full: the network thread stops reading, and TCP holds the leader back.
             */
            private void queue(int type, ByteBuffer bytes, int index, int length) {
                while (!queue.write(type, bytes, index, length)) {
                    thread.wakeup();
                    LockSupport.parkNanos(QUEUE_FULL_PARK_NANOS);
                }
            }

            @Override
            public int writeQueued() {
                writing = true;
                try {
                    int framesTaken = queue.read(this, Integer.MAX_VALUE);
                    writeUnwritten();
                    return framesTaken;
                } finally {
                    writing = false;
                }
            }

            @Override
            public void onFrame(int type, ByteBuffer buffer, int index, int length) {
                switch (type) {
                    case FRAME:
                        if (leaderConnection != null) {
                            leaderConnection.apply(viewOf(buffer), index, index + length, writerDecoder);
                            framesUnwritten = true;
                        }
                        break;
                    case CONNECTED:
                        leaderConnection = connectedLanes.poll();
                        if (leaderConnection != null) {
                            leaderConnection.hello();
                        }
                        break;
                    default:
                        writeUnwritten();
                        if (leaderConnection != null) {
                            leaderConnection.disconnected();
                        }
                        leaderConnection = null;
                }
            }

            private ByteBuffer viewOf(ByteBuffer buffer) {
                if (viewedQueueBuffer != buffer) {
                    viewedQueueBuffer = buffer;
                    queueBufferView = buffer.duplicate();
                }
                return queueBufferView.clear();
            }

            private void writeUnwritten() {
                if (leaderConnection != null && framesUnwritten) {
                    framesUnwritten = false;
                    leaderConnection.endRead();
                }
            }

            void stop(Deadline deadline, boolean drain) {
                if (drain && !thread.isCurrentThread()) {
                    while ((writing || queue.getSize() > 0) && !deadline.isExpired()) {
                        thread.wakeup();
                        LockSupport.parkNanos(QUEUE_FULL_PARK_NANOS);
                    }
                    if (writing || queue.getSize() > 0) {
                        log.warn("Replication lane {} stopped before writing everything it read", lane);
                    }
                }
                thread.remove(this);
            }
        }
    }

    /**
     * A power of two holding the largest frame in an eighth of it, the queue's longest payload.
     */
    private static int queueCapacity(int maxFrameBytes) {
        int needed = Math.max(ByteRingBufferFactory.MIN_CAPACITY, 8 * (maxFrameBytes + 16));
        return Integer.highestOneBit(needed - 1) << 1;
    }
}
