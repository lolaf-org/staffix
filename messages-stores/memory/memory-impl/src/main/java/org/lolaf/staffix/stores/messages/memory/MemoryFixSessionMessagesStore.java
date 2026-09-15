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
package org.lolaf.staffix.stores.messages.memory;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.rb.RingBuffer;
import org.lolaf.ringos.rb.RingBufferFactory;
import org.lolaf.ringos.unsafe.UnsafeOperations;
import org.lolaf.ringos.unsafe.UnsafeOperationsApi;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.stores.FixMessagesStore;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.IntFunction;

@Slf4j
class MemoryFixSessionMessagesStore extends Startable.VoidStartable<FixMessagesStore.FixSessionMessagesStore> implements FixMessagesStore.FixSessionMessagesStore {

    private final AtomicLong outgoingSequenceNumber;
    private final AtomicLong incomingSequenceNumber;
    private final IntFunction<ByteBuffer> allocator;
    private final BiPredicate<MessageType, ByteBuffer> messageFilter;
    private final Consumer<ByteBuffer> byteBufferCleaner;
    private final boolean enabledMessageStoring;
    private final RingBuffer<StoredMessage> storeMessages;

    public MemoryFixSessionMessagesStore(MemoryMessageStoreSettings settings) {
        allocator = settings.isUseDirectMemory() ? ByteBuffer::allocateDirect : ByteBuffer::allocate;
        enabledMessageStoring = settings.getMaxEntriesInMemory() > 0;
        outgoingSequenceNumber = new AtomicLong(1);
        incomingSequenceNumber = new AtomicLong(1);
        messageFilter = settings.getMessageFilter();
        byteBufferCleaner = settings.isUseDirectMemory() ? this::doClean : this::doNotClean;
        if (enabledMessageStoring) {
            storeMessages = RingBufferFactory.build(RingBufferFactory.AccessType.SINGLE_CONSUMER_SINGLE_PRODUCER, settings.getMaxEntriesInMemory());
            while (!storeMessages.isFull()) {
                storeMessages.offer(new StoredMessage());
            }
        } else {
            storeMessages = null;
        }
    }

    private void doNotClean(ByteBuffer bb) {
        // nothing to do
    }

    private void doClean(ByteBuffer bb) {
        UnsafeOperationsApi.ifAvailableDo(UnsafeOperations::invokeCleaner, bb);
    }

    @Override
    public boolean filter(MessageType messageType, ByteBuffer message) {
        return messageFilter.test(messageType, message);
    }

    @Override
    public void resetSequenceNumbers() {
        outgoingSequenceNumber.set(1);
        incomingSequenceNumber.set(1);
        if (enabledMessageStoring) {
            storeMessages.forEachEntry(s -> s.seqNum = Long.MIN_VALUE);
        }
    }

    @Override
    public long getIncomingSeqNum() {
        return incomingSequenceNumber.get();
    }

    @Override
    public long getNextOutgoingSeqNum() throws StoreException {
        return outgoingSequenceNumber.getAndIncrement();
    }

    @Override
    public long getOutgoingSeqNum() {
        return outgoingSequenceNumber.get();
    }

    @Override
    public void storeNextIncomingSeqNum(long nextIncomingSeqNum) {
        incomingSequenceNumber.set(nextIncomingSeqNum);
    }

    @Override
    public void storeNextOutgoingSeqNum(long nextOutgoingSeqNum) {
        outgoingSequenceNumber.set(nextOutgoingSeqNum);
    }

    @Override
    public void storeMessageSent(long outgoingSeqNum, ByteBuffer message) {
        outgoingSequenceNumber.set(outgoingSeqNum + 1);
        if (!enabledMessageStoring) {
            return;
        }
        StoredMessage event = storeMessages.poll();
        event.seqNum = outgoingSeqNum;
        if (event.message != null) {
            if (event.message.capacity() < message.remaining()) {
                byteBufferCleaner.accept(event.message);
                event.message = allocator.apply(message.remaining());
            }
        } else {
            event.message = allocator.apply(message.remaining());
        }
        event.message.clear().put(message).flip();
        storeMessages.offer(event);
    }

    @Override
    public void find(long startSequenceNumber, long stopSequenceNumber, StoreMessagesConsumer consumer) {
        if (!enabledMessageStoring) {
            return;
        }
        // the ring holds them in no particular order, so the matching ones are collected before being handed over:
        // the contract is ascending MsgSeqNum(34), and this store keeps everything in memory anyway
        List<StoreMessage> messages = new ArrayList<>(32);
        storeMessages.forEach(e -> {
            if (e.seqNum >= startSequenceNumber && e.seqNum <= stopSequenceNumber) {
                messages.add(new StoreMessage(e.seqNum, e.message));
            }
        });
        messages.sort(Comparator.comparingLong(StoreMessage::getSeqNum));
        for (StoreMessage message : messages) {
            if (!consumer.onMessage(message.getSeqNum(), message.getMessage())) {
                return;
            }
        }
    }

    private static final class StoredMessage {
        private long seqNum = Long.MIN_VALUE;
        private ByteBuffer message;
    }
}
