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
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.stores.FixMessagesStore;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.IntFunction;

@Slf4j
class MemoryFixSessionMessagesStore extends Startable.VoidStartable<FixMessagesStore.FixSessionMessagesStore> implements FixMessagesStore.FixSessionMessagesStore {

    private final AtomicLong outgoingSequenceNumber;
    private final AtomicLong incomingSequenceNumber;
    private final IntFunction<ByteBuffer> allocator;
    private final BiPredicate<MessageType, ByteBuffer> messageFilter;
    private final boolean enabledMessageStoring;
    private final StoredMessage[] storedMessages;
    private final Optional<String> storageIdentity = Optional.of("memory:" + UUID.randomUUID());

    public MemoryFixSessionMessagesStore(MemoryMessageStoreSettings settings) {
        allocator = settings.isUseDirectMemory() ? ByteBuffer::allocateDirect : ByteBuffer::allocate;
        enabledMessageStoring = settings.getMaxEntriesInMemory() > 0;
        outgoingSequenceNumber = new AtomicLong(1);
        incomingSequenceNumber = new AtomicLong(1);
        messageFilter = settings.getMessageFilter();
        if (enabledMessageStoring) {
            storedMessages = new StoredMessage[settings.getMaxEntriesInMemory()];
            Arrays.setAll(storedMessages, index -> new StoredMessage());
        } else {
            storedMessages = null;
        }
    }

    @Override
    public Optional<String> getStorageIdentity() {
        return storageIdentity;
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
            for (StoredMessage stored : storedMessages) {
                stored.clear();
            }
        }
    }

    @Override
    public long getIncomingSeqNum() {
        return incomingSequenceNumber.get();
    }

    @Override
    public long getNextOutgoingSeqNum() {
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
        storedMessages[(int) Math.floorMod(outgoingSeqNum, (long) storedMessages.length)].store(outgoingSeqNum, message, allocator);
    }

    @Override
    public void find(long startSequenceNumber, long stopSequenceNumber, StoreMessagesConsumer consumer) {
        if (!enabledMessageStoring) {
            return;
        }
        // collected before being handed over, the contract being ascending MsgSeqNum(34): the slots wrap around, and a
        // sequence number moved ahead leaves older messages in the slots it skipped
        List<StoreMessage> messages = new ArrayList<>(32);
        for (StoredMessage stored : storedMessages) {
            StoreMessage copy = stored.copyIfIn(startSequenceNumber, stopSequenceNumber);
            if (copy != null) {
                messages.add(copy);
            }
        }
        messages.sort(Comparator.comparingLong(StoreMessage::getSeqNum));
        for (StoreMessage message : messages) {
            if (!consumer.onMessage(message.getSeqNum(), message.getMessage())) {
                return;
            }
        }
    }

    /**
     * Written by the session while resends and replication snapshots copy it on threads of their own: a seqlock, so a
     * copy torn by a write is dropped, and gap filled, rather than handed over. A buffer outgrown is left to the
     * garbage collector instead of being freed, since a copy in progress may still read it.
     */
    private static final class StoredMessage {

        private static final VarHandle VERSION;

        static {
            try {
                VERSION = MethodHandles.lookup().findVarHandle(StoredMessage.class, "version", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        /**
         * Odd while a write is in progress. Release and acquire rather than volatile: a full fence on every message
         * stored would cost the session's write path.
         */
        private long version;
        private long seqNum = Long.MIN_VALUE;
        private ByteBuffer message;
        private int length;

        void store(long seqNum, ByteBuffer message, IntFunction<ByteBuffer> allocator) {
            long writing = version + 1;
            VERSION.setOpaque(this, writing);
            VarHandle.storeStoreFence();
            this.seqNum = seqNum;
            this.length = message.remaining();
            if (this.message == null || this.message.capacity() < length) {
                this.message = allocator.apply(length);
            }
            this.message.clear().put(message).flip();
            VERSION.setRelease(this, writing + 1);
        }

        void clear() {
            long writing = version + 1;
            VERSION.setOpaque(this, writing);
            VarHandle.storeStoreFence();
            seqNum = Long.MIN_VALUE;
            VERSION.setRelease(this, writing + 1);
        }

        StoreMessage copyIfIn(long startSequenceNumber, long stopSequenceNumber) {
            long before = (long) VERSION.getAcquire(this);
            long copiedSeqNum = seqNum;
            ByteBuffer stored = message;
            int copiedLength = length;
            if ((before & 1) != 0 || copiedSeqNum < startSequenceNumber || copiedSeqNum > stopSequenceNumber
                    || stored == null || copiedLength > stored.capacity()) {
                return null;
            }
            byte[] bytes = new byte[copiedLength];
            ByteBuffer source = stored.duplicate();
            source.clear().limit(copiedLength);
            source.get(bytes);
            VarHandle.loadLoadFence();
            return (long) VERSION.getAcquire(this) == before ? new StoreMessage(copiedSeqNum, ByteBuffer.wrap(bytes)) : null;
        }
    }
}
