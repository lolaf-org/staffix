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
package org.lolaf.staffix.ha.wire;

import lombok.experimental.UtilityClass;

/**
 * The byte layout of the replication stream between a leader and its follower, over TCP.
 *
 * <pre>
 * offset  size  field
 *      0     4  frame length, header included
 *      4     1  frame type
 *      5     1  flags, per frame type; unused bits sent zero, ignored on receive
 *      6     2  reserved, sent zero
 *      8     8  epoch of the sender's leadership view, fencing a deposed leader
 *     16     -  payload, per frame type
 * </pre>
 *
 * <p>Big-endian throughout, {@link java.nio.ByteBuffer}'s default, so nothing calls {@code order()}. Strings are
 * UTF-8, preceded by their length in an unsigned short.
 */
@UtilityClass
public class WireFormat {

    public static final int OFFSET_LENGTH = 0;
    public static final int OFFSET_TYPE = 4;
    public static final int OFFSET_FLAGS = 5;
    public static final int OFFSET_RESERVED = 6;
    public static final int OFFSET_EPOCH = 8;
    public static final int HEADER_BYTES = 16;

    /**
     * Where the body of a {@link FrameType#STORE_EVENT} starts: past the header and the log offset.
     */
    public static final int STORE_EVENT_BODY_OFFSET = HEADER_BYTES + Long.BYTES;

    /**
     * Opens {@link FrameType#HELLO} and {@link FrameType#WELCOME}, so a peer that is not a staffix replication
     * endpoint is refused before anything else is read: 'SFHA'.
     */
    public static final int MAGIC = 0x53464841;

    /**
     * Bumped only for an incompatible layout change; a peer of another version is closed, never guessed at.
     */
    public static final short VERSION = 1;

    /**
     * {@link FrameType#WELCOME}'s resume offset when the follower's position is no longer in the leader's log, so
     * every session is caught up by snapshot.
     */
    public static final long NO_RESUME = -1;

    public static final int MAX_STRING_BYTES = 0xFFFF;

    /**
     * {@link FrameType#SESSION}: the leader's store has a storage identity.
     */
    public static final int FLAG_STORAGE_IDENTITY = 0x01;

    /**
     * {@link FrameType#ACK}: the follower has applied everything it needs to take over.
     */
    public static final int FLAG_IN_SYNC = 0x01;

    /**
     * The size of a buffer holding frames up to {@code maxFrameBytes}, and many small ones at once. A multiple of 64,
     * the only buffer sizes betty pools.
     */
    public static int bufferBytes(int maxFrameBytes) {
        return (Math.max(64 * 1024, maxFrameBytes) + 63) & ~63;
    }
}
