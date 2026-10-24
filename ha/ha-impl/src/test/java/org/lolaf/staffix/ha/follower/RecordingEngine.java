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

import org.jspecify.annotations.Nullable;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.FixEngineHa.EngineAccess;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An engine whose sessions' stores record what is written to them, as text.
 */
final class RecordingEngine implements EngineAccess {

    final List<FixSessionSettings> settings = new CopyOnWriteArrayList<>();
    final Map<String, RecordingStore> stores = new ConcurrentHashMap<>();

    static FixSessionSettings session(String name, FixSessionType type) {
        return FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of(name, FixRegularVersion.VERSION_44, "SENDER", "TARGET"))
                .fixSessionType(type)
                .haSessionSettings(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.ASYNC).build())
                .build();
    }

    RecordingEngine with(FixSessionSettings session) {
        settings.add(session);
        return this;
    }

    RecordingStore store(FixSessionSettings session) {
        return stores.get(session.getFixSessionType() + " " + session.getFixSessionId());
    }

    @Override
    public List<FixSessionSettings> getFixSessionsSettings() {
        return settings;
    }

    /**
     * The storage identity the next stores opened report.
     */
    volatile @Nullable String nextStorageIdentity = "follower-storage";

    @Override
    public FixSessionMessagesStore openStore(FixSessionSettings session) {
        RecordingStore store = new RecordingStore();
        store.storageIdentity = nextStorageIdentity;
        stores.put(session.getFixSessionType() + " " + session.getFixSessionId(), store);
        return store;
    }

    static final class RecordingStore implements FixSessionMessagesStore {

        final List<String> writes = new CopyOnWriteArrayList<>();
        volatile boolean started;
        volatile @Nullable String storageIdentity = "recording:" + UUID.randomUUID();

        @Override
        public Optional<String> getStorageIdentity() {
            return Optional.ofNullable(storageIdentity);
        }

        @Override
        public FixSessionMessagesStore start() {
            started = true;
            return this;
        }

        @Override
        public FixSessionMessagesStore stop(Deadline stopDeadline) {
            started = false;
            return this;
        }

        @Override
        public boolean isStarted() {
            return started;
        }

        @Override
        public boolean filter(MessageType messageType, ByteBuffer message) {
            return false;
        }

        @Override
        public void resetSequenceNumbers() {
            writes.add("reset");
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
            writes.add("incoming " + nextIncomingSeqNum);
        }

        @Override
        public void storeNextOutgoingSeqNum(long nextOutgoingSeqNum) {
            writes.add("outgoing " + nextOutgoingSeqNum);
        }

        /**
         * Consumes the message, as the memory store does.
         */
        @Override
        public void storeMessageSent(long outgoingSeqNum, ByteBuffer message) {
            byte[] text = new byte[message.remaining()];
            message.get(text);
            writes.add("message " + outgoingSeqNum + " " + new String(text, StandardCharsets.US_ASCII));
        }

        @Override
        public void find(long startSequenceNumber, long stopSequenceNumber, StoreMessagesConsumer consumer) {
            // reads are not replicated
        }
    }
}
