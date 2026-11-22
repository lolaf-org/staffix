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

import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.ringos.idling.RetryStrategy;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;
import org.lolaf.staffix.ha.SessionSettingsDigests.Digest;
import org.lolaf.staffix.ha.SessionSettingsDigests.StoredSession;
import org.lolaf.staffix.ha.leader.Followers;
import org.lolaf.staffix.ha.log.LogRecord;
import org.lolaf.staffix.ha.log.ReplicationLog;
import org.lolaf.staffix.ha.spi.LeaderStream;
import org.lolaf.staffix.ha.spi.SnapshotSource;
import org.lolaf.staffix.ha.wire.StreamReplicationMode;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The leader's session settings as a stream, for its followers to compare with their own: each change of a session's
 * digest is a record at the next version, its position, and a snapshot copies every session's latest record.
 */
@Slf4j
public final class SessionSettingsStream implements LeaderStream {

    public static final String STREAM_NAME = "staffix session settings";

    @Getter
    private final int key;
    private final ReplicationLog replicationLog;
    private final RetryStrategy logFullWait;
    private final Followers followers;
    private final LogRecord logRecord;
    private final SnapshotSource snapshotSource;
    /**
     * Each session's latest record, kept once forgotten so a snapshot in progress copies that too.
     */
    private final Map<StoredSession, Entry> latest;
    /**
     * Sessions removed from a store and not added again, so a follower away at the time still removes them. In memory:
     * a leader restarted since has forgotten them, and the follower then only reports the session.
     */
    private final Set<StoredSession> removals;
    /**
     * Published after its record is in {@link #latest}, which a snapshot round reads up to it.
     */
    private volatile long version;

    SessionSettingsStream(int key, ReplicationLog replicationLog, RetryStrategy logFullWait, Followers followers) {
        this.key = key;
        this.replicationLog = replicationLog;
        this.logFullWait = logFullWait;
        this.followers = followers;
        this.logRecord = new LogRecord();
        this.snapshotSource = new Snapshot();
        this.latest = new ConcurrentHashMap<>();
        this.removals = ConcurrentHashMap.newKeySet();
    }

    @Override
    public String getStreamName() {
        return STREAM_NAME;
    }

    @Override
    public StreamReplicationMode getStreamReplicationMode() {
        return StreamReplicationMode.ASYNC;
    }

    @Override
    public @Nullable String getStorageIdentity() {
        return null;
    }

    @Override
    public SnapshotSource newSnapshotSource() {
        return snapshotSource;
    }

    /**
     * Appends what changed since the last call.
     *
     * @param replicated the stores whose documents go with their digests; the others are only compared
     */
    synchronized void publish(List<FixSessionsSettingsStore> stores, Predicate<FixSessionsSettingsStore> replicated) {
        Map<StoredSession, Digest> current = new LinkedHashMap<>();
        SessionSettingsDigests.of(stores, replicated).forEach(digest -> current.put(digest.storedSession(), digest));
        for (FixSessionsSettingsStore store : stores) {
            if (replicated.test(store)) {
                String identity = store.getStorageIdentity().orElse("");
                removals.stream().filter(removal -> removal.getStore().equals(store.getInstanceId()))
                        .forEach(removal -> current.put(removal, Digest.removal(removal, identity)));
            }
        }
        current.forEach((session, digest) -> {
            Entry known = latest.get(session);
            if (known == null || !digest.equals(known.getDigest())) {
                append(SessionSettingsRecords.contentType(digest), digest);
            }
        });
        List<StoredSession> gone = latest.values().stream()
                .filter(entry -> entry.getContentType() != SessionSettingsRecords.FORGOTTEN)
                .map(Entry::getSession).filter(session -> !current.containsKey(session)).collect(Collectors.toList());
        gone.forEach(session -> append(SessionSettingsRecords.FORGOTTEN, Digest.forgotten(session)));
    }

    void onRemoved(String store, FixSessionSettings settings) {
        removals.add(new StoredSession(store, TypedSessionId.of(settings)));
    }

    void onAdded(String store, FixSessionSettings settings) {
        removals.remove(new StoredSession(store, TypedSessionId.of(settings)));
    }

    private void append(byte contentType, Digest digest) {
        ByteBuffer payload = SessionSettingsRecords.encode(digest);
        if (payload.remaining() > replicationLog.getMaxPayloadLength() && !digest.getDocument().isEmpty()) {
            log.warn("The settings of FIX session {} are too long to replicate, they are only compared", digest.getFixSessionId());
            payload = SessionSettingsRecords.encode(digest.withoutDocument());
        }
        long next = version + 1;
        latest.put(digest.storedSession(), new Entry(next, contentType, digest, payload));
        version = next;
        try {
            replicationLog.append(logRecord.set(key, contentType, (byte) 0, next, payload), logFullWait);
            followers.wakeSenders();
        } catch (IllegalArgumentException e) {
            log.error("The settings of FIX session {} are not replicated, a follower has them from its next snapshot: {}",
                    digest.getFixSessionId(), e.getMessage());
        }
    }

    @Value
    private static class Entry {
        long version;
        byte contentType;
        /**
         * As published, its document kept when it was too long to send.
         */
        Digest digest;
        /**
         * Read only, duplicated for each reader.
         */
        ByteBuffer payload;

        StoredSession getSession() {
            return digest.storedSession();
        }
    }

    /**
     * Holds no state of its own, so every lane's sender shares it.
     */
    private final class Snapshot implements SnapshotSource {

        @Override
        public boolean isReadable() {
            return true;
        }

        @Override
        public long captureState(ByteBuffer state) {
            return version;
        }

        @Override
        public void read(long fromPosition, long toPosition, RecordConsumer consumer) {
            List<Entry> inRange = new ArrayList<>();
            for (Entry entry : latest.values()) {
                if (entry.getVersion() >= fromPosition && entry.getVersion() <= toPosition) {
                    inRange.add(entry);
                }
            }
            inRange.sort(Comparator.comparingLong(Entry::getVersion));
            for (Entry entry : inRange) {
                if (!consumer.onRecord(entry.getContentType(), (byte) 0, entry.getVersion(), entry.getPayload().duplicate())) {
                    return;
                }
            }
        }
    }
}
