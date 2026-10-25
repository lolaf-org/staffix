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
package org.lolaf.staffix.ha;

import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.api.ha.FixEngineHa.ReplicatingStore;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.leader.Followers;
import org.lolaf.staffix.ha.log.ReplicationLog;
import org.lolaf.staffix.ha.log.StoreEventRecord;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * A replicated session's store: each write is applied to the session's own store, then appended to the replication
 * log, in the order the session made them. Reads go straight to the session's store.
 *
 * <p>Relies on the engine's guarantees: the store it wraps never throws a storage failure, and the writes come from
 * the session's thread one at a time, which is what keeps the log in the order the writes were applied and lets one
 * {@link StoreEventRecord} serve them all.
 */
final class ReplicatingFixSessionMessagesStore implements ReplicatingStore {

    private final FixSessionMessagesStore store;
    private final ReplicationLog log;
    private final ReplicatedSession session;
    private final Followers followers;
    private final StoreEventRecord storeEventRecord;
    private final IdleStrategy ackIdle;
    private final ReplicatedAhead replicatedAhead;
    private long lastAppendedOffset = -1;

    ReplicatingFixSessionMessagesStore(FixSessionMessagesStore store, ReplicationLog log, ReplicatedSession session, Followers followers) {
        this.store = store;
        this.log = log;
        this.session = session;
        this.followers = followers;
        this.storeEventRecord = new StoreEventRecord();
        this.ackIdle = new BackoffIdleStrategy();
        this.replicatedAhead = new ReplicatedAhead();
    }

    /**
     * Appends what the session's store write will be once the message is sent, deciding as the session does: the
     * message itself when storable and not filtered, else the next outgoing seqnum.
     */
    @Override
    public void replicateSent(MessageType messageType, long outgoingSeqNum, ByteBuffer message) {
        if (messageType.isStorable() && !store.filter(messageType, message)) {
            append(StoreEventType.STORE_MESSAGE, outgoingSeqNum, message);
        } else {
            append(StoreEventType.STORE_OUTGOING_SEQ_NUM, outgoingSeqNum + 1, null);
        }
        replicatedAhead.add(outgoingSeqNum);
    }

    @Override
    public void awaitReplicated() {
        if (lastAppendedOffset >= 0) {
            session.acknowledged(followers.awaitApplied(session.getKey(), lastAppendedOffset, session.getReplicationAckTimeoutNanos(), ackIdle));
        }
    }

    @Override
    public FixSessionMessagesStore start() {
        store.start();
        return this;
    }

    @Override
    public FixSessionMessagesStore stop(Deadline stopDeadline) {
        store.stop(stopDeadline);
        return this;
    }

    @Override
    public boolean isStarted() {
        return store.isStarted();
    }

    @Override
    public boolean isUnderlyingStorageResourceAvailable() {
        return store.isUnderlyingStorageResourceAvailable();
    }

    @Override
    public String getUnderlyingStorageResourceDescription() {
        return store.getUnderlyingStorageResourceDescription();
    }

    @Override
    public Optional<String> getStorageIdentity() {
        return store.getStorageIdentity();
    }

    @Override
    public boolean filter(MessageType messageType, ByteBuffer message) {
        return store.filter(messageType, message);
    }

    @Override
    public void resetSequenceNumbers() {
        store.resetSequenceNumbers();
        replicatedAhead.clear();
        append(StoreEventType.RESET_SEQ_NUM, 0, null);
    }

    @Override
    public long getIncomingSeqNum() {
        return store.getIncomingSeqNum();
    }

    @Override
    public long getOutgoingSeqNum() {
        return store.getOutgoingSeqNum();
    }

    @Override
    public long getNextOutgoingSeqNum() {
        return store.getNextOutgoingSeqNum();
    }

    @Override
    public void storeNextIncomingSeqNum(long nextIncomingSeqNum) {
        store.storeNextIncomingSeqNum(nextIncomingSeqNum);
        append(StoreEventType.STORE_INCOMING_SEQ_NUM, nextIncomingSeqNum, null);
    }

    @Override
    public void storeNextOutgoingSeqNum(long nextOutgoingSeqNum) {
        store.storeNextOutgoingSeqNum(nextOutgoingSeqNum);
        if (replicatedAhead.remove(nextOutgoingSeqNum - 1)) {
            return;
        }
        append(StoreEventType.STORE_OUTGOING_SEQ_NUM, nextOutgoingSeqNum, null);
    }

    /**
     * The session's store may consume the message; the log is handed it as it came.
     */
    @Override
    public void storeMessageSent(long outgoingSeqNum, ByteBuffer message) {
        int position = message.position();
        int limit = message.limit();
        store.storeMessageSent(outgoingSeqNum, message);
        message.limit(limit).position(position);
        if (replicatedAhead.remove(outgoingSeqNum)) {
            return;
        }
        append(StoreEventType.STORE_MESSAGE, outgoingSeqNum, message);
    }

    @Override
    public void find(long startSequenceNumber, long stopSequenceNumber, StoreMessagesConsumer consumer) {
        store.find(startSequenceNumber, stopSequenceNumber, consumer);
    }

    /**
     * Never throws into the session: an event the log cannot take leaves the session needing a snapshot instead.
     */
    private void append(StoreEventType eventType, long sequenceNumber, ByteBuffer message) {
        try {
            lastAppendedOffset = log.append(storeEventRecord.set(session.getKey(), eventType, sequenceNumber, message));
        } catch (IllegalArgumentException e) {
            session.unreplicated(e.getMessage());
        }
    }

    /**
     * The outgoing seqnums replicated ahead of their store write, oldest first: a write of one of them is the one
     * already in the log. Seqnums only grow between resets, so one older than the write looked at is a write that
     * never came, and is dropped. One thread at a time, like the writes.
     */
    static final class ReplicatedAhead {

        private long[] seqNums = new long[16];
        private int head;
        private int size;

        void add(long seqNum) {
            if (size == seqNums.length) {
                long[] grown = new long[seqNums.length * 2];
                for (int i = 0; i < size; i++) {
                    grown[i] = seqNums[(head + i) % seqNums.length];
                }
                seqNums = grown;
                head = 0;
            }
            seqNums[(head + size) % seqNums.length] = seqNum;
            size++;
        }

        /**
         * @return whether {@code seqNum} was replicated ahead, and is now taken off
         */
        boolean remove(long seqNum) {
            while (size > 0 && seqNums[head] < seqNum) {
                pop();
            }
            if (size > 0 && seqNums[head] == seqNum) {
                pop();
                return true;
            }
            return false;
        }

        void clear() {
            head = 0;
            size = 0;
        }

        private void pop() {
            head = (head + 1) % seqNums.length;
            size--;
        }
    }
}
