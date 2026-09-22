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

import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.stores.FixMessagesStore;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.IntFunction;

/**
 * Refuses writes once stopped, as a real store would, and records each one: the engine's fail-safe wrapper only logs
 * the exception, so {@link #getWritesRefusedWhileStopped()} is how a test sees them.
 */
public class TestingFixSessionMessagesStore implements FixMessagesStore.FixSessionMessagesStore {

    private final AtomicLong outgoingSequenceNumber;
    private final AtomicLong incomingSequenceNumber;
    private final Map<Long, ByteBuffer> sentMessages;
    private final IntFunction<ByteBuffer> allocator;
    private final int maxEntriesInMemory;
    private final BiPredicate<MessageType, ByteBuffer> messagesFilter;
    private final List<String> writesRefusedWhileStopped = new CopyOnWriteArrayList<>();
    private volatile boolean stopped;

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
    public FixMessagesStore.FixSessionMessagesStore start() {
        stopped = false;
        return this;
    }

    @Override
    public FixMessagesStore.FixSessionMessagesStore stop(Deadline stopDeadline) {
        stopped = true;
        return this;
    }

    @Override
    public boolean isStarted() {
        return !stopped;
    }

    public List<String> getWritesRefusedWhileStopped() {
        return writesRefusedWhileStopped;
    }

    private void refuseIfStopped(String write) {
        if (stopped) {
            writesRefusedWhileStopped.add(write);
            throw new StoreException(write + " on a stopped store");
        }
    }

    @Override
    public boolean filter(MessageType messageType, ByteBuffer message) {
        return messagesFilter.test(messageType, message);
    }

    @Override
    public void resetSequenceNumbers() {
        refuseIfStopped("resetSequenceNumbers");
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
        refuseIfStopped("getNextOutgoingSeqNum");
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
        refuseIfStopped("storeNextIncomingSeqNum " + nextIncomingSeqNum);
        incomingSequenceNumber.set(nextIncomingSeqNum);
    }

    @Override
    public void storeNextOutgoingSeqNum(long nextOutgoingSeqNum) {
        refuseIfStopped("storeNextOutgoingSeqNum " + nextOutgoingSeqNum);
        outgoingSequenceNumber.set(nextOutgoingSeqNum);
    }

    @Override
    public void storeMessageSent(long outgoingSeqNum, ByteBuffer message) {
        refuseIfStopped("storeMessageSent " + outgoingSeqNum);
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