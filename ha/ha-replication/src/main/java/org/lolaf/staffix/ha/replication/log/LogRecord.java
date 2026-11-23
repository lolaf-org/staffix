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
import org.lolaf.staffix.ha.replication.wire.WireFormat;

import java.nio.ByteBuffer;

/**
 * One record to append, reused by its writer for every record so appending allocates nothing. Owned by one thread at
 * a time.
 */
@Getter
public final class LogRecord {

    private int streamKey;
    private byte contentType;
    private byte flags;
    private long position;
    private @Nullable ByteBuffer buffer;
    private int payloadIndex;
    private int payloadLength;
    private int fragmentIndex;
    private int fragmentLength;
    private byte fragmentFlags;

    /**
     * @param contentType the application's, carried untouched to the follower
     * @param flags       {@link WireFormat#PLAINTEXT} and {@link WireFormat#RESET}; the log adds the fragment flags
     * @param buffer      holds the payload at {@code payloadIndex}, read during the append and left untouched; null for
     *                    a record carrying none
     */
    public LogRecord set(int streamKey, byte contentType, byte flags, long position, @Nullable ByteBuffer buffer,
                         int payloadIndex, int payloadLength) {
        if (buffer == null && payloadLength != 0) {
            throw new IllegalArgumentException("A payload of " + payloadLength + " bytes needs a buffer");
        }
        this.streamKey = streamKey;
        this.contentType = contentType;
        this.flags = flags;
        this.position = position;
        this.buffer = buffer;
        this.payloadIndex = payloadIndex;
        this.payloadLength = payloadLength;
        return fragment(payloadIndex, payloadLength, (byte) 0);
    }

    /**
     * Narrows the record to one fragment of its payload, as the log appends it.
     */
    LogRecord fragment(int fragmentIndex, int fragmentLength, byte fragmentFlags) {
        this.fragmentIndex = fragmentIndex;
        this.fragmentLength = fragmentLength;
        this.fragmentFlags = fragmentFlags;
        return this;
    }
}
