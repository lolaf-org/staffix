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
 * The follower's connections to the leader, one per replication link the leader announces, each made again whenever
 * it drops through a new {@link LeaderLink} on the same {@link Replica}. Link 0 connects first; its WELCOME, or any
 * link's, says how many the leader has, and the others are opened or closed to match. Each link has its own IO
 * thread, so the links are applied in parallel.
 */
@Slf4j
public final class ReplicationReceiver {

    private static final Duration LINK_STOP = Duration.ofSeconds(1);
    private static final long QUEUE_FULL_PARK_NANOS = 50_000;
    private static final ByteBuffer NO_BYTES = ByteBuffer.allocate(0);

    private final InetSocketAddress leaderAddress;
    private final Replica replica;
    private final LongSupplier epoch;
    private final String nodeId;
    private final int maxFrameBytes;
    private final Duration connectionRetry;
    private final SelectStrategy selectStrategy;
    private final @Nullable StoreWriterThreads storeWriterThreads;
    private volatile LinkConnection[] connections = new LinkConnection[0];
    private boolean stopped;

    /**
     * @param maxFrameBytes the largest frame the leader sends, see
     *                      {@link org.lolaf.staffix.ha.log.ReplicationLog#getMaxEventFrameBytes()}
     */
    public ReplicationReceiver(InetSocketAddress leaderAddress, Replica replica, LongSupplier epoch, String nodeId,
                               int maxFrameBytes, Duration connectionRetry, SelectStrategy selectStrategy) {
        this(leaderAddress, replica, epoch, nodeId, maxFrameBytes, connectionRetry, selectStrategy, 0, BackoffIdleStrategy::new);
    }

    /**
     * @param storeWriterThreads threads writing what the links read into the stores, 0 to write on the links' network
     *                           threads
     */
    public ReplicationReceiver(InetSocketAddress leaderAddress, Replica replica, LongSupplier epoch, String nodeId,
                               int maxFrameBytes, Duration connectionRetry, SelectStrategy selectStrategy,
                               int storeWriterThreads, Supplier<IdleStrategy> storeWriterIdleStrategy) {
        this.storeWriterThreads = storeWriterThreads == 0 ? null
                : new StoreWriterThreads(storeWriterThreads, storeWriterIdleStrategy, nodeId);
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

    /**
     * Stops reading, then waits, within the deadline, for each link to write what it read: an event acknowledged as
     * received is written before a promotion starts the sessions.
     */
    public synchronized void stop(Deadline deadline) {
        stopped = true;
        for (LinkConnection connection : connections) {
            connection.stop(deadline, true);
        }
        if (storeWriterThreads != null) {
            storeWriterThreads.stop();
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
            current[link].stop(Deadline.of(LINK_STOP), false);
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
        private final @Nullable LinkWriteQueue writeQueue;
        private long receivedOffsetAcked = -1;

        LinkConnection(int link) {
            this.link = link;
            this.writeQueue = storeWriterThreads == null ? null : new LinkWriteQueue(storeWriterThreads.forLink(link));
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
                            .readBufferSize(WireFormat.READ_BUFFER_BYTES)
                            .maxReadBufferSize(WireFormat.bufferBytes(maxFrameBytes))
                            .socketOptions(Map.of(StandardSocketOptions.TCP_NODELAY, true))
                            .build())
                    .ioEventsListener(this)
                    .build().newInstance();
            client.start();
        }

        /**
         * @param drain whether to wait, within the deadline, for the link's store writer thread to write what was read;
         *              not when the link is dropped from a store writer thread, which may be the one to drain it
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
                LeaderLink connected = new LeaderLink(session, replica, epoch, nodeId, link, ReplicationReceiver.this::linksAnnounced);
                session.setAttachment(connected);
                leader = connected;
                connected.hello();
                return;
            }
            LeaderLink connected = new LeaderLink(session, replica, epoch, nodeId, link, ReplicationReceiver.this::linksAnnounced,
                    LeaderLink.REBIND_CHECK_NANOS, new AnswerSender.ThroughNetworkThread(session), true);
            session.setAttachment(connected);
            leader = connected;
            receivedOffsetAcked = -1;
            writeQueue.connected(connected);
        }

        @Override
        public void onRead(IOSession session, ByteBuffer message, long localReceiveTimeInNanos) {
            LeaderLink leaderLink = session.getAttachment();
            if (writeQueue == null) {
                leaderLink.read(message, decoder);
            } else {
                queueForWriter(leaderLink, session, message);
            }
        }

        /**
         * Queues the read's whole frames for the store writer thread, then acknowledges as received the last event of
         * a {@code SYNC_RECEIVED} session among them. A frame of an impossible length goes as its header alone, for the
         * writer's decoder to refuse, and the rest of the read with it.
         */
        private void queueForWriter(LeaderLink leaderLink, IOSession session, ByteBuffer message) {
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
                        && leaderLink.followsEpoch(frameEpoch)
                        && replica.acksOnReceipt(link, message.getInt(index + WireFormat.HEADER_BYTES + Long.BYTES))) {
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
            LeaderLink disconnected = session.getAttachment();
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
         * A link written by a store writer thread: the network thread queues the frames it reads, and its connections
         * and disconnections in their place among them, so the writer takes each in order.
         */
        private final class LinkWriteQueue implements StoreWriterThreads.WriteQueue, ByteFrameHandler {

            private static final int FRAME = 0;
            private static final int CONNECTED = 1;
            private static final int DISCONNECTED = 2;

            private final StoreWriterThreads.StoreWriterThread thread;
            private final ByteRingBuffer queue = ByteRingBufferFactory.queue(queueCapacity(maxFrameBytes));
            private final Queue<LeaderLink> connectedLinks = new ConcurrentLinkedQueue<>();
            private final FrameDecoder writerDecoder = new FrameDecoder(maxFrameBytes);
            private volatile boolean writing;
            private @Nullable LeaderLink leaderLink;
            private boolean framesUnwritten;
            /**
             * The queue's buffer, which a store write moves the position and limit of while the network thread
             * writes into it.
             */
            private @Nullable ByteBuffer queueBufferView;
            private @Nullable ByteBuffer viewedQueueBuffer;

            LinkWriteQueue(StoreWriterThreads.StoreWriterThread thread) {
                this.thread = thread;
                thread.add(this);
            }

            void connected(LeaderLink connected) {
                connectedLinks.add(connected);
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
                        if (leaderLink != null) {
                            leaderLink.apply(viewOf(buffer), index, index + length, writerDecoder);
                            framesUnwritten = true;
                        }
                        break;
                    case CONNECTED:
                        leaderLink = connectedLinks.poll();
                        if (leaderLink != null) {
                            leaderLink.hello();
                        }
                        break;
                    default:
                        writeUnwritten();
                        if (leaderLink != null) {
                            leaderLink.disconnected();
                        }
                        leaderLink = null;
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
                if (leaderLink != null && framesUnwritten) {
                    framesUnwritten = false;
                    leaderLink.endRead();
                }
            }

            void stop(Deadline deadline, boolean drain) {
                if (drain && !thread.isCurrentThread()) {
                    while ((writing || queue.getSize() > 0) && !deadline.isExpired()) {
                        thread.wakeup();
                        LockSupport.parkNanos(QUEUE_FULL_PARK_NANOS);
                    }
                    if (writing || queue.getSize() > 0) {
                        log.warn("Replication link {} stopped before writing everything it read", link);
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
