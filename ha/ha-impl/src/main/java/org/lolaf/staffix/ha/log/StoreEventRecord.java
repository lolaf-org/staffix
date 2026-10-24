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

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;

import java.nio.ByteBuffer;

/**
 * One store event to append, reused by its writer for every event so appending allocates nothing. Owned by one
 * thread at a time.
 */
@Getter
public final class StoreEventRecord {

    private int sessionKey;
    private StoreEventType eventType = StoreEventType.STORE_MESSAGE;
    private long sequenceNumber;
    private @Nullable ByteBuffer message;

    /**
     * @param message the bytes from its position to its limit, read during the append and left untouched; null for
     *                an event carrying none
     */
    public StoreEventRecord set(int sessionKey, StoreEventType eventType, long sequenceNumber, @Nullable ByteBuffer message) {
        this.sessionKey = sessionKey;
        this.eventType = eventType;
        this.sequenceNumber = sequenceNumber;
        this.message = message;
        return this;
    }

    int messageLength() {
        return message == null ? 0 : message.remaining();
    }
}
