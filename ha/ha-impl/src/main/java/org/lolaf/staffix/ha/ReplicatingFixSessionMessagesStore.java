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
    private final StoreEventRecord record = new StoreEventRecord();
    private final IdleStrategy ackIdle = new BackoffIdleStrategy();
    private long lastAppendedOffset = -1;

    ReplicatingFixSessionMessagesStore(FixSessionMessagesStore store, ReplicationLog log, ReplicatedSession session, Followers followers) {
        this.store = store;
        this.log = log;
        this.session = session;
        this.followers = followers;
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
            lastAppendedOffset = log.append(record.set(session.getKey(), eventType, sequenceNumber, message));
        } catch (IllegalArgumentException e) {
            session.unreplicated(e.getMessage());
        }
    }
}
