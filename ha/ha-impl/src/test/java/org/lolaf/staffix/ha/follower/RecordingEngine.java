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
import org.lolaf.staffix.api.alerting.Probes;
import org.lolaf.staffix.api.ha.FixEngineHa.EngineAccess;
import org.lolaf.staffix.api.monitoring.FixEngineMonitoringContext;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.BatchingFixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

/**
 * An engine whose sessions' stores record what is written to them, as text.
 */
public final class RecordingEngine implements EngineAccess {

    final List<FixSessionSettings> settings = new CopyOnWriteArrayList<>();
    final Map<String, RecordingStore> stores = new ConcurrentHashMap<>();
    /**
     * Activations and deactivations, each with the number of stores open at the time.
     */
    public final List<String> activity = new CopyOnWriteArrayList<>();

    public static FixSessionSettings session(String name, FixSessionType type) {
        return FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of(name, FixRegularVersion.VERSION_44, "SENDER", "TARGET"))
                .fixSessionType(type)
                .haSessionSettings(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.ASYNC).build())
                .build();
    }

    public RecordingEngine with(FixSessionSettings session) {
        settings.add(session);
        return this;
    }

    /**
     * Swaps the settings of the session with the same id and type, as a settings change would.
     */
    public RecordingEngine replace(FixSessionSettings session) {
        settings.replaceAll(known -> known.getFixSessionType() == session.getFixSessionType()
                && known.getFixSessionId().equals(session.getFixSessionId()) ? session : known);
        return this;
    }

    public RecordingStore store(FixSessionSettings session) {
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
    /**
     * Whether the next stores opened write sent messages in batches.
     */
    volatile boolean batchingStores;

    @Override
    public FixSessionMessagesStore openStore(FixSessionSettings session) {
        RecordingStore store = batchingStores ? new BatchingRecordingStore() : new RecordingStore();
        store.storageIdentity = nextStorageIdentity;
        stores.put(session.getFixSessionType() + " " + session.getFixSessionId(), store);
        return store;
    }

    /**
     * The meters handed out as the monitoring manager {@code "monitoring"}.
     */
    public final RecordingMeters meters = new RecordingMeters();

    @Override
    public FixEngineMonitoringContext monitoring(String monitoringInstanceId) {
        if (!monitoringInstanceId.equals("monitoring")) {
            throw new IllegalArgumentException("No monitoring manager " + monitoringInstanceId);
        }
        return meters;
    }

    /**
     * The probes handed out to component {@code "ha"} with the alerts publisher {@code "alerts"}.
     */
    public final RecordingProbes probes = new RecordingProbes();

    @Override
    public Probes probes(String component, String alertsPublisherInstanceId, BooleanSupplier componentActive) {
        if (!component.equals("ha") || !alertsPublisherInstanceId.equals("alerts")) {
            throw new IllegalArgumentException("No alerts publisher " + alertsPublisherInstanceId);
        }
        probes.active = componentActive;
        return probes;
    }

    @Override
    public void activate() {
        activity.add("activate, stores open " + openStores());
    }

    @Override
    public void deactivate() {
        activity.add("deactivate");
    }

    @Override
    public void deactivateWithLogout(Deadline deadline) {
        activity.add("deactivate with logout");
    }

    private long openStores() {
        return stores.values().stream().filter(store -> store.started).count();
    }

    public static class RecordingStore implements FixSessionMessagesStore {

        public final List<String> writes = new CopyOnWriteArrayList<>();
        /**
         * Holds an incoming seqnum write until counted down, as a stalled storage would.
         */
        public volatile CountDownLatch incomingWritesHeld = new CountDownLatch(0);
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
            try {
                incomingWritesHeld.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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

    /**
     * Records each batch as one write, its messages separated by commas.
     */
    static final class BatchingRecordingStore extends RecordingStore implements BatchingFixSessionMessagesStore {

        @Override
        public void storeSentFixMessages(SentFixMessage[] sentFixMessages, int sentFixMessageCount) {
            StringJoiner batch = new StringJoiner(", ", "batch ", "");
            for (int i = 0; i < sentFixMessageCount; i++) {
                ByteBuffer message = sentFixMessages[i].getMessage();
                byte[] text = new byte[message.remaining()];
                message.get(text);
                batch.add(sentFixMessages[i].getSequenceNumber() + " " + new String(text, StandardCharsets.US_ASCII));
            }
            writes.add(batch.toString());
        }
    }
}
