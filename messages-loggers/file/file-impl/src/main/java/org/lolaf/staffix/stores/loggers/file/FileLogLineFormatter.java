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
package org.lolaf.staffix.stores.loggers.file;

import org.jspecify.annotations.NullMarked;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.time.UTCTime;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

/**
 * Writes the lines of one session, on the logger's thread only, so it needs no locking.
 * <p>
 * Each method writes one whole line, newline included, into {@code line} from its position, and reads the message or
 * the event from its position to its limit. A line that does not fit throws {@link BufferOverflowException}: the
 * logger then calls again with a buffer twice the size and the message rewound, so a call must leave no state behind.
 * Separate incoming and outgoing methods let a format precompute both rather than branch on the direction per line.
 */
@NullMarked
public interface FileLogLineFormatter {

    void formatIncoming(UTCTime time, MessageType messageType, ByteBuffer message, ByteBuffer line);

    void formatOutgoing(UTCTime time, MessageType messageType, ByteBuffer message, ByteBuffer line);

    /**
     * A session event such as a logon or a disconnection, its text encoded as the logger encodes it.
     */
    void formatEvent(UTCTime time, ByteBuffer event, ByteBuffer line);
}
