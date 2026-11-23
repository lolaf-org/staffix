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
package org.lolaf.staffix.ha.replication.log;

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.lolaf.ringos.idling.RetryStrategy;
import org.lolaf.ringos.rb.*;
import org.lolaf.staffix.ha.replication.wire.FrameEncoder;
import org.lolaf.staffix.ha.replication.wire.WireFormat;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The records of every replicated stream of a lane, in the order they were appended, in one off-heap ring.
 *
 * <p>Each record is held as a {@link FrameEncoder#recordBody record body}; a sender adds the header and the
 * record's offset, its position in the log, when it sends it. Appending allocates nothing, and waits only while a
 * reader is a whole log behind: past the appender's retry strategy, the log drops the slowest reader, telling its
 * owner, and writes over what it had not read.
 */
public class ReplicationLog {

    private static final int RECORD = 0;

    private static final ByteTranslator<LogRecord> ENCODE = (buffer, index, length, logRecord) ->
            FrameEncoder.recordBody(buffer, index, logRecord.getStreamKey(), logRecord.getContentType(),
                    (byte) (logRecord.getFlags() | logRecord.getFragmentFlags()), logRecord.getPosition(),
                    logRecord.getBuffer(), logRecord.getFragmentIndex(), logRecord.getFragmentLength());

    private final BroadcastByteRingBuffer ring;
    private final List<Reader> readers;
    /**
     * The longest payload a record can carry, in fragments past {@link #getMaxFragmentLength()}: half the log, so the
     * other half still holds the records a follower had not read when it was written.
     */
    @Getter
    private final int maxPayloadLength;
    /**
     * The longest payload one record carries whole, the ring's largest frame.
     */
    @Getter
    private final int maxFragmentLength;
    /**
     * Counts the readers dropped, so writers that gave up together drop one reader, not one each.
     */
    private volatile long drops;

    /**
     * @param capacity bytes, a power of two of at least 1024
     */
    public ReplicationLog(int capacity) {
        this.readers = new CopyOnWriteArrayList<>();
        this.ring = ByteRingBufferFactory.broadcast(capacity, ProducerType.MULTI);
        this.maxPayloadLength = ring.getCapacity() / 2;
        this.maxFragmentLength = ring.getMaxPayloadLength() - FrameEncoder.recordBodyBytes(0);
    }

    /**
     * The longest RECORD frame this log can produce, which a follower's receive buffer must hold with a
     * {@link WireFormat#TAG_BYTES tag}.
     */
    public int getMaxRecordFrameBytes() {
        return WireFormat.RECORD_BODY_OFFSET + FrameEncoder.recordBodyBytes(maxFragmentLength);
    }

    /**
     * Any thread; the records of one stream must be appended by one thread at a time, in their order. A payload
     * longer than {@link #getMaxFragmentLength()} is appended as consecutive fragments, other streams' records
     * possibly between them. Allocates nothing.
     *
     * @param logFullWait how long to wait for a reader a whole log behind, the appending thread's own
     * @return the offset of the record, or of the payload's last fragment
     * @throws IllegalArgumentException for a payload longer than {@link #getMaxPayloadLength()}
     */
    public long append(LogRecord logRecord, RetryStrategy logFullWait) {
        int payloadLength = logRecord.getPayloadLength();
        if (payloadLength <= maxFragmentLength) {
            return write(logRecord, logFullWait);
        }
        if (payloadLength > maxPayloadLength) {
            throw new IllegalArgumentException("A payload of " + payloadLength + " bytes exceeds the "
                    + maxPayloadLength + " a replication log of " + ring.getCapacity() + " bytes can hold");
        }
        int start = logRecord.getPayloadIndex();
        long offset = -1;
        for (int from = 0; from < payloadLength; from += maxFragmentLength) {
            int length = Math.min(maxFragmentLength, payloadLength - from);
            int flags = (from + length < payloadLength ? WireFormat.MORE_FRAGMENTS : 0) | (from > 0 ? WireFormat.CONTINUED_FRAGMENT : 0);
            offset = write(logRecord.fragment(start + from, length, (byte) flags), logFullWait);
        }
        return offset;
    }

    private long write(LogRecord logRecord, RetryStrategy logFullWait) {
        int length = FrameEncoder.recordBodyBytes(logRecord.getFragmentLength());
        long dropsBefore = drops;
        long offset;
        while ((offset = ring.writeRetrying(RECORD, length, ENCODE, logRecord, logFullWait)) == BroadcastByteRingBuffer.GATED) {
            dropSlowestReader(dropsBefore);
            // dropSlowestReader can modify drops
            dropsBefore = drops;
        }
        return offset;
    }

    private void dropSlowestReader(long dropsBefore) {
        Reader dropped = closeSlowestReader(dropsBefore);
        if (dropped != null) {
            dropped.onDropped.run();
        }
    }

    /**
     * Locked so that writers that gave up together retry only once the reader is closed, rather than wait for it again.
     */
    private synchronized @Nullable Reader closeSlowestReader(long dropsBefore) {
        if (drops != dropsBefore) {
            return null;
        }
        Reader slowest = null;
        for (Reader reader : readers) {
            if (slowest == null || reader.offset < slowest.offset) {
                slowest = reader;
            }
        }
        if (slowest != null) {
            drops++;
            slowest.close();
        }
        return slowest;
    }

    /**
     * The offset the next record will take.
     */
    public long getTailOffset() {
        return ring.getTailPosition();
    }

    /**
     * A reader for one streaming follower, at the tail: appends wait for it rather than overwrite what it has not
     * read, until it is {@link Reader#close() closed}, or dropped as the slowest by an append that waited too long.
     *
     * @param onDropped run on the appending thread once the log dropped this reader, already closed
     */
    public Reader newReader(Runnable onDropped) {
        Reader reader = new Reader(ring.newGatingReader(), onDropped);
        readers.add(reader);
        return reader;
    }

    /**
     * Reads the log from its own offset, independently of the other readers, holding appends back while open. One
     * thread at a time, but for {@link #close()}.
     */
    public final class Reader {

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
         * Copies the next record's body into {@code dst}, checked whole.
         *
         * @return the body's length, or a negative value with the reader left where it was: no record past it, the
         * next one larger than the room left in {@code dst}, or, once it was closed, records overwritten
         */
        public int readInto(ByteBuffer dst, int dstIndex) {
            int body = byteRingReader.readInto(dst, dstIndex);
            offset = byteRingReader.getPosition();
            return body;
        }

        /**
         * The offset of the record last read.
         */
        public long getRecordOffset() {
            return byteRingReader.getFramePosition();
        }

        /**
         * The offset of the next record to read.
         */
        public long getOffset() {
            return byteRingReader.getPosition();
        }

        /**
         * Resumes from a record's offset, for a follower reconnecting.
         *
         * @return false, and the reader left where it was, when that record is no longer in the log
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
         * Steps back to the record last read, so the next read returns it again: one a sender cannot send yet. Appends
         * keep that record until the reader is closed.
         */
        public void unread() {
            byteRingReader.unread();
            offset = byteRingReader.getPosition();
        }

        /**
         * Stops holding appends back. Any thread, since the log closes the reader it drops from the appending one.
         */
        public void close() {
            readers.remove(this);
            byteRingReader.close();
        }
    }
}
