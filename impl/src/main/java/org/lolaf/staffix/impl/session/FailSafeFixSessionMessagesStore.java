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
package org.lolaf.staffix.impl.session;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.stores.FixMessagesStore;

import java.nio.ByteBuffer;

/**
 * Wraps a session's message store so a storage failure is reported rather than thrown into the session.
 *
 * <p>A store that is down is a serious condition but not necessarily a fatal one; ending the session would
 * guarantee the outage becomes an outage for the counterparty too.
 */
@Slf4j
@Value
public class FailSafeFixSessionMessagesStore implements FixMessagesStore.FixSessionMessagesStore {

    FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore;

    @Override
    public boolean isUnderlyingStorageResourceAvailable() {
        return fixSessionMessagesStore.isUnderlyingStorageResourceAvailable();
    }

    @Override
    public boolean filter(MessageType messageType, ByteBuffer message) {
        return fixSessionMessagesStore.filter(messageType, message);
    }

    @Override
    public void resetSequenceNumbers() {
        try {
            fixSessionMessagesStore.resetSequenceNumbers();
        } catch (Exception ex) {
            log.error("Failed to call resetSequenceNumbers() on FixSessionMessagesStore {}", fixSessionMessagesStore.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public long getIncomingSeqNum() {
        return fixSessionMessagesStore.getIncomingSeqNum();
    }

    @Override
    public long getOutgoingSeqNum() {
        return fixSessionMessagesStore.getOutgoingSeqNum();
    }

    @Override
    public long getNextOutgoingSeqNum() throws StoreException {
        return fixSessionMessagesStore.getNextOutgoingSeqNum();
    }

    @Override
    public void storeNextIncomingSeqNum(long nextIncomingSeqNum) {
        try {
            fixSessionMessagesStore.storeNextIncomingSeqNum(nextIncomingSeqNum);
        } catch (Exception ex) {
            log.error("Failed to call storeNextIncomingSeqNum() on FixSessionMessagesStore {}", fixSessionMessagesStore.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void storeNextOutgoingSeqNum(long nextOutgoingSeqNum) {
        try {
            fixSessionMessagesStore.storeNextOutgoingSeqNum(nextOutgoingSeqNum);
        } catch (Exception ex) {
            log.error("Failed to call storeNextOutgoingSeqNum() on FixSessionMessagesStore {}", fixSessionMessagesStore.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void storeMessageSent(long outgoingSeqNum, ByteBuffer message) {
        try {
            fixSessionMessagesStore.storeMessageSent(outgoingSeqNum, message);
        } catch (Exception ex) {
            log.error("Failed to call storeMessageSent() on FixSessionMessagesStore {}", fixSessionMessagesStore.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void find(long startSequenceNumber, long stopSequenceNumber, StoreMessagesConsumer consumer) {
        try {
            fixSessionMessagesStore.find(startSequenceNumber, stopSequenceNumber, consumer);
        } catch (StoreException ex) {
            log.error("Failed to call find() on FixSessionMessagesStore {}", fixSessionMessagesStore.getClass().getSimpleName(), ex);
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to call find() on FixSessionMessagesStore {}", fixSessionMessagesStore.getClass().getSimpleName(), ex);
            throw new StoreException("Failed to call find() on FixSessionMessagesStore " + fixSessionMessagesStore.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public FixMessagesStore.FixSessionMessagesStore stop(Deadline stopDeadline) throws StartStopException {
        return fixSessionMessagesStore.stop(stopDeadline);
    }

    @Override
    public FixMessagesStore.FixSessionMessagesStore start() throws StartStopException {
        return fixSessionMessagesStore.start();
    }

    @Override
    public boolean isStarted() {
        return fixSessionMessagesStore.isStarted();
    }
}
