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
 * session's store, opened outside the session, and the last event applied.
 *
 * <p>Applying never deletes: seqnums and messages are written over, so an event applied twice changes nothing.
 * Written from the connection's IO thread only.
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
    /**
     * Holds every event the leader logged up to the last one applied: false until a snapshot fills it, and again
     * after a stream that did not resume.
     */
    @Getter
    private volatile boolean complete;

    public Replica(EngineAccess engine) {
        this.engine = engine;
    }

    /**
     * Starts a stream: one that did not resume leaves a gap only a snapshot fills.
     */
    void streamStarted(boolean resumed) {
        if (!resumed) {
            complete = false;
        }
    }

    /**
     * Binds the leader's key to the session configured here with that id and type, opening and starting its store
     * the first time.
     */
    SessionStatus bind(int key, FixSessionType fixSessionType, String fixSessionId) {
        ReplicaSession bound = key < sessions.length ? sessions[key] : null;
        if (bound != null && bound.matches(fixSessionType, fixSessionId)) {
            return bound.status;
        }
        if (bound != null) {
            bound.stop(Deadline.unlimited());
        }
        FixSessionSettings settings = findSettings(fixSessionType, fixSessionId);
        if (key >= sessions.length) {
            sessions = Arrays.copyOf(sessions, Math.max(key + 1, sessions.length * 2));
        }
        if (settings == null) {
            sessions[key] = new ReplicaSession(fixSessionType, fixSessionId, null, SessionStatus.UNKNOWN_SESSION);
            log.warn("The leader replicates FIX session {} {}, unknown here", fixSessionType, fixSessionId);
        } else {
            FixSessionMessagesStore store = engine.openStore(settings);
            store.start();
            sessions[key] = new ReplicaSession(fixSessionType, fixSessionId, store, SessionStatus.REPLICATING);
        }
        return sessions[key].status;
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
        private final SessionStatus status;

        ReplicaSession(FixSessionType fixSessionType, String fixSessionId, @Nullable FixSessionMessagesStore store,
                       SessionStatus status) {
            this.fixSessionType = fixSessionType;
            this.fixSessionId = fixSessionId;
            this.store = store;
            this.status = status;
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
