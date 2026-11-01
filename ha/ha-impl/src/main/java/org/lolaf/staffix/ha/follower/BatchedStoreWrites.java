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

import org.lolaf.staffix.api.stores.FixMessagesStore.BatchingFixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.BatchingFixSessionMessagesStore.SentFixMessage;

import java.nio.ByteBuffer;

/**
 * Messages copied out of the receive buffer and written with the seqnums in one call, which leaves the outgoing
 * seqnum to the caller.
 */
final class BatchedStoreWrites implements StoreWrites {

    private static final int MAX_MESSAGES = 256;
    private final BatchingFixSessionMessagesStore store;
    private final SentFixMessage[] messages = new SentFixMessage[MAX_MESSAGES];
    private int messageCount;
    private long nextIncomingSeqNum = -1;
    private long nextOutgoingSeqNum = -1;

    BatchedStoreWrites(BatchingFixSessionMessagesStore store) {
        this.store = store;
    }

    @Override
    public void storeLiveMessage(long seqNum, ByteBuffer receiveBuffer, int messageStart, int messageLength) {
        storeSnapshotMessage(seqNum, receiveBuffer, messageStart, messageLength);
        nextOutgoingSeqNum = seqNum + 1;
    }

    @Override
    public void storeSnapshotMessage(long seqNum, ByteBuffer receiveBuffer, int messageStart, int messageLength) {
        if (messages[messageCount] == null) {
            messages[messageCount] = new CopiedMessage();
        }
        ((CopiedMessage) messages[messageCount]).copy(seqNum, receiveBuffer, messageStart, messageLength);
        if (++messageCount == MAX_MESSAGES) {
            write(-1, -1);
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
        nextIncomingSeqNum = -1;
        nextOutgoingSeqNum = -1;
        if (messageCount > 0 || incoming >= 0 || outgoing >= 0) {
            write(incoming, outgoing);
        }
    }

    private void write(long incoming, long outgoing) {
        try {
            store.storeSentFixMessagesAndSeqNums(messages, messageCount, incoming, outgoing);
        } finally {
            messageCount = 0;
        }
    }

    @Override
    public void discardPending() {
        messageCount = 0;
        nextIncomingSeqNum = -1;
        nextOutgoingSeqNum = -1;
    }

    /**
     * A message of a batch, its buffer kept and grown for the next one.
     */
    private static final class CopiedMessage implements SentFixMessage {

        private long seqNum;
        private ByteBuffer message = ByteBuffer.allocate(256);

        /**
         * The message is lent as a range of {@code receiveBuffer}, which is put back as it was.
         */
        void copy(long seqNum, ByteBuffer receiveBuffer, int messageStart, int messageLength) {
            this.seqNum = seqNum;
            if (message.capacity() < messageLength) {
                message = ByteBuffer.allocate(Math.max(messageLength, message.capacity() * 2));
            }
            int position = receiveBuffer.position();
            int limit = receiveBuffer.limit();
            message.clear().put(receiveBuffer.limit(messageStart + messageLength).position(messageStart)).flip();
            receiveBuffer.limit(limit).position(position);
        }

        @Override
        public long getSequenceNumber() {
            return seqNum;
        }

        @Override
        public ByteBuffer getMessage() {
            return message;
        }
    }
}
