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

import lombok.AccessLevel;
import lombok.Getter;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.stores.FixMessagesStore.BatchingFixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.spi.ReplicaSink;
import org.lolaf.staffix.ha.wire.WireFormatException;

import java.nio.ByteBuffer;

/**
 * A FIX session replicated into this follower's store: a record's content type is its {@link StoreEventType} code
 * and its position its seqnum, a snapshot's state its next incoming and outgoing seqnums. Messages the store already
 * holds since its last reset are skipped, as a snapshot may have brought them before the log does.
 */
final class FixSessionReplica implements ReplicaSink {

    @Getter(AccessLevel.PACKAGE)
    private final FixSessionMessagesStore store;
    private final LostWrites lostWrites;
    private final StoreWrites storeWrites;
    /**
     * Since the store was last reset.
     */
    private long highestStoredSeqNum;

    FixSessionReplica(FixSessionMessagesStore store, String fixSessionId) {
        this.store = store;
        this.lostWrites = new LostWrites(store, fixSessionId);
        this.storeWrites = store instanceof BatchingFixSessionMessagesStore
                ? new BatchedStoreWrites((BatchingFixSessionMessagesStore) store, lostWrites)
                : new OneByOneStoreWrites(store, lostWrites);
    }

    @Override
    public void apply(byte contentType, long seqNum, ByteBuffer receiveBuffer, int messageStart, int messageLength) {
        switch (eventType(contentType)) {
            case STORE_MESSAGE:
                if (seqNum <= highestStoredSeqNum) {
                    break;
                }
                storeWrites.storeLiveMessage(seqNum, receiveBuffer, messageStart, messageLength);
                highestStoredSeqNum = seqNum;
                break;
            case STORE_OUTGOING_SEQ_NUM:
                storeWrites.setNextOutgoingSeqNum(seqNum);
                break;
            case STORE_INCOMING_SEQ_NUM:
                storeWrites.setNextIncomingSeqNum(seqNum);
                break;
            case RESET_SEQ_NUM:
                writePending();
                store.resetSequenceNumbers();
                lostWrites.reset();
                highestStoredSeqNum = 0;
                break;
            default:
                throw new WireFormatException("Unexpected store event " + eventType(contentType));
        }
    }

    @Override
    public void snapshotBegin(ByteBuffer state, int stateIndex, int stateLength) {
        checkState(stateLength);
        store.resetSequenceNumbers();
        lostWrites.reset();
        highestStoredSeqNum = 0;
        store.storeNextIncomingSeqNum(state.getLong(stateIndex));
        store.storeNextOutgoingSeqNum(state.getLong(stateIndex + Long.BYTES));
    }

    @Override
    public void snapshotRecord(byte contentType, long seqNum, ByteBuffer receiveBuffer, int messageStart,
                               int messageLength) {
        if (eventType(contentType) != StoreEventType.STORE_MESSAGE) {
            throw new WireFormatException("A snapshot of a FIX session carries messages only, got " + eventType(contentType));
        }
        storeWrites.storeSnapshotMessage(seqNum, receiveBuffer, messageStart, messageLength);
        highestStoredSeqNum = Math.max(highestStoredSeqNum, seqNum);
    }

    /**
     * Storing a message moved the outgoing seqnum, which the state's sets back where the leader's was.
     */
    @Override
    public void snapshotEnd(ByteBuffer state, int stateIndex, int stateLength) {
        checkState(stateLength);
        store.storeNextIncomingSeqNum(state.getLong(stateIndex));
        store.storeNextOutgoingSeqNum(state.getLong(stateIndex + Long.BYTES));
    }

    @Override
    public void writePending() {
        storeWrites.writePending();
    }

    @Override
    public void discardPending() {
        storeWrites.discardPending();
    }

    @Override
    public long getPendingWrites() {
        return store.getPendingWrites();
    }

    @Override
    public void stop(Deadline deadline) {
        store.stop(deadline);
    }

    /**
     * What the store holds, beside whether the session is in sync, which only the replication knows.
     */
    void describe(HaSessionStatus.HaSessionStatusBuilder status) {
        status.nextIncomingSeqNum(store.getIncomingSeqNum())
                .nextOutgoingSeqNum(store.getOutgoingSeqNum())
                .pendingWrites(getPendingWrites())
                .lostWrites(lostWrites.getCount() + store.getLostWrites());
    }

    private static void checkState(int stateLength) {
        if (stateLength != FixSessionSnapshotSource.STATE_BYTES) {
            throw new WireFormatException("A FIX session's snapshot state is " + FixSessionSnapshotSource.STATE_BYTES
                    + " bytes, got " + stateLength);
        }
    }

    private static StoreEventType eventType(byte contentType) {
        try {
            return StoreEventType.from(contentType);
        } catch (IllegalStateException e) {
            throw new WireFormatException("Unknown store event type " + contentType);
        }
    }
}
