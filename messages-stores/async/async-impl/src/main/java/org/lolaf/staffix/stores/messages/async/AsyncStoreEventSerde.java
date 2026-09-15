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

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.ValueIn;
import net.openhft.chronicle.wire.ValueOut;
import net.openhft.chronicle.wire.Wire;
import org.lolaf.ringos.unsafe.UnsafeOperations;
import org.lolaf.ringos.unsafe.UnsafeOperationsApi;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.stores.core.async.AsyncEventInstanceProvider;
import org.lolaf.staffix.stores.core.async.AsyncEventSerde;

import java.nio.ByteBuffer;

class AsyncStoreEventSerde implements AsyncEventSerde<AsyncStoreEvent> {


    private final AsyncStoreEvent storeMessage;

    public AsyncStoreEventSerde(AsyncEventInstanceProvider<AsyncStoreEvent> asyncEventInstanceProvider) {
        this.storeMessage = asyncEventInstanceProvider.instanciateAsyncEvent(null);
    }

    public AsyncStoreEvent storeMessage(long outgoingSequenceNumber, ByteBuffer message) {
        storeMessage.from(FixMessagesStore.StoreEventType.STORE_MESSAGE, outgoingSequenceNumber, message);
        return storeMessage;
    }

    public AsyncStoreEvent storeSeqNum(FixMessagesStore.StoreEventType eventType, long sequenceNumber) {
        storeMessage.from(eventType, sequenceNumber);
        return storeMessage;
    }

    public AsyncStoreEvent resetSeqNum() {
        storeMessage.from(FixMessagesStore.StoreEventType.RESET_SEQ_NUM, 0);
        return storeMessage;
    }

    @Override
    public void deserialize(DocumentContext documentContext, AsyncStoreEvent deserializeTarget) {
        Wire wire = documentContext.wire();
        ValueIn in = wire.getValueIn();
        FixMessagesStore.StoreEventType eventType = FixMessagesStore.StoreEventType.from(in.readByte());
        if (eventType.equals(FixMessagesStore.StoreEventType.STORE_MESSAGE)) {
            deserializeTarget.from(eventType, in.readLong(), readMessage(wire, deserializeTarget.getMessage()));
        } else {
            deserializeTarget.from(eventType, in.readLong());
        }
    }

    @Override
    public void serialize(DocumentContext documentContext, AsyncStoreEvent toSerialize) {
        Wire wire = documentContext.wire();
        ValueOut out = wire.getValueOut();
        out.writeByte(toSerialize.getStoreEvent().getCode());
        out.writeLong(toSerialize.getSequenceNumber());
        if (toSerialize.getStoreEvent().equals(FixMessagesStore.StoreEventType.STORE_MESSAGE)) {
            Bytes<?> documentBytes = wire.bytes();
            ByteBuffer message = toSerialize.getMessage();
            documentBytes.write(documentBytes.writePosition(), message, message.position(), message.limit());
            documentBytes.writePosition(documentBytes.writePosition() + message.remaining());
        }
    }

    private ByteBuffer readMessage(Wire wire, ByteBuffer logByteBuffer) {
        logByteBuffer.clear();
        Bytes<?> documentBytes = wire.bytes();
        int readRemaining = (int) documentBytes.readRemaining();
        if (readRemaining > logByteBuffer.capacity()) {
            UnsafeOperationsApi.ifAvailableDo(UnsafeOperations::invokeCleanerIfNeeded, logByteBuffer);
            logByteBuffer = logByteBuffer.isDirect()
                    ? ByteBuffer.allocateDirect(readRemaining) : ByteBuffer.allocate(readRemaining);
        }
        documentBytes.read(logByteBuffer);
        return logByteBuffer.flip();
    }
}
