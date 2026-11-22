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
    private @Nullable ByteBuffer payload;
    private int fragmentIndex;
    private int fragmentLength;
    private byte fragmentFlags;

    /**
     * @param contentType the application's, carried untouched to the follower
     * @param flags       {@link WireFormat#PLAINTEXT} and {@link WireFormat#RESET}; the log adds the fragment flags
     * @param payload     the bytes from its position to its limit, read during the append and left untouched; null for
     *                    a record carrying none
     */
    public LogRecord set(int streamKey, byte contentType, byte flags, long position, @Nullable ByteBuffer payload) {
        this.streamKey = streamKey;
        this.contentType = contentType;
        this.flags = flags;
        this.position = position;
        this.payload = payload;
        return fragment(payload == null ? 0 : payload.position(), payloadLength(), (byte) 0);
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

    int payloadLength() {
        return payload == null ? 0 : payload.remaining();
    }
}
