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

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.betty.api.io.IOWriter.MessageSentCallback;
import org.lolaf.ringos.rb.RingBuffer;
import org.lolaf.ringos.rb.RingBufferFactory;

import java.nio.ByteBuffer;

/**
 * The buffers a connection sends its batches in, handed back by betty's IO thread once written. Owned rather than
 * borrowed from the connection's pool: betty frees a pool's memory when the connection stops, whichever thread still
 * writes into one. Taken and sent by the sender thread; allocates only up to its count.
 */
final class BatchBuffers {

    private final IOSession ioSession;
    private final int count;
    @Getter
    private final int batchBytes;
    private final ReplicationStats stats;
    private final RingBuffer<ByteBuffer> writtenBatches;
    private final MessageSentCallback<ByteBuffer> batchWritten = this::batchWritten;
    /**
     * The batch last written, handed back with the next: betty restores its position and limit once the callback
     * returns, which would land on a sender already filling it. IO thread only.
     */
    private @Nullable ByteBuffer lastWrittenBatch;
    private int allocated;
    /**
     * A batch taken for a pass with nothing to send, kept for the next one.
     */
    private @Nullable ByteBuffer spareBatch;
    private volatile boolean aboveWriteWatermark;
    private boolean sendWaitCounted;

    /**
     * @param count the buffers allocated at most, a power of two; the one written last is reused only once the next is
     *              written
     */
    BatchBuffers(IOSession ioSession, int count, int batchBytes, ReplicationStats stats) {
        this.ioSession = ioSession;
        this.count = count;
        this.batchBytes = batchBytes;
        this.stats = stats;
        this.writtenBatches = RingBufferFactory.build(RingBufferFactory.AccessType.SINGLE_CONSUMER_SINGLE_PRODUCER, count);
    }

    /**
     * A buffer written from index 0 with absolute puts, as a send takes it: up to its position, which betty flips.
     */
    static ByteBuffer written(ByteBuffer buffer, int length) {
        return buffer.clear().position(length);
    }

    /**
     * Only once {@link #hasNoRoom()} said there is room.
     */
    ByteBuffer take() {
        ByteBuffer batch = spareBatch;
        if (batch != null) {
            spareBatch = null;
            return batch;
        }
        batch = writtenBatches.poll();
        if (batch == null) {
            allocated++;
            batch = ByteBuffer.allocateDirect(batchBytes);
        }
        return batch.clear();
    }

    /**
     * Gives back a batch taken and not sent, for the next {@link #take()}.
     */
    void keep(ByteBuffer batch) {
        spareBatch = batch;
    }

    void send(ByteBuffer batch, int length) {
        ioSession.send(written(batch, length), batch, batchWritten, false);
    }

    void onWriteWatermark(boolean reached) {
        aboveWriteWatermark = reached;
    }

    /**
     * Betty's write watermark is reached, or every buffer is in flight.
     */
    boolean hasNoRoom() {
        boolean noRoom = aboveWriteWatermark || spareBatch == null && allocated >= count && writtenBatches.isEmpty();
        if (!noRoom) {
            sendWaitCounted = false;
        }
        return noRoom;
    }

    /**
     * Counts one send wait per stretch without room, however often the sender finds none.
     */
    void countSendWait() {
        if (!sendWaitCounted) {
            sendWaitCounted = true;
            stats.sendWaited();
        }
    }

    private void batchWritten(ByteBuffer message, Exception error, ByteBuffer batch) {
        ByteBuffer previous = lastWrittenBatch;
        lastWrittenBatch = batch;
        if (previous != null) {
            writtenBatches.offer(previous);
        }
    }
}
