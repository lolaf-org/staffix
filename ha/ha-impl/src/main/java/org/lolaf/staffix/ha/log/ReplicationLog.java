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
import org.lolaf.ringos.rb.BroadcastByteRingBuffer;
import org.lolaf.ringos.rb.ByteRingBufferFactory;
import org.lolaf.ringos.rb.ByteRingReader;
import org.lolaf.ringos.rb.ByteTranslator;
import org.lolaf.ringos.rb.ProducerType;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;

/**
 * The store events of every replicated session of an engine, in the order they happened, in one off-heap ring.
 *
 * <p>Each event is held as a {@link FrameEncoder#storeEventBody store event body}; a sender adds the header and the
 * event's offset, its position in the log, when it sends it. Appending never waits and allocates nothing: when the
 * ring is full the oldest events are overwritten, and a reader that falls that far behind is told it was lapped, so
 * a slow follower is caught up by snapshot rather than stalling a session.
 */
public class ReplicationLog {

    static final int STORE_EVENT = 0;

    private static final ByteTranslator<StoreEventRecord> ENCODE = (buffer, index, length, storeEventRecord) ->
            FrameEncoder.storeEventBody(buffer, index, storeEventRecord.getSessionKey(), storeEventRecord.getEventType(),
                    storeEventRecord.getSequenceNumber(), storeEventRecord.getMessage(), storeEventRecord.getFragmentIndex(),
                    storeEventRecord.getFragmentLength(), storeEventRecord.getFragmentFlags());

    private final BroadcastByteRingBuffer ring;

    /**
     * The longest message an event can carry, in fragments past {@link #getMaxFragmentLength()}: half the log, so a
     * follower reading it still has the other half before it is lapped.
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
     * @return the offset of the event, or of the message's last fragment
     * @throws IllegalArgumentException for a message longer than {@link #getMaxMessageLength()}
     */
    public long append(StoreEventRecord storeEventRecord) {
        int messageLength = storeEventRecord.messageLength();
        if (messageLength <= maxFragmentLength) {
            return write(storeEventRecord);
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
            offset = write(storeEventRecord.fragment(start + from, length, (byte) flags));
        }
        return offset;
    }

    private long write(StoreEventRecord storeEventRecord) {
        return ring.write(STORE_EVENT, FrameEncoder.storeEventBodyBytes(storeEventRecord.getFragmentLength()), ENCODE,
                storeEventRecord);
    }

    /**
     * The offset the next event will take.
     */
    public long getTailOffset() {
        return ring.getTailPosition();
    }

    /**
     * A reader for one follower, at the tail.
     */
    public Reader newReader() {
        return new Reader(ring.newReader());
    }

    /**
     * Reads the log from its own offset, independently of the other readers. One thread at a time.
     */
    public static final class Reader {

        /**
         * Returned by {@link #readInto} when writers overwrote events this reader had not read: the follower needs a
         * snapshot.
         */
        public static final int LAPPED = ByteRingReader.LAPPED;

        /**
         * Returned by {@link #readInto} when no event was appended past this reader.
         */
        public static final int NO_EVENT = ByteRingReader.NO_FRAME;

        private final ByteRingReader byteRingReader;

        private Reader(ByteRingReader byteRingReader) {
            this.byteRingReader = byteRingReader;
        }

        /**
         * Copies the next event's body into {@code dst}, checked whole.
         *
         * @return the body's length, {@link #NO_EVENT} or {@link #LAPPED}, after which the reader stays put until
         * {@link #seek} or {@link #seekToTail}
         */
        public int readInto(ByteBuffer dst, int dstIndex) {
            return byteRingReader.readInto(dst, dstIndex);
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
            return byteRingReader.seek(offset);
        }

        public void seekToTail() {
            byteRingReader.seekToTail();
        }
    }
}
