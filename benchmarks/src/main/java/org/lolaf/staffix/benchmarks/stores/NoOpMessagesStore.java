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
package org.lolaf.staffix.benchmarks.stores;

import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.alerting.Probes;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;

import java.nio.ByteBuffer;
import java.util.function.BiPredicate;

/**
 * Drops every write, so that a store wrapping it is measured on its own cost.
 */
class NoOpMessagesStore extends Startable.SimpleStartable<FixMessagesStore> implements FixMessagesStore {

    @Override
    public FixSessionMessagesStore getStore(FixSessionId fixSessionId, Probes probes) {
        return new NoOpSessionMessagesStore();
    }

    @Override
    public String getInstanceId() {
        return Settings.INSTANCE.getInstanceId();
    }

    @Override
    protected void startMe() {
        // nothing to do
    }

    @Override
    protected void stopMe(Deadline stopDeadline) {
        // nothing to do
    }

    /**
     * Builds the store itself rather than through the SPI, which only knows the stores shipped as modules.
     */
    static class Settings implements FixMessagesStoreSettings {

        static final Settings INSTANCE = new Settings();

        @Override
        public BiPredicate<MessageType, ByteBuffer> getMessageFilter() {
            return (messageType, message) -> true;
        }

        @Override
        public FixMessagesStore instance() {
            return new NoOpMessagesStore();
        }

        @Override
        public String getInstanceId() {
            return "no-op";
        }
    }

    private static class NoOpSessionMessagesStore extends Startable.VoidStartable<FixSessionMessagesStore>
            implements BatchingFixSessionMessagesStore {

        @Override
        public void storeSentFixMessages(SentFixMessage[] sentFixMessages, int sentFixMessageCount) {
            // nothing to do
        }

        @Override
        public boolean filter(MessageType messageType, ByteBuffer message) {
            return true;
        }

        @Override
        public void resetSequenceNumbers() {
            // nothing to do
        }

        @Override
        public long getIncomingSeqNum() {
            return 0;
        }

        @Override
        public long getOutgoingSeqNum() {
            return 0;
        }

        @Override
        public long getNextOutgoingSeqNum() {
            return 1;
        }

        @Override
        public void storeNextIncomingSeqNum(long nextIncomingSeqNum) {
            // nothing to do
        }

        @Override
        public void storeNextOutgoingSeqNum(long nextOutgoingSeqNum) {
            // nothing to do
        }

        @Override
        public void storeMessageSent(long outgoingSeqNum, ByteBuffer message) {
            // nothing to do
        }

        @Override
        public void find(long startSequenceNumber, long stopSequenceNumber, StoreMessagesConsumer consumer) {
            // nothing to do
        }
    }
}
