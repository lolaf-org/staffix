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
package org.lolaf.staffix.tests;

import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.stores.FixMessagesStore;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.IntFunction;

public class TestingFixSessionMessagesStore extends Startable.VoidStartable<FixMessagesStore.FixSessionMessagesStore> implements FixMessagesStore.FixSessionMessagesStore {

    private final AtomicLong outgoingSequenceNumber;
    private final AtomicLong incomingSequenceNumber;
    private final Map<Long, ByteBuffer> sentMessages;
    private final IntFunction<ByteBuffer> allocator;
    private final int maxEntriesInMemory;
    private final BiPredicate<MessageType, ByteBuffer> messagesFilter;

    public TestingFixSessionMessagesStore(BiPredicate<MessageType, ByteBuffer> messagesFilter) {
        allocator = ByteBuffer::allocate;
        sentMessages = new ConcurrentHashMap<>(1024);
        outgoingSequenceNumber = new AtomicLong(1);
        incomingSequenceNumber = new AtomicLong(1);
        maxEntriesInMemory = 1024;
        this.messagesFilter = messagesFilter;
    }

    public TestingFixSessionMessagesStore() {
        this((mt, m) -> false);
    }

    @Override
    public boolean filter(MessageType messageType, ByteBuffer message) {
        return messagesFilter.test(messageType, message);
    }

    @Override
    public void resetSequenceNumbers() {
        outgoingSequenceNumber.set(1);
        incomingSequenceNumber.set(1);
        sentMessages.clear();
    }

    public void clearMessages() {
        sentMessages.clear();
    }

    @Override
    public long getIncomingSeqNum() {
        return incomingSequenceNumber.get();
    }

    @Override
    public long getOutgoingSeqNum() {
        return outgoingSequenceNumber.get();
    }

    @Override
    public long getNextOutgoingSeqNum() throws StoreException {
        return outgoingSequenceNumber.getAndIncrement();
    }

    public void setCurrentOutgoingSeqNum(long newSeqNum) {
        outgoingSequenceNumber.set(newSeqNum);
    }

    public void setCurrentIncomingSeqNum(long newSeqNum) {
        incomingSequenceNumber.set(newSeqNum);
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
        ByteBuffer copy = allocator.apply(message.limit());
        sentMessages.put(outgoingSeqNum, copy.put(message).flip());
        if (sentMessages.size() > maxEntriesInMemory) {
            sentMessages.remove(outgoingSeqNum - maxEntriesInMemory);
        }
        outgoingSequenceNumber.set(outgoingSeqNum + 1);
    }

    @Override
    public void find(long startSequenceNumber, long stopSequenceNumber, StoreMessagesConsumer consumer) {
        List<StoreMessage> messages = new ArrayList<>(32);
        sentMessages.entrySet().stream()
                .filter(e -> e.getKey() >= startSequenceNumber && e.getKey() <= stopSequenceNumber)
                .forEach(e -> messages.add(new StoreMessage(e.getKey(), e.getValue())));

        messages.sort(Comparator.comparingLong(StoreMessage::getSeqNum));
        for (StoreMessage message : messages) {
            if (!consumer.onMessage(message.getSeqNum(), message.getMessage())) {
                return;
            }
        }
    }
}