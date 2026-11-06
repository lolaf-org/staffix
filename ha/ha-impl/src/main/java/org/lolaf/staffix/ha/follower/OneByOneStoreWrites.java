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
package org.lolaf.staffix.ha.follower;

import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;

import java.nio.ByteBuffer;

/**
 * Each message written as it comes, which also moves the outgoing seqnum past it.
 */
final class OneByOneStoreWrites implements StoreWrites {

    private final FixSessionMessagesStore store;
    private final LostWrites lostWrites;
    private long nextIncomingSeqNum = -1;
    private long nextOutgoingSeqNum = -1;

    OneByOneStoreWrites(FixSessionMessagesStore store, LostWrites lostWrites) {
        this.store = store;
        this.lostWrites = lostWrites;
    }

    @Override
    public void storeLiveMessage(long seqNum, ByteBuffer receiveBuffer, int messageStart, int messageLength) {
        storeSnapshotMessage(seqNum, receiveBuffer, messageStart, messageLength);
        nextOutgoingSeqNum = -1;
    }

    @Override
    public void storeSnapshotMessage(long seqNum, ByteBuffer receiveBuffer, int messageStart, int messageLength) {
        int position = receiveBuffer.position();
        int limit = receiveBuffer.limit();
        try {
            store.storeMessageSent(seqNum, receiveBuffer.limit(messageStart + messageLength).position(messageStart));
        } catch (RuntimeException e) {
            lostWrites.failed(1, e);
        } finally {
            receiveBuffer.limit(limit).position(position);
        }
    }

    @Override
    public void setNextIncomingSeqNum(long nextIncomingSeqNum) {
        this.nextIncomingSeqNum = nextIncomingSeqNum;
    }

    @Override
    public void setNextOutgoingSeqNum(long nextOutgoingSeqNum) {
        this.nextOutgoingSeqNum = nextOutgoingSeqNum;
    }

    @Override
    public void writePending() {
        long incoming = nextIncomingSeqNum;
        long outgoing = nextOutgoingSeqNum;
        discardPending();
        try {
            if (incoming >= 0) {
                store.storeNextIncomingSeqNum(incoming);
            }
            if (outgoing >= 0) {
                store.storeNextOutgoingSeqNum(outgoing);
            }
        } catch (RuntimeException e) {
            lostWrites.failed(0, e);
        }
    }

    @Override
    public void discardPending() {
        nextIncomingSeqNum = -1;
        nextOutgoingSeqNum = -1;
    }
}
