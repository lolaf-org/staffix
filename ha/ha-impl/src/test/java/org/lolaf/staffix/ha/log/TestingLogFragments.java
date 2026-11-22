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

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;
import java.time.Duration;

/**
 * Appends store events as the FIX side does, or one fragment of a message, so a test interleaves the fragments of one
 * session's message with another's events, as concurrent appends do.
 */
@UtilityClass
public class TestingLogFragments {

    public static void appendFragment(ReplicationLog log, int streamKey, long seqNum, byte flags, ByteBuffer fragment,
                                      byte fragmentFlags) {
        LogRecord record = new LogRecord()
                .set(streamKey, StoreEventType.STORE_MESSAGE.getCode(), flags, seqNum, fragment)
                .fragment(fragment.position(), fragment.remaining(), fragmentFlags);
        log.append(record, new LogFullWait(Duration.ofSeconds(1)));
    }

    /**
     * A store event as the FIX side appends it: its type's code as content type, its seqnum as position.
     */
    public static LogRecord storeEvent(LogRecord record, int streamKey, StoreEventType eventType, long seqNum,
                                       @Nullable ByteBuffer message) {
        byte flags = eventType == StoreEventType.RESET_SEQ_NUM ? WireFormat.RESET : 0;
        return record.set(streamKey, eventType.getCode(), flags, seqNum, message);
    }
}
