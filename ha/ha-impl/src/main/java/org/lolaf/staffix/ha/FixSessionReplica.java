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
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreMessage;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.replicos.spi.RecordFlag;
import org.lolaf.replicos.spi.ReplicaSink;
import org.lolaf.replicos.spi.WireFormatException;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A FIX session replicated into this follower's store: a record's content type is its {@link StoreEventType} code
 * and its position its seqnum, a snapshot's state its next incoming and outgoing seqnums. Messages the store already
 * holds since its last reset are skipped, as a snapshot may have brought them before the log does. Reads its last
 * messages back for {@link org.lolaf.replicos.CatchUp#byLastRecords()}, so a follower is copied only what it misses.
 */
final class FixSessionReplica implements ReplicaSink {

    @Getter(AccessLevel.PACKAGE)
    private final FixSessionMessagesStore store;
    private final LostWrites lostWrites;
    private final StoreWrites storeWrites;
    /**
     * Since the store was last reset, or as {@link #readLast} found it.
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
    public void apply(byte contentType, RecordFlag flag, long seqNum, ByteBuffer receiveBuffer, int messageIndex,
                      int messageLength) {
        switch (eventType(contentType)) {
            case STORE_MESSAGE:
                if (seqNum <= highestStoredSeqNum) {
                    break;
                }
                storeWrites.storeLiveMessage(seqNum, receiveBuffer, messageIndex, messageLength);
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

    /**
     * The last stored messages, found by widening a seqnum window back from the last outgoing seqnum: admin messages
     * are not stored, so the seqnums have gaps. None while the store has writes pending, which it cannot read back
     * yet: the session is then copied whole.
     */
    @Override
    public void readLast(int records, RecordConsumer consumer) {
        if (store.getPendingWrites() > 0) {
            return;
        }
        Deque<StoreMessage> last = new ArrayDeque<>(records);
        long lastSeqNum = store.getOutgoingSeqNum() - 1;
        for (long span = records; lastSeqNum >= 1; span *= 2) {
            long from = Math.max(1, lastSeqNum - span + 1);
            last.clear();
            store.find(from, lastSeqNum, (seqNum, message) -> {
                if (last.size() == records) {
                    last.removeFirst();
                }
                last.addLast(new StoreMessage(seqNum, copy(message)));
                return true;
            });
            if (last.size() == records || from == 1) {
                break;
            }
        }
        highestStoredSeqNum = last.isEmpty() ? 0 : last.getLast().getSeqNum();
        for (StoreMessage message : last) {
            ByteBuffer payload = message.getMessage();
            if (!consumer.onRecord(StoreEventType.STORE_MESSAGE.getCode(), message.getSeqNum(), payload, 0,
                    payload.limit())) {
                return;
            }
        }
    }

    /**
     * From 0, starts the store over. From a later seqnum, keeps it: the leader starts there after messages both hold
     * the same, so what the store holds from there on came from that leader, and a FIX store cannot drop messages.
     */
    @Override
    public void snapshotBegin(long fromPosition, ByteBuffer state, int stateIndex, int stateLength) {
        checkState(stateLength);
        if (fromPosition == 0) {
            store.resetSequenceNumbers();
            lostWrites.reset();
            highestStoredSeqNum = 0;
        }
        store.storeNextIncomingSeqNum(state.getLong(stateIndex));
        store.storeNextOutgoingSeqNum(state.getLong(stateIndex + Long.BYTES));
    }

    @Override
    public void snapshotRecord(byte contentType, long seqNum, ByteBuffer receiveBuffer, int messageIndex,
                               int messageLength) {
        if (eventType(contentType) != StoreEventType.STORE_MESSAGE) {
            throw new WireFormatException("A snapshot of a FIX session carries messages only, got " + eventType(contentType));
        }
        if (seqNum <= highestStoredSeqNum) {
            return;
        }
        storeWrites.storeSnapshotMessage(seqNum, receiveBuffer, messageIndex, messageLength);
        highestStoredSeqNum = seqNum;
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

    private static ByteBuffer copy(ByteBuffer message) {
        ByteBuffer copy = ByteBuffer.allocate(message.remaining());
        copy.put(message.duplicate()).flip();
        return copy;
    }

    private static StoreEventType eventType(byte contentType) {
        try {
            return StoreEventType.from(contentType);
        } catch (IllegalStateException e) {
            throw new WireFormatException("Unknown store event type " + contentType);
        }
    }
}
