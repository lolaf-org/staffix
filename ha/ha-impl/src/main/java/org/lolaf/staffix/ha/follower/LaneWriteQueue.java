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
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.rb.ByteFrameHandler;
import org.lolaf.ringos.rb.ByteRingBuffer;
import org.lolaf.ringos.rb.ByteRingBufferFactory;
import org.lolaf.staffix.ha.wire.FrameDecoder;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.FrameType;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * A lane written by a store writer thread: the network thread queues the frames it reads, and its connections and
 * disconnections in their place among them, so the writer takes each in order. Allocates nothing per read.
 */
@Slf4j
final class LaneWriteQueue implements StoreWriterThreads.WriteQueue, ByteFrameHandler {

    private static final int FRAME = 0;
    private static final int CONNECTED = 1;
    private static final int DISCONNECTED = 2;
    private static final long QUEUE_FULL_PARK_NANOS = 50_000;
    private static final ByteBuffer NO_BYTES = ByteBuffer.allocate(0);

    private final int lane;
    private final int maxFrameBytes;
    private final ReplicaSessions replicaSessions;
    private final StoreWriterThreads.StoreWriterThread thread;
    private final ByteRingBuffer queue;
    private final Queue<LeaderConnection> connectedLeaders = new ConcurrentLinkedQueue<>();
    private final FrameDecoder writerDecoder;
    private volatile boolean writing;
    /**
     * The last event acknowledged as received; network thread only.
     */
    private long receivedOffsetAcked = -1;
    /**
     * The connection the writer applies the frames to, from the last connection it took; writer thread only.
     */
    private @Nullable LeaderConnection connection;
    private boolean framesUnwritten;
    /**
     * The queue's buffer, which a store write moves the position and limit of while the network thread writes into
     * it.
     */
    private @Nullable ByteBuffer queueBufferView;
    private @Nullable ByteBuffer viewedQueueBuffer;

    LaneWriteQueue(int lane, int maxFrameBytes, ReplicaSessions replicaSessions, StoreWriterThreads.StoreWriterThread thread) {
        this.lane = lane;
        this.maxFrameBytes = maxFrameBytes;
        this.replicaSessions = replicaSessions;
        this.thread = thread;
        this.queue = ByteRingBufferFactory.queue(queueCapacity(maxFrameBytes));
        this.writerDecoder = new FrameDecoder(maxFrameBytes);
        thread.add(this);
    }

    /**
     * A power of two holding the largest frame in an eighth of it, the queue's longest payload.
     */
    private static int queueCapacity(int maxFrameBytes) {
        int needed = Math.max(ByteRingBufferFactory.MIN_CAPACITY, 8 * (maxFrameBytes + 16));
        return Integer.highestOneBit(needed - 1) << 1;
    }

    void connected(LeaderConnection connected) {
        receivedOffsetAcked = -1;
        connectedLeaders.add(connected);
        queue(CONNECTED, NO_BYTES, 0, 0);
        thread.wakeup();
    }

    /**
     * Queues the read's whole frames for the writer, then acknowledges as received the last event of a
     * {@code SYNC_RECEIVED} session among them. A frame of an impossible length goes as its header alone, for the
     * writer's decoder to refuse, and the rest of the read with it.
     */
    void queueRead(LeaderConnection readConnection, IOSession ioSession, ByteBuffer message) {
        int index = message.position();
        int limit = message.limit();
        long lastReceivedOffset = -1;
        long lastReceivedEpoch = 0;
        while (limit - index >= WireFormat.HEADER_BYTES) {
            int length = message.getInt(index + WireFormat.OFFSET_LENGTH);
            if (length < WireFormat.HEADER_BYTES || length > maxFrameBytes) {
                queue(FRAME, message, index, WireFormat.HEADER_BYTES);
                index = limit;
                break;
            }
            if (limit - index < length) {
                break;
            }
            queue(FRAME, message, index, length);
            long frameEpoch = message.getLong(index + WireFormat.OFFSET_EPOCH);
            if (message.get(index + WireFormat.OFFSET_TYPE) == FrameType.STORE_EVENT.getCode()
                    && readConnection.isStreamEpoch(frameEpoch)
                    && replicaSessions.acksOnReceipt(lane, message.getInt(index + WireFormat.HEADER_BYTES + Long.BYTES))) {
                lastReceivedOffset = message.getLong(index + WireFormat.HEADER_BYTES);
                lastReceivedEpoch = frameEpoch;
            }
            index += length;
        }
        message.position(index);
        if (lastReceivedOffset > receivedOffsetAcked) {
            receivedOffsetAcked = lastReceivedOffset;
            ByteBuffer frame = ioSession.borrow(FrameEncoder.fixedBytes(FrameType.RECEIVED)).clear();
            ioSession.send(frame.position(FrameEncoder.received(frame, 0, lastReceivedEpoch, lastReceivedOffset)), true);
        }
        thread.wakeup();
    }

    void disconnected() {
        queue(DISCONNECTED, NO_BYTES, 0, 0);
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
                if (connection != null) {
                    connection.apply(viewOf(buffer), index, index + length, writerDecoder);
                    framesUnwritten = true;
                }
                break;
            case CONNECTED:
                connection = connectedLeaders.poll();
                if (connection != null) {
                    connection.hello();
                }
                break;
            default:
                writeUnwritten();
                if (connection != null) {
                    connection.disconnected();
                }
                connection = null;
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
        if (connection != null && framesUnwritten) {
            framesUnwritten = false;
            connection.endRead();
        }
    }

    /**
     * @param drain whether to wait, within the deadline, for the writer to write what was queued; not from the writer
     *              thread itself, which would wait on itself
     */
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
