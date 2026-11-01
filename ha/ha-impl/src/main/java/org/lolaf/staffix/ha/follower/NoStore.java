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

import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;

import java.nio.ByteBuffer;

/**
 * The store of a session not replicated here, which takes every write and keeps nothing.
 */
enum NoStore implements FixSessionMessagesStore {

    INSTANCE;

    @Override
    public FixSessionMessagesStore start() {
        return this;
    }

    @Override
    public FixSessionMessagesStore stop(Deadline stopDeadline) {
        return this;
    }

    @Override
    public boolean isStarted() {
        return true;
    }

    @Override
    public boolean filter(MessageType messageType, ByteBuffer message) {
        return true;
    }

    @Override
    public void resetSequenceNumbers() {
    }

    @Override
    public long getIncomingSeqNum() {
        return 1;
    }

    @Override
    public long getOutgoingSeqNum() {
        return 1;
    }

    @Override
    public long getNextOutgoingSeqNum() {
        return 1;
    }

    @Override
    public void storeNextIncomingSeqNum(long nextIncomingSeqNum) {
    }

    @Override
    public void storeNextOutgoingSeqNum(long nextOutgoingSeqNum) {
    }

    @Override
    public void storeMessageSent(long outgoingSeqNum, ByteBuffer message) {
    }

    @Override
    public void find(long startSequenceNumber, long stopSequenceNumber, StoreMessagesConsumer consumer) {
    }
}
