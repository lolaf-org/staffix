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
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;

import java.nio.ByteBuffer;
import java.time.Duration;

/**
 * Appends one fragment of a message, so a test interleaves the fragments of one session's message with another's
 * events, as concurrent appends do.
 */
@UtilityClass
public class TestingLogFragments {

    public static void appendFragment(ReplicationLog log, int sessionKey, long seqNum, int messageTypeHash,
                                      ByteBuffer fragment, byte fragmentFlags) {
        StoreEventRecord record = new StoreEventRecord()
                .set(sessionKey, StoreEventType.STORE_MESSAGE, seqNum, messageTypeHash, fragment)
                .fragment(fragment.position(), fragment.remaining(), fragmentFlags);
        log.append(record, new LogFullWait(Duration.ofSeconds(1)));
    }
}
