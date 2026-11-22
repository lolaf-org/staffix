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

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.replication.journal.JournalStream;
import org.lolaf.staffix.ha.replication.wire.WireFormat;

import java.nio.ByteBuffer;
import java.time.Duration;

/**
 * Journal entries to append, or one fragment of an entry, so a test interleaves the fragments of one stream's entry with
 * another's, as concurrent appends do.
 */
@UtilityClass
public class TestingLogFragments {

    public static void appendFragment(ReplicationLog log, int streamKey, long position, byte flags, ByteBuffer fragment,
                                      byte fragmentFlags) {
        LogRecord record = new LogRecord()
                .set(streamKey, JournalStream.TEXT, flags, position, fragment)
                .fragment(fragment.position(), fragment.remaining(), fragmentFlags);
        log.append(record, new LogFullWait(Duration.ofSeconds(1)));
    }

    /**
     * @param payload null for an entry carrying none
     */
    public static LogRecord entry(LogRecord record, int streamKey, long position, @Nullable ByteBuffer payload) {
        return record.set(streamKey, JournalStream.TEXT, (byte) 0, position, payload);
    }

    public static LogRecord reset(LogRecord record, int streamKey) {
        return record.set(streamKey, JournalStream.TEXT, WireFormat.RESET, 0, null);
    }
}
