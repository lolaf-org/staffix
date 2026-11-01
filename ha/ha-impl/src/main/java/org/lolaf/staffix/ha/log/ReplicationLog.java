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
package org.lolaf.staffix.ha.log;

import lombok.Getter;
import org.lolaf.ringos.idling.RetryStrategy;
import org.lolaf.ringos.rb.BroadcastByteRingBuffer;
import org.lolaf.ringos.rb.ByteRingBufferFactory;
import org.lolaf.ringos.rb.ByteRingReader;
import org.lolaf.ringos.rb.ByteTranslator;
import org.lolaf.ringos.rb.ProducerType;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The store events of every replicated session of an engine, in the order they happened, in one off-heap ring.
 *
 * <p>Each event is held as a {@link FrameEncoder#storeEventBody store event body}; a sender adds the header and the
 * event's offset, its position in the log, when it sends it. Appending allocates nothing, and waits only while a
 * gating reader is a whole log behind: past the appender's retry strategy, the log drops the slowest gating reader,
 * telling its owner, and writes over what it had not read.
 */
public class ReplicationLog {

    static final int STORE_EVENT = 0;

    private static final ByteTranslator<StoreEventRecord> ENCODE = (buffer, index, length, storeEventRecord) ->
            FrameEncoder.storeEventBody(buffer, index, storeEventRecord.getSessionKey(), storeEventRecord.getEventType(),
                    storeEventRecord.getSequenceNumber(), storeEventRecord.getMessage(), storeEventRecord.getFragmentIndex(),
                    storeEventRecord.getFragmentLength(), storeEventRecord.getFragmentFlags());

    private final BroadcastByteRingBuffer ring;
    private final List<Reader> gatingReaders = new CopyOnWriteArrayList<>();
    /**
     * Counts the readers dropped, so writers that gave up together drop one reader, not one each.
     */
    private volatile long drops;

    /**
     * The longest message an event can carry, in fragments past {@link #getMaxFragmentLength()}: half the log, so the
     * other half still holds the events a follower had not read when it was written.
     */
    @Getter
    private final int maxMessageLength;

    /**
     * The longest message one event carries whole, the ring's largest frame.
     */
    @Getter
    private final int maxFragmentLength;

    /**
     * @param capacity bytes, a power of two of at least 1024
     */
    public ReplicationLog(int capacity) {
        this.ring = ByteRingBufferFactory.broadcast(capacity, ProducerType.MULTI);
        this.maxMessageLength = ring.getCapacity() / 2;
        this.maxFragmentLength = ring.getMaxPayloadLength() - FrameEncoder.storeEventBodyBytes(0);
    }

    /**
     * The longest STORE_EVENT frame this log can produce, which a follower's receive buffer must hold.
     */
    public int getMaxEventFrameBytes() {
        return WireFormat.STORE_EVENT_BODY_OFFSET + FrameEncoder.storeEventBodyBytes(maxFragmentLength);
    }

    /**
     * Any thread; the events of one session must be appended by one thread at a time, in their order. A message
     * longer than {@link #getMaxFragmentLength()} is appended as consecutive fragments, other sessions' events
     * possibly between them. Allocates nothing.
     *
     * @param storeEventRecord the event
     * @param logFullWait      how long to wait for a gating reader a whole log behind, the appending thread's own
     * @return the offset of the event, or of the message's last fragment
     * @throws IllegalArgumentException for a message longer than {@link #getMaxMessageLength()}
     */
    public long append(StoreEventRecord storeEventRecord, RetryStrategy logFullWait) {
        int messageLength = storeEventRecord.messageLength();
        if (messageLength <= maxFragmentLength) {
            return write(storeEventRecord, logFullWait);
        }
        if (messageLength > maxMessageLength) {
            throw new IllegalArgumentException("A message of " + messageLength + " bytes exceeds the "
                    + maxMessageLength + " a replication log of " + ring.getCapacity() + " bytes can hold");
        }
        int start = storeEventRecord.getMessage().position();
        long offset = -1;
        for (int from = 0; from < messageLength; from += maxFragmentLength) {
            int length = Math.min(maxFragmentLength, messageLength - from);
            int flags = (from + length < messageLength ? WireFormat.MORE_FRAGMENTS : 0) | (from > 0 ? WireFormat.CONTINUED_FRAGMENT : 0);
            offset = write(storeEventRecord.fragment(start + from, length, (byte) flags), logFullWait);
        }
        return offset;
    }

    private long write(StoreEventRecord storeEventRecord, RetryStrategy logFullWait) {
        int length = FrameEncoder.storeEventBodyBytes(storeEventRecord.getFragmentLength());
        while (true) {
            long dropsBefore = drops;
            long offset = ring.writeRetrying(STORE_EVENT, length, ENCODE, storeEventRecord, logFullWait);
            if (offset != BroadcastByteRingBuffer.GATED) {
                return offset;
            }
            dropSlowestReader(dropsBefore);
        }
    }

    private synchronized void dropSlowestReader(long dropsBefore) {
        if (drops != dropsBefore) {
            return;
        }
        Reader slowest = null;
        for (Reader reader : gatingReaders) {
            if (slowest == null || reader.offset < slowest.offset) {
                slowest = reader;
            }
        }
        if (slowest != null) {
            drops++;
            slowest.close();
            slowest.onDropped.run();
        }
    }

    /**
     * The offset the next event will take.
     */
    public long getTailOffset() {
        return ring.getTailPosition();
    }

    /**
     * A reader at the tail that holds no append back, so it can be lapped.
     */
    public Reader newReader() {
        return new Reader(ring.newReader(), null);
    }

    /**
     * A reader for one streaming follower, at the tail: appends wait for it rather than overwrite what it has not
     * read, until it is {@link Reader#close() closed}, or dropped as the slowest by an append that waited too long.
     *
     * @param onDropped run on the appending thread once the log dropped this reader, already closed
     */
    public Reader newGatingReader(Runnable onDropped) {
        Reader reader = new Reader(ring.newGatingReader(), onDropped);
        gatingReaders.add(reader);
        return reader;
    }

    /**
     * Reads the log from its own offset, independently of the other readers. One thread at a time, but for
     * {@link #close()}.
     */
    public final class Reader {

        /**
         * Returned by {@link #readInto} when writers overwrote events this reader had not read, which a gating reader
         * sees only once closed: the follower needs a snapshot.
         */
        public static final int LAPPED = ByteRingReader.LAPPED;

        /**
         * Returned by {@link #readInto} when no event was appended past this reader.
         */
        public static final int NO_EVENT = ByteRingReader.NO_FRAME;

        /**
         * Returned by {@link #readInto} when the next event is larger than the room left in {@code dst}: the reader
         * stays on it.
         */
        public static final int NO_ROOM = ByteRingReader.NO_ROOM;

        private final ByteRingReader byteRingReader;
        private final Runnable onDropped;
        /**
         * {@link #getOffset()} for the appending threads that look for the slowest reader.
         */
        private volatile long offset;

        private Reader(ByteRingReader byteRingReader, Runnable onDropped) {
            this.byteRingReader = byteRingReader;
            this.onDropped = onDropped;
            this.offset = byteRingReader.getPosition();
        }

        /**
         * Copies the next event's body into {@code dst}, checked whole.
         *
         * @return the body's length, {@link #NO_EVENT}, {@link #NO_ROOM} or {@link #LAPPED}, after which the reader stays put until
         * {@link #seek} or {@link #seekToTail}
         */
        public int readInto(ByteBuffer dst, int dstIndex) {
            int body = byteRingReader.readInto(dst, dstIndex);
            offset = byteRingReader.getPosition();
            return body;
        }

        /**
         * The offset of the event last read.
         */
        public long getEventOffset() {
            return byteRingReader.getFramePosition();
        }

        /**
         * The offset of the next event to read.
         */
        public long getOffset() {
            return byteRingReader.getPosition();
        }

        /**
         * Resumes from an event's offset, for a follower reconnecting.
         *
         * @return false, and the reader left where it was, when that event is no longer in the log
         */
        public boolean seek(long offset) {
            boolean moved = byteRingReader.seek(offset);
            this.offset = byteRingReader.getPosition();
            return moved;
        }

        public void seekToTail() {
            byteRingReader.seekToTail();
            offset = byteRingReader.getPosition();
        }

        /**
         * Steps back to the event last read, so the next read returns it again: one a sender cannot send yet. A gating
         * reader keeps that event; once closed, the next read may find it lapped instead.
         */
        public void unread() {
            byteRingReader.unread();
            offset = byteRingReader.getPosition();
        }

        /**
         * Stops holding appends back. Any thread, since the log closes the reader it drops from the appending one.
         */
        public void close() {
            gatingReaders.remove(this);
            byteRingReader.close();
        }
    }
}
