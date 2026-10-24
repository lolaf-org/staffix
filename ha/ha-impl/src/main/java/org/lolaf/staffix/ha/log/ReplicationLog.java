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

    private static final ByteTranslator<StoreEventRecord> ENCODE = (buffer, index, length, record) ->
            FrameEncoder.storeEventBody(buffer, index, record.getSessionKey(), record.getEventType(), record.getSequenceNumber(),
                    record.getMessage());

    private final BroadcastByteRingBuffer ring;

    /**
     * @param capacity bytes, a power of two of at least 1024
     */
    public ReplicationLog(int capacity) {
        this.ring = ByteRingBufferFactory.broadcast(capacity, ProducerType.MULTI);
    }

    /**
     * The longest message an event can carry.
     */
    public int getMaxMessageLength() {
        return ring.getMaxPayloadLength() - FrameEncoder.storeEventBodyBytes(0);
    }

    /**
     * The longest STORE_EVENT frame this log can produce, which a follower's receive buffer must hold.
     */
    public int getMaxEventFrameBytes() {
        return WireFormat.STORE_EVENT_BODY_OFFSET + FrameEncoder.storeEventBodyBytes(getMaxMessageLength());
    }

    /**
     * Any thread; the events of one session must be appended by one thread at a time, in their order.
     *
     * @return the event's offset
     * @throws IllegalArgumentException for a message longer than {@link #getMaxMessageLength()}
     */
    public long append(StoreEventRecord record) {
        int messageLength = record.messageLength();
        if (messageLength > getMaxMessageLength()) {
            throw new IllegalArgumentException("A message of " + messageLength + " bytes exceeds the "
                    + getMaxMessageLength() + " a replication log of " + ring.getCapacity() + " bytes can hold");
        }
        return ring.write(STORE_EVENT, FrameEncoder.storeEventBodyBytes(messageLength), ENCODE, record);
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

        private final ByteRingReader reader;

        private Reader(ByteRingReader reader) {
            this.reader = reader;
        }

        /**
         * Copies the next event's body into {@code dst}, checked whole.
         *
         * @return the body's length, {@link #NO_EVENT} or {@link #LAPPED}, after which the reader stays put until
         * {@link #seek} or {@link #seekToTail}
         */
        public int readInto(ByteBuffer dst, int dstIndex) {
            return reader.readInto(dst, dstIndex);
        }

        /**
         * The offset of the event last read.
         */
        public long getEventOffset() {
            return reader.getFramePosition();
        }

        /**
         * The offset of the next event to read.
         */
        public long getOffset() {
            return reader.getPosition();
        }

        /**
         * Resumes from an event's offset, for a follower reconnecting.
         *
         * @return false, and the reader left where it was, when that event is no longer in the log
         */
        public boolean seek(long offset) {
            return reader.seek(offset);
        }

        public void seekToTail() {
            reader.seekToTail();
        }
    }
}
