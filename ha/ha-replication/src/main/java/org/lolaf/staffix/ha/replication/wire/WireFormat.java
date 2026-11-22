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
package org.lolaf.staffix.ha.replication.wire;

import lombok.experimental.UtilityClass;

/**
 * The byte layout of the replication stream between a leader and its follower, over TCP.
 *
 * <pre>
 * offset  size  field
 *      0     4  frame length, header and tag included
 *      4     1  frame type
 *      5     1  flags, per frame type; unused bits sent zero, ignored on receive
 *      6     2  reserved, sent zero
 *      8     8  epoch of the sender's leadership view, fencing a deposed leader
 *     16     -  payload, per frame type
 * </pre>
 *
 * <p>On an encrypted connection, every frame after the handshake ends with a {@link #TAG_BYTES} tag over all of it,
 * its variable part encrypted; see {@link FrameCipher}. The follower's frames from the one after its KEY_CONFIRMATION;
 * the leader's from the first it sends once it verified it, a refusal before then going without, since the keys may
 * differ.
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
     * Where the body of a {@link FrameType#RECORD} starts: past the header and the log offset.
     */
    public static final int RECORD_BODY_OFFSET = HEADER_BYTES + Long.BYTES;

    /**
     * Where a record body's flags are, from the body's start.
     */
    public static final int RECORD_BODY_FLAGS = 5;

    /**
     * Where a {@link FrameType#RECORD}'s flags are in its frame.
     */
    public static final int OFFSET_RECORD_FLAGS = RECORD_BODY_OFFSET + RECORD_BODY_FLAGS;

    /**
     * Set in a record's flags when more fragments of its payload follow. A payload longer than a frame is sent as
     * consecutive records of its stream: the first with this bit, the middle ones with both, the last with
     * {@link #CONTINUED_FRAGMENT} alone, so a last fragment never reads as a whole payload.
     */
    public static final byte MORE_FRAGMENTS = 0x01;

    /**
     * Set on every fragment but a payload's first, as {@link #MORE_FRAGMENTS}.
     */
    public static final byte CONTINUED_FRAGMENT = 0x02;

    /**
     * The application lets the record's payload go unencrypted on an encrypted connection; the frame is still
     * authenticated, this bit included. Meaningless on a clear connection, where nothing is encrypted.
     */
    public static final byte PLAINTEXT = 0x04;

    /**
     * The stream starts over with this record: a snapshot of the stream in progress ends before it.
     */
    public static final byte RESET = 0x08;

    /**
     * Opens {@link FrameType#HELLO} and {@link FrameType#WELCOME}, so a peer that is not a replication endpoint is
     * refused before anything else is read: 'REPL'.
     */
    public static final int MAGIC = 0x5245504C;

    /**
     * Bumped only for an incompatible layout change; a peer of another version is closed, never guessed at.
     */
    public static final short VERSION = 1;

    /**
     * {@link FrameType#WELCOME}'s resume offset when the follower's position is no longer in the leader's log, so
     * every stream is caught up by snapshot.
     */
    public static final long NO_RESUME = -1;

    public static final int MAX_STRING_BYTES = 0xFFFF;

    /**
     * The longest stream state a snapshot carries, its length being an unsigned short.
     */
    public static final int MAX_STATE_BYTES = 0xFFFF;

    /**
     * The random nonce of {@link FrameType#HELLO} and {@link FrameType#WELCOME}, zeros from a peer that does not
     * encrypt: both nonces make each connection's keys new.
     */
    public static final int NONCE_BYTES = 16;

    /**
     * The HMAC-SHA256 of {@link FrameType#KEY_CONFIRMATION}.
     */
    public static final int KEY_CONFIRMATION_BYTES = 32;

    /**
     * The tag ending an authenticated frame, whatever the cipher.
     */
    public static final int TAG_BYTES = 16;

    /**
     * {@link FrameType#STREAM}: the leader's store has a storage identity.
     */
    public static final int FLAG_STORAGE_IDENTITY = 0x01;

    /**
     * {@link FrameType#ACK}: the follower has applied everything it needs to take over.
     */
    public static final int FLAG_IN_SYNC = 0x01;

    /**
     * Initial read buffer of a connection, holding many small frames per read. Betty grows it for a larger frame, up
     * to the connection's frame limit, so a connection pays for its largest frame only once it receives one.
     */
    public static final int READ_BUFFER_BYTES = 64 * 1024;

    /**
     * The size of a buffer holding frames up to {@code maxFrameBytes}, and many small ones at once. A multiple of 64,
     * the only buffer sizes betty pools.
     */
    public static int bufferBytes(int maxFrameBytes) {
        return (Math.max(64 * 1024, maxFrameBytes) + 63) & ~63;
    }
}
