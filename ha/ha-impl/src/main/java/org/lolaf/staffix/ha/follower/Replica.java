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
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.wire.SessionStatus;

import java.nio.ByteBuffer;
import java.util.Arrays;

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
    private ReplicaSession[] sessions = new ReplicaSession[16];
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
     * Starts a stream: one that did not resume leaves every session a gap only a snapshot fills, and nothing to resume
     * from.
     */
    void streamStarted(boolean resumed) {
        if (resumed) {
            return;
        }
        lastAppliedOffset = -1;
        for (ReplicaSession session : sessions) {
            if (session != null) {
                session.complete = false;
            }
        }
    }

    /**
     * Every session replicated here is complete.
     */
    public boolean isComplete() {
        for (ReplicaSession session : sessions) {
            if (session != null && session.store != null && !session.complete) {
                return false;
            }
        }
        return true;
    }

    /**
     * Binds the leader's key to the session configured here with that id and type, opening and starting its store
     * the first time.
     */
    SessionStatus bind(int key, FixSessionType fixSessionType, String fixSessionId) {
        ReplicaSession bound = key < sessions.length ? sessions[key] : null;
        if (bound != null && bound.matches(fixSessionType, fixSessionId)) {
            return bound.status();
        }
        if (bound != null) {
            bound.stop(Deadline.unlimited());
        }
        FixSessionSettings settings = findSettings(fixSessionType, fixSessionId);
        if (key >= sessions.length) {
            sessions = Arrays.copyOf(sessions, Math.max(key + 1, sessions.length * 2));
        }
        if (settings == null) {
            sessions[key] = new ReplicaSession(fixSessionType, fixSessionId, null);
            log.warn("The leader replicates FIX session {} {}, unknown here", fixSessionType, fixSessionId);
        } else {
            FixSessionMessagesStore store = engine.openStore(settings);
            store.start();
            sessions[key] = new ReplicaSession(fixSessionType, fixSessionId, store);
        }
        return sessions[key].status();
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
        for (ReplicaSession session : sessions) {
            if (session != null && session.matches(fixSessionType, fixSessionId)) {
                return session.store;
            }
        }
        return null;
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
        private volatile boolean complete;
        private long snapshotNextOutgoingSeqNum;
        private int snapshotMessages;

        ReplicaSession(FixSessionType fixSessionType, String fixSessionId, @Nullable FixSessionMessagesStore store) {
            this.fixSessionType = fixSessionType;
            this.fixSessionId = fixSessionId;
            this.store = store;
        }

        SessionStatus status() {
            if (store == null) {
                return SessionStatus.UNKNOWN_SESSION;
            }
            return complete ? SessionStatus.REPLICATING : SessionStatus.SNAPSHOT_NEEDED;
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
