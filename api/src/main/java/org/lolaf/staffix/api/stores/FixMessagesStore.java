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
package org.lolaf.staffix.api.stores;

import lombok.Getter;
import lombok.Value;
import org.lolaf.staffix.api.InstanceIdSupplier;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a session's outbound messages and sequence numbers are kept, so a restart can serve a ResendRequest.
 *
 * <p>One store serves many sessions, which is why {@link #getStore(FixSessionId)} exists: the per-session view
 * is what the session layer holds, while the store owns the file, connection or map underneath.
 */
public interface FixMessagesStore extends InstanceIdSupplier, Startable<FixMessagesStore> {

    /**
     * Gets a store instance of a given fix session
     *
     * @param fixSessionId the fix session id
     */
    FixSessionMessagesStore getStore(FixSessionId fixSessionId);

    @Getter
    enum StoreEventType {

        STORE_MESSAGE((byte) 0),
        STORE_INCOMING_SEQ_NUM((byte) 1),
        STORE_OUTGOING_SEQ_NUM((byte) 2),
        RESET_SEQ_NUM((byte) 3);

        private final byte code;

        StoreEventType(byte code) {
            this.code = code;
        }

        public static StoreEventType from(byte code) {
            // keep it like that, using Enum.values[] creates an array instance each call
            if (code == STORE_MESSAGE.getCode()) {
                return STORE_MESSAGE;
            } else if (code == STORE_INCOMING_SEQ_NUM.getCode()) {
                return STORE_INCOMING_SEQ_NUM;
            } else if (code == STORE_OUTGOING_SEQ_NUM.getCode()) {
                return STORE_OUTGOING_SEQ_NUM;
            } else if (code == RESET_SEQ_NUM.getCode()) {
                return RESET_SEQ_NUM;
            }
            throw new IllegalStateException("Unknown code " + code);
        }
    }

    /**
     * Interface to be implemented by your FixSessionMessagesStore to process store messages in batch in conjunction with an async store
     */
    interface BatchingFixSessionMessagesStore extends FixSessionMessagesStore {

        /**
         * Save multiple SentFixMessage to enable batch processing and increase throughput, contrary to {@link #storeMessageSent(long, ByteBuffer)}
         * the implementation must NOT increment the stored outgoing sequence number by 1 for each processed message
         *
         * @param sentFixMessages     the collection of SentFixMessage as an array for optimal memory allocation
         * @param sentFixMessageCount the sentFixMessageCount to process, as the array will contain null references
         *                            starting from sentFixMessageCount+1
         *                            WARNING: the sentFixMessages array instance CANNOT be passed to another thread, must be cloned properly in other to do so
         */
        void storeSentFixMessages(SentFixMessage[] sentFixMessages, int sentFixMessageCount) throws StoreException;

        interface SentFixMessage {

            long getSequenceNumber();

            ByteBuffer getMessage();

        }
    }

    /**
     * Fix session messages store, will be started when session is connected and stopped when disconnected
     */
    interface FixSessionMessagesStore extends Startable<FixSessionMessagesStore> {


        /**
         * Indicates if the underlying resource to store the messages is currently available or not,
         * this method should not throw an exception under any circumstances, can be called even if the store is not started
         */
        default boolean isUnderlyingStorageResourceAvailable() {
            return true;
        }

        /**
         * Returns a description of the store underlying storage resource
         */
        default String getUnderlyingStorageResourceDescription() {
            return "default";
        }

        /**
         * Indicates it the message should be filtered from the store.
         * Admin messages are de facto filtered
         *
         * @return true is the message should be filtered and not inserted into the store
         */
        boolean filter(MessageType messageType, ByteBuffer message) throws StoreException;

        /**
         * Resets the sequence numbers to 1
         */
        void resetSequenceNumbers() throws StoreException;

        /**
         * Gets the current incoming sequence number
         */
        long getIncomingSeqNum() throws StoreException;

        /**
         * Gets the current outgoing sequence number
         */
        long getOutgoingSeqNum() throws StoreException;

        /**
         * Gets the next outgoing sequence number (getOutgoingSeqNum + 1), implementations must increment the variable holding the counter
         */
        long getNextOutgoingSeqNum() throws StoreException;

        /**
         * Store the next expected incoming sequence number
         *
         * @param nextIncomingSeqNum the sequence number of the next expected incoming message
         */
        void storeNextIncomingSeqNum(long nextIncomingSeqNum) throws StoreException;

        /**
         * Store the next outgoing sequence number
         *
         * @param nextOutgoingSeqNum the sequence number of the next outgoing message
         */
        void storeNextOutgoingSeqNum(long nextOutgoingSeqNum) throws StoreException;

        /**
         * Store a sent message into the store context, the implement MUST also increment the stored outgoing sequence number by 1,
         * equivalent of calling @{link #storeNextOutgoingSeqNum(outgoingSeqNum+1)}
         *
         * @param outgoingSeqNum the sequence number of this outgoing message
         * @param message        the message content, reference to this buffer CANNOT be kept in the store
         */
        void storeMessageSent(long outgoingSeqNum, ByteBuffer message) throws StoreException;

        /**
         * Finds messages in the store for a given start and stop sequence number, handing them to {@code consumer} in
         * ascending MsgSeqNum(34) order as they are read. If a message cannot be loaded due to an underlying storage
         * failure the implementation is expected to throw a StoreException, which will result as a FIX session
         * immediate logout.
         * <p>
         * Streaming rather than returning them all: the range comes from a peer's ResendRequest(35=2) and can be
         * arbitrarily wide, so materializing it would let a single request decide how much memory this engine uses.
         * The consumer answering {@code false} stops the read there and then, which is how a bounded resend avoids
         * reading what it has already decided not to send.
         * <p>
         * The {@link ByteBuffer} handed over stays valid for the duration of the whole call, so a caller may keep it
         * beyond its callback - which is what the {@link #find(long, long)} form below relies on.
         */
        void find(long startSequenceNumber, long stopSequenceNumber, StoreMessagesConsumer consumer) throws StoreException;

        /**
         * Finds messages in the store for a given start and stop sequence number, all of them at once. A convenience
         * over {@link #find(long, long, StoreMessagesConsumer)} for callers that know the range is small, tests
         * above all; anything answering a peer's ResendRequest(35=2) should stream instead.
         */
        default List<StoreMessage> find(long startSequenceNumber, long stopSequenceNumber) throws StoreException {
            List<StoreMessage> messages = new ArrayList<>();
            find(startSequenceNumber, stopSequenceNumber, (seqNum, message) -> {
                messages.add(new StoreMessage(seqNum, message));
                return true;
            });
            return messages;
        }

        /**
         * Receives the messages a {@link #find(long, long, StoreMessagesConsumer)} reads, one at a time.
         */
        @FunctionalInterface
        interface StoreMessagesConsumer {

            /**
             * @param seqNum  the MsgSeqNum(34) the message was stored with
             * @param message the message as it was sent
             * @return whether to carry on reading, {@code false} stopping the read after this message
             */
            boolean onMessage(long seqNum, ByteBuffer message);
        }

        @Value
        class StoreMessage {
            long seqNum;
            ByteBuffer message;
        }

        class StoreException extends RuntimeException {

            public StoreException(String message) {
                super(message);
            }

            public StoreException(String message, Throwable cause) {
                super(message, cause);
            }
        }
    }
}