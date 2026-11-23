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
package org.lolaf.staffix.ha.replication.spi;

import java.nio.ByteBuffer;

/**
 * A stream's state and records, as a snapshot copies them while the stream keeps being written. The snapshot copies
 * in rounds, each up to the last position the state covered when the round started, so it relies on two things: a
 * record is readable here before it is appended to the log, and positions grow between two resets. One sender thread
 * at a time.
 */
public interface SnapshotSource {

    /**
     * False while the stream cannot be read yet, as before its storage started: the snapshot is retried shortly.
     */
    boolean isReadable();

    /**
     * Writes the stream's current state into {@code state}, cleared, up to its position, at most
     * {@link org.lolaf.staffix.ha.replication.wire.WireFormat#MAX_STATE_BYTES}: the follower resumes the stream from it.
     *
     * @return the last position the state covers, which the round copies up to
     */
    long captureState(ByteBuffer state);

    /**
     * Hands {@code consumer} the records from {@code fromPosition} to {@code toPosition}, both included, in position
     * order, until it returns false. A position the stream holds no record at is skipped.
     */
    void read(long fromPosition, long toPosition, RecordConsumer consumer);

    @FunctionalInterface
    interface RecordConsumer {

        /**
         * @param flags  {@link org.lolaf.staffix.ha.replication.wire.WireFormat#PLAINTEXT} or none
         * @param buffer holds the payload at {@code payloadIndex}, read during the call only and left untouched
         * @return false to stop before the next record
         */
        boolean onRecord(byte contentType, byte flags, long position, ByteBuffer buffer, int payloadIndex,
                         int payloadLength);
    }
}
