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
package org.lolaf.staffix.stores.messages.async;

import lombok.Getter;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.stores.core.async.AsyncEvent;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

@Getter
class AsyncStoreEvent implements FixMessagesStore.BatchingFixSessionMessagesStore.SentFixMessage, AsyncEvent {

    private final Consumer<AsyncStoreEvent> released;

    private ByteBuffer message;
    private long sequenceNumber;
    private FixMessagesStore.StoreEventType storeEvent;

    public AsyncStoreEvent(ByteBuffer message, Consumer<AsyncStoreEvent> released) {
        this.released = released == null ? b -> {
            // nothing to do
        } : released;
        this.message = message;
    }

    public void from(FixMessagesStore.StoreEventType eventType, long sequenceNumber, ByteBuffer message) {
        this.sequenceNumber = sequenceNumber;
        this.message = message;
        this.storeEvent = eventType;
    }

    public void from(FixMessagesStore.StoreEventType eventType, long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
        this.storeEvent = eventType;
    }

    @Override
    public void release() {
        released.accept(this);
    }
}