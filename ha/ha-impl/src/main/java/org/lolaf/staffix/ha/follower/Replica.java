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

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.FixEngineHa.EngineAccess;
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.wire.SessionStatus;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.ObjIntConsumer;

/**
 * What the follower holds of the leader's sessions, kept across connections so a reconnection can resume: each
 * session's store, opened outside the session, whether it is complete, and the last event applied.
 *
 * <p>A session is complete once a snapshot filled it, and until a stream fails to resume. A snapshot resets the store
 * before filling it, so nothing older than the leader's copy survives; store events are written over, so one applied
 * twice changes nothing. Written from the connection's IO thread only.
 */
@Slf4j
public final class Replica {

    private final EngineAccess engine;
    private volatile ReplicaSession[] sessions = new ReplicaSession[16];
    @Getter
    private volatile long lastAppliedEpoch;
    /**
     * The offset of the last event applied, -1 for none.
     */
    @Getter
    private volatile long lastAppliedOffset = -1;

    public Replica(EngineAccess engine) {
        this.engine = engine;
    }

    /**
     * Starts a stream, ending any handover: one that did not resume leaves every session a gap only a snapshot fills,
     * and nothing to resume from.
     */
    void streamStarted(boolean resumed) {
        for (ReplicaSession session : sessions) {
            if (session != null) {
                session.handedOver = false;
                session.complete &= resumed;
            }
        }
        if (!resumed) {
            lastAppliedOffset = -1;
        }
    }

    /**
     * The leader stopped cleanly after sending every event, so each session complete here, or whose storage is the
     * leader's own, holds exactly what the leader last wrote: taking it over needs no seqnum jump. Holds until the
     * next stream.
     */
    void handedOver() {
        for (ReplicaSession session : sessions) {
            if (session != null) {
                session.handedOver = session.store != null ? session.complete : session.refusal == SessionStatus.SHARED_STORAGE;
            }
        }
    }

    /**
     * Every session the leader replicates is complete here, or held by the leader's own storage: one refused for an
     * unknown session or storage is missing, although nothing is replicated into it.
     */
    public boolean isComplete() {
        for (ReplicaSession session : sessions) {
            if (session != null && (session.store != null ? !session.complete : session.isMissing())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Binds the leader's key to the session configured here with that id and type, opening and starting its store
     * the first time, when it is not the leader's own storage. A session refused is looked at again on the next
     * connection, its storage being readable by then perhaps. One the leader no longer replicates has its store
     * closed, and is caught up again by snapshot once the leader replicates it again.
     */
    SessionStatus bind(int key, FixSessionType fixSessionType, String fixSessionId, ReplicationMode replicationMode,
                       @Nullable String leaderStorageIdentity) {
        ReplicaSession bound = key < sessions.length ? sessions[key] : null;
        if (bound != null && bound.matches(fixSessionType, fixSessionId) && bound.store != null
                && replicationMode != ReplicationMode.NONE) {
            return bound.status();
        }
        if (bound != null) {
            bound.stop(Deadline.unlimited());
        }
        if (key >= sessions.length) {
            sessions = Arrays.copyOf(sessions, Math.max(key + 1, sessions.length * 2));
        }
        sessions[key] = replicationMode == ReplicationMode.NONE
                ? new ReplicaSession(fixSessionType, fixSessionId, null, SessionStatus.NOT_REPLICATED)
                : open(fixSessionType, fixSessionId, leaderStorageIdentity);
        SessionStatus status = sessions[key].status();
        if (status == SessionStatus.NOT_REPLICATED) {
            log.info("FIX session {} {} is not replicated: the leader's settings do not ask for it", fixSessionType, fixSessionId);
        } else if (status == SessionStatus.SHARED_STORAGE) {
            log.info("FIX session {} {} is not replicated: its storage is the leader's own", fixSessionType, fixSessionId);
        } else if (status != SessionStatus.SNAPSHOT_NEEDED) {
            log.warn("FIX session {} {} is not replicated here: {}", fixSessionType, fixSessionId, status);
        }
        return status;
    }

    private ReplicaSession open(FixSessionType fixSessionType, String fixSessionId, @Nullable String leaderStorageIdentity) {
        FixSessionSettings settings = findSettings(fixSessionType, fixSessionId);
        if (settings == null) {
            ReplicaSession unknown = new ReplicaSession(fixSessionType, fixSessionId, null, SessionStatus.UNKNOWN_SESSION);
            unknown.leaderStorageIdentity = leaderStorageIdentity;
            return unknown;
        }
        FixSessionMessagesStore store = engine.openStore(settings);
        String storageIdentity = store.getStorageIdentity().orElse(null);
        if (storageIdentity == null || leaderStorageIdentity == null) {
            return new ReplicaSession(fixSessionType, fixSessionId, null, SessionStatus.UNKNOWN_STORAGE);
        }
        if (storageIdentity.equals(leaderStorageIdentity)) {
            return new ReplicaSession(fixSessionType, fixSessionId, null, SessionStatus.SHARED_STORAGE);
        }
        store.start();
        return new ReplicaSession(fixSessionType, fixSessionId, store, SessionStatus.SNAPSHOT_NEEDED);
    }

    /**
     * Binds again each session refused as unknown whose settings the engine has by now: a standby's connectors may
     * register their sessions after the leader announced them, which would otherwise stay refused until the next
     * connection.
     *
     * @param bound told each key bound now, with its status for the leader
     */
    void bindNewlyKnown(ObjIntConsumer<SessionStatus> bound) {
        for (int key = 0; key < sessions.length; key++) {
            ReplicaSession session = sessions[key];
            if (session == null || session.store != null || session.refusal != SessionStatus.UNKNOWN_SESSION
                    || findSettings(session.fixSessionType, session.fixSessionId) == null) {
                continue;
            }
            sessions[key] = open(session.fixSessionType, session.fixSessionId, session.leaderStorageIdentity);
            SessionStatus status = sessions[key].status();
            log.info("FIX session {} {} is now configured here: {}", session.fixSessionType, session.fixSessionId, status);
            bound.accept(status, key);
        }
    }

    boolean hasUnknownSessions() {
        for (ReplicaSession session : sessions) {
            if (session != null && session.store == null && session.refusal == SessionStatus.UNKNOWN_SESSION) {
                return true;
            }
        }
        return false;
    }

    private @Nullable FixSessionSettings findSettings(FixSessionType fixSessionType, String fixSessionId) {
        for (FixSessionSettings settings : engine.getFixSessionsSettings()) {
            if (settings.getFixSessionType() == fixSessionType && settings.getFixSessionId().toString().equals(fixSessionId)) {
                return settings;
            }
        }
        return null;
    }

    /**
     * @return false for a key the leader never bound
     */
    boolean apply(long epoch, long offset, int key, StoreEventType eventType, long sequenceNumber, ByteBuffer buffer,
                  int messageIndex, int messageLength) {
        ReplicaSession session = key < sessions.length ? sessions[key] : null;
        if (session == null) {
            return false;
        }
        if (session.store != null) {
            session.apply(eventType, sequenceNumber, buffer, messageIndex, messageLength);
        }
        lastAppliedEpoch = epoch;
        lastAppliedOffset = offset;
        return true;
    }

    /**
     * @return false for a key the leader never bound, or one not replicated here
     */
    boolean snapshotBegin(int key, long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        ReplicaSession session = replicated(key);
        if (session == null) {
            return false;
        }
        session.complete = false;
        session.snapshotNextOutgoingSeqNum = nextOutgoingSeqNum;
        session.snapshotMessages = 0;
        session.store.resetSequenceNumbers();
        session.store.storeNextIncomingSeqNum(nextIncomingSeqNum);
        session.store.storeNextOutgoingSeqNum(nextOutgoingSeqNum);
        return true;
    }

    boolean snapshotMessage(int key, long sequenceNumber, ByteBuffer buffer, int messageIndex, int messageLength) {
        ReplicaSession session = replicated(key);
        if (session == null) {
            return false;
        }
        session.apply(StoreEventType.STORE_MESSAGE, sequenceNumber, buffer, messageIndex, messageLength);
        session.snapshotMessages++;
        return true;
    }

    /**
     * Completes the session. Storing a message moves the outgoing seqnum past it, so the snapshot's is written again.
     *
     * @return false when the session is unknown or the count is not what was received
     */
    boolean snapshotEnd(int key, int messageCount) {
        ReplicaSession session = replicated(key);
        if (session == null || session.snapshotMessages != messageCount) {
            return false;
        }
        session.store.storeNextOutgoingSeqNum(session.snapshotNextOutgoingSeqNum);
        session.complete = true;
        return true;
    }

    private @Nullable ReplicaSession replicated(int key) {
        ReplicaSession session = key < sessions.length ? sessions[key] : null;
        return session == null || session.store == null ? null : session;
    }

    /**
     * The store replaying the session, null when it is not replicated here.
     */
    public @Nullable FixSessionMessagesStore getStore(FixSessionType fixSessionType, String fixSessionId) {
        ReplicaSession session = find(fixSessionType, fixSessionId);
        return session == null ? null : session.store;
    }

    private @Nullable ReplicaSession find(FixSessionType fixSessionType, String fixSessionId) {
        for (ReplicaSession session : sessions) {
            if (session != null && session.matches(fixSessionType, fixSessionId)) {
                return session;
            }
        }
        return null;
    }

    /**
     * Adds what this follower holds of the session to its status, nothing for a session the leader never announced.
     * Any thread.
     */
    public void describe(FixSessionType fixSessionType, String fixSessionId, HaSessionStatus.HaSessionStatusBuilder status) {
        ReplicaSession session = find(fixSessionType, fixSessionId);
        if (session == null) {
            return;
        }
        status.handedOver(session.handedOver);
        if (session.store != null) {
            status.inSync(session.complete)
                    .nextIncomingSeqNum(session.store.getIncomingSeqNum())
                    .nextOutgoingSeqNum(session.store.getOutgoingSeqNum());
        } else if (session.refusal == SessionStatus.SHARED_STORAGE) {
            status.inSync(true).sharedStorage(true);
        } else {
            status.notReplicatedReason(session.refusal.name());
        }
    }

    /**
     * Moves the outgoing seqnum of every session configured with replication by {@code jump}, before this node runs
     * them as leader: in the replica's store, or in one opened for it when the replica holds none (its storage shared
     * with the old leader, unidentified, or never announced). A session the old leader {@link #handedOver() handed
     * over} cleanly keeps its seqnum.
     */
    public void jumpOutgoingSeqNums(long jump) {
        for (FixSessionSettings settings : engine.getFixSessionsSettings()) {
            if (settings.getHaSessionSettings().getReplicationMode() == ReplicationMode.NONE) {
                continue;
            }
            ReplicaSession session = find(settings.getFixSessionType(), settings.getFixSessionId().toString());
            if (session != null && session.handedOver) {
                log.info("FIX session {} {} taken over where the old leader cleanly left it", settings.getFixSessionType(),
                        settings.getFixSessionId());
                continue;
            }
            FixSessionMessagesStore replicated = session == null ? null : session.store;
            FixSessionMessagesStore store = replicated != null ? replicated : engine.openStore(settings);
            if (replicated == null) {
                store.start();
            }
            try {
                long next = store.getOutgoingSeqNum();
                store.storeNextOutgoingSeqNum(next + jump);
                log.warn("FIX session {} {} outgoing sequence number moved from {} to {}, past what the old leader may "
                        + "have sent unreplicated", settings.getFixSessionType(), settings.getFixSessionId(), next, next + jump);
            } finally {
                if (replicated == null) {
                    store.stop(Deadline.unlimited());
                }
            }
        }
    }

    /**
     * Stops every store, before the sessions start on their own.
     */
    public void close(Deadline deadline) {
        for (ReplicaSession session : sessions) {
            if (session != null) {
                session.stop(deadline);
            }
        }
        Arrays.fill(sessions, null);
    }

    private static final class ReplicaSession {

        private final FixSessionType fixSessionType;
        private final String fixSessionId;
        private final @Nullable FixSessionMessagesStore store;
        private final SessionStatus refusal;
        /**
         * Kept by a session refused as unknown, to bind it once it is configured.
         */
        private @Nullable String leaderStorageIdentity;
        private volatile boolean complete;
        private volatile boolean handedOver;
        private long snapshotNextOutgoingSeqNum;
        private int snapshotMessages;

        /**
         * @param refusal the status of a session not replicated here, without a store
         */
        ReplicaSession(FixSessionType fixSessionType, String fixSessionId, @Nullable FixSessionMessagesStore store,
                       SessionStatus refusal) {
            this.fixSessionType = fixSessionType;
            this.fixSessionId = fixSessionId;
            this.store = store;
            this.refusal = refusal;
        }

        SessionStatus status() {
            if (store == null) {
                return refusal;
            }
            return complete ? SessionStatus.REPLICATING : SessionStatus.SNAPSHOT_NEEDED;
        }

        boolean isMissing() {
            return refusal == SessionStatus.UNKNOWN_SESSION || refusal == SessionStatus.UNKNOWN_STORAGE;
        }

        boolean matches(FixSessionType fixSessionType, String fixSessionId) {
            return this.fixSessionType == fixSessionType && this.fixSessionId.equals(fixSessionId);
        }

        /**
         * The message is lent as a range of the receive buffer, which is put back as it was.
         */
        void apply(StoreEventType eventType, long sequenceNumber, ByteBuffer buffer, int messageIndex, int messageLength) {
            switch (eventType) {
                case STORE_MESSAGE:
                    int position = buffer.position();
                    int limit = buffer.limit();
                    store.storeMessageSent(sequenceNumber, buffer.limit(messageIndex + messageLength).position(messageIndex));
                    buffer.limit(limit).position(position);
                    break;
                case STORE_OUTGOING_SEQ_NUM:
                    store.storeNextOutgoingSeqNum(sequenceNumber);
                    break;
                case STORE_INCOMING_SEQ_NUM:
                    store.storeNextIncomingSeqNum(sequenceNumber);
                    break;
                case RESET_SEQ_NUM:
                    store.resetSequenceNumbers();
                    break;
                default:
                    throw new IllegalStateException("Unexpected store event " + eventType);
            }
        }

        void stop(Deadline deadline) {
            if (store != null) {
                store.stop(deadline);
            }
        }
    }
}
