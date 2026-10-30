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
import org.lolaf.staffix.api.stores.FixMessagesStore.BatchingFixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.BatchingFixSessionMessagesStore.SentFixMessage;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.wire.SessionStatus;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
                // a resume may start past the first fragments of a message this session was assembling
                session.complete &= resumed && !session.assembling;
                session.discardFragments();
                session.discardSnapshotBatch();
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
            if (session != null && (session.store != null ? !session.complete : session.refusal.isRefusal())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Binds the leader's key to the session configured here with that id and type, opening and starting its store
     * the first time, when it is not the leader's own storage. A session refused is looked at again on the next
     * connection, its storage being readable by then perhaps. One the leader no longer replicates, or with another
     * replication mode here, has its store closed, and is caught up again by snapshot once both agree it is replicated.
     */
    SessionStatus bind(int key, FixSessionType fixSessionType, String fixSessionId, ReplicationMode replicationMode,
                       @Nullable String leaderStorageIdentity) {
        ReplicaSession bound = key < sessions.length ? sessions[key] : null;
        if (bound != null && bound.matches(fixSessionType, fixSessionId) && bound.store != null
                && replicationMode != ReplicationMode.NONE && replicationMode == localMode(fixSessionType, fixSessionId)) {
            return bound.status();
        }
        if (bound != null) {
            bound.stop(Deadline.unlimited());
        }
        if (key >= sessions.length) {
            sessions = Arrays.copyOf(sessions, Math.max(key + 1, sessions.length * 2));
        }
        sessions[key] = open(fixSessionType, fixSessionId, replicationMode, leaderStorageIdentity);
        SessionStatus status = sessions[key].status();
        if (status == SessionStatus.NOT_REPLICATED) {
            log.info("FIX session {} {} is not replicated: the leader's settings do not ask for it", fixSessionType, fixSessionId);
        } else if (status == SessionStatus.SHARED_STORAGE) {
            log.info("FIX session {} {} is not replicated: its storage is the leader's own", fixSessionType, fixSessionId);
        } else if (status == SessionStatus.MODE_MISMATCH) {
            log.error("FIX session {} {} is not replicated here: the leader's settings give it replication mode {}, this "
                    + "node's {}", fixSessionType, fixSessionId, replicationMode, localMode(fixSessionType, fixSessionId));
        } else if (status != SessionStatus.SNAPSHOT_NEEDED) {
            log.warn("FIX session {} {} is not replicated here: {}", fixSessionType, fixSessionId, status);
        }
        return status;
    }

    private ReplicaSession open(FixSessionType fixSessionType, String fixSessionId, ReplicationMode leaderMode,
                                @Nullable String leaderStorageIdentity) {
        FixSessionSettings settings = findSettings(fixSessionType, fixSessionId);
        if (settings == null) {
            return leaderMode == ReplicationMode.NONE
                    ? new ReplicaSession(fixSessionType, fixSessionId, null, SessionStatus.NOT_REPLICATED)
                    : refused(fixSessionType, fixSessionId, SessionStatus.UNKNOWN_SESSION, leaderMode, leaderStorageIdentity);
        }
        if (settings.getHaSessionSettings().getReplicationMode() != leaderMode) {
            return refused(fixSessionType, fixSessionId, SessionStatus.MODE_MISMATCH, leaderMode, leaderStorageIdentity);
        }
        if (leaderMode == ReplicationMode.NONE) {
            return new ReplicaSession(fixSessionType, fixSessionId, null, SessionStatus.NOT_REPLICATED);
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

    private static ReplicaSession refused(FixSessionType fixSessionType, String fixSessionId, SessionStatus refusal,
                                          ReplicationMode leaderMode, @Nullable String leaderStorageIdentity) {
        ReplicaSession session = new ReplicaSession(fixSessionType, fixSessionId, null, refusal);
        session.leaderMode = leaderMode;
        session.leaderStorageIdentity = leaderStorageIdentity;
        return session;
    }

    /**
     * Binds again each session refused as unknown whose settings the engine has by now, or for a mode mismatch its
     * settings here now agree with: a standby's connectors may register their sessions after the leader announced
     * them, and a settings change may reach this node after the leader. Either would otherwise stay refused until
     * the next connection.
     *
     * @param bound told each key bound now, with its status for the leader
     */
    void bindAgain(ObjIntConsumer<SessionStatus> bound) {
        for (int key = 0; key < sessions.length; key++) {
            ReplicaSession session = sessions[key];
            if (session == null || !session.isRefusedUntilSettingsChange() || !settingsNowAccept(session)) {
                continue;
            }
            sessions[key] = open(session.fixSessionType, session.fixSessionId, session.leaderMode, session.leaderStorageIdentity);
            SessionStatus status = sessions[key].status();
            log.info("FIX session {} {} is now configured here as on the leader: {}", session.fixSessionType,
                    session.fixSessionId, status);
            bound.accept(status, key);
        }
    }

    boolean hasSessionsToBindAgain() {
        for (ReplicaSession session : sessions) {
            if (session != null && session.isRefusedUntilSettingsChange()) {
                return true;
            }
        }
        return false;
    }

    private boolean settingsNowAccept(ReplicaSession session) {
        FixSessionSettings settings = findSettings(session.fixSessionType, session.fixSessionId);
        return settings != null && (session.refusal == SessionStatus.UNKNOWN_SESSION
                || settings.getHaSessionSettings().getReplicationMode() == session.leaderMode);
    }

    private @Nullable ReplicationMode localMode(FixSessionType fixSessionType, String fixSessionId) {
        FixSessionSettings settings = findSettings(fixSessionType, fixSessionId);
        return settings == null ? null : settings.getHaSessionSettings().getReplicationMode();
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
                  int messageIndex, int messageLength, byte fragmentFlags) {
        ReplicaSession session = key < sessions.length ? sessions[key] : null;
        if (session == null) {
            return false;
        }
        session.flushSnapshotBatch();
        if (fragmentFlags != 0 || session.assembling) {
            if (!session.assemble(sequenceNumber, buffer, messageIndex, messageLength, fragmentFlags, false)) {
                return false;
            }
        } else if (session.store != null) {
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
        session.discardFragments();
        session.discardSnapshotBatch();
        session.snapshotMessages = 0;
        session.store.resetSequenceNumbers();
        session.store.storeNextIncomingSeqNum(nextIncomingSeqNum);
        session.store.storeNextOutgoingSeqNum(nextOutgoingSeqNum);
        return true;
    }

    boolean snapshotMessage(int key, long sequenceNumber, ByteBuffer buffer, int messageIndex, int messageLength,
                            byte fragmentFlags) {
        ReplicaSession session = replicated(key);
        if (session == null) {
            return false;
        }
        if (fragmentFlags != 0 || session.assembling) {
            if (!session.assemble(sequenceNumber, buffer, messageIndex, messageLength, fragmentFlags, true)) {
                return false;
            }
        } else {
            session.storeSnapshotMessage(sequenceNumber, buffer, messageIndex, messageLength);
        }
        if ((fragmentFlags & WireFormat.MORE_FRAGMENTS) == 0) {
            session.snapshotMessages++;
        }
        return true;
    }

    /**
     * Completes the session with the leader's seqnums at the snapshot's end: its events in between went through the
     * snapshot, and storing a message moved the outgoing seqnum.
     *
     * @return false when the session is unknown or the count is not what was received
     */
    boolean snapshotEnd(int key, int messageCount, long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        ReplicaSession session = replicated(key);
        if (session == null || session.snapshotMessages != messageCount) {
            return false;
        }
        session.flushSnapshotBatch();
        session.store.storeNextIncomingSeqNum(nextIncomingSeqNum);
        session.store.storeNextOutgoingSeqNum(nextOutgoingSeqNum);
        session.complete = true;
        return true;
    }

    /**
     * Writes the snapshot messages batched by the frames of a read, before it is acknowledged.
     */
    void flushSnapshotBatches() {
        for (ReplicaSession session : sessions) {
            if (session != null) {
                session.flushSnapshotBatch();
            }
        }
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
     *
     * @return the sessions taken over, moved or handed over
     */
    public List<FixSessionSettings> jumpOutgoingSeqNums(long jump) {
        List<FixSessionSettings> takenOver = new ArrayList<>();
        for (FixSessionSettings settings : engine.getFixSessionsSettings()) {
            if (settings.getHaSessionSettings().getReplicationMode() == ReplicationMode.NONE) {
                continue;
            }
            ReplicaSession session = find(settings.getFixSessionType(), settings.getFixSessionId().toString());
            takenOver.add(settings);
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
        return takenOver;
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
        /**
         * Gathers the snapshot messages for a store that writes them in batches, null for one that does not.
         */
        private final @Nullable SnapshotBatch snapshotBatch;
        private final SessionStatus refusal;
        /**
         * Kept by a session refused until its settings here change, to bind it again once they do.
         */
        private @Nullable String leaderStorageIdentity;
        private @Nullable ReplicationMode leaderMode;
        private volatile boolean complete;
        private volatile boolean handedOver;
        private int snapshotMessages;
        private boolean assembling;
        private long assembledSequenceNumber;
        /**
         * The fragments received so far of a message longer than a frame, grown as needed and kept for the next one.
         */
        private @Nullable ByteBuffer fragments;

        /**
         * @param refusal the status of a session not replicated here, without a store
         */
        ReplicaSession(FixSessionType fixSessionType, String fixSessionId, @Nullable FixSessionMessagesStore store,
                       SessionStatus refusal) {
            this.fixSessionType = fixSessionType;
            this.fixSessionId = fixSessionId;
            this.store = store;
            this.refusal = refusal;
            this.snapshotBatch = store instanceof BatchingFixSessionMessagesStore
                    ? new SnapshotBatch((BatchingFixSessionMessagesStore) store) : null;
        }

        SessionStatus status() {
            if (store == null) {
                return refusal;
            }
            return complete ? SessionStatus.REPLICATING : SessionStatus.SNAPSHOT_NEEDED;
        }

        boolean isRefusedUntilSettingsChange() {
            return store == null && (refusal == SessionStatus.UNKNOWN_SESSION || refusal == SessionStatus.MODE_MISMATCH);
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

        /**
         * Writes a snapshot message, batched when the store batches: unlike one by one, which moves the outgoing
         * seqnum to the message's, a batch leaves it alone, as {@link #snapshotBegin} set it.
         */
        void storeSnapshotMessage(long sequenceNumber, ByteBuffer buffer, int messageIndex, int messageLength) {
            if (snapshotBatch == null) {
                apply(StoreEventType.STORE_MESSAGE, sequenceNumber, buffer, messageIndex, messageLength);
            } else {
                snapshotBatch.add(sequenceNumber, buffer, messageIndex, messageLength);
            }
        }

        void flushSnapshotBatch() {
            if (snapshotBatch != null) {
                snapshotBatch.flush();
            }
        }

        void discardSnapshotBatch() {
            if (snapshotBatch != null) {
                snapshotBatch.discard();
            }
        }

        /**
         * Adds a fragment of a message longer than a frame, applying the message with its last fragment. A session not
         * replicated here keeps no bytes, only where the message ends. A continued fragment with no message begun is
         * what a resume leaves of a message whose start it skipped: dropped, its session catching up by snapshot.
         *
         * @return false for a fragment out of place: another message's, or a new message before the last one ended
         */
        boolean assemble(long sequenceNumber, ByteBuffer buffer, int messageIndex, int messageLength, byte fragmentFlags,
                         boolean snapshot) {
            boolean continued = (fragmentFlags & WireFormat.CONTINUED_FRAGMENT) != 0;
            boolean moreFragments = (fragmentFlags & WireFormat.MORE_FRAGMENTS) != 0;
            if (continued != assembling) {
                return continued;
            }
            if (!assembling) {
                assembling = true;
                assembledSequenceNumber = sequenceNumber;
            } else if (sequenceNumber != assembledSequenceNumber) {
                return false;
            }
            if (store != null) {
                ByteBuffer fragment = buffer.duplicate();
                fragment.limit(messageIndex + messageLength).position(messageIndex);
                fragments = withRoomFor(messageLength).put(fragment);
            }
            if (!moreFragments) {
                if (snapshot) {
                    storeSnapshotMessage(sequenceNumber, fragments, 0, fragments.position());
                } else if (store != null) {
                    apply(StoreEventType.STORE_MESSAGE, sequenceNumber, fragments, 0, fragments.position());
                }
                discardFragments();
            }
            return true;
        }

        private ByteBuffer withRoomFor(int length) {
            if (fragments == null) {
                return ByteBuffer.allocate(length);
            }
            if (fragments.remaining() >= length) {
                return fragments;
            }
            ByteBuffer grown = ByteBuffer.allocate(Math.max(fragments.position() + length, fragments.capacity() * 2));
            return grown.put(fragments.flip());
        }

        void discardFragments() {
            assembling = false;
            if (fragments != null) {
                fragments.clear();
            }
        }

        void stop(Deadline deadline) {
            discardSnapshotBatch();
            if (store != null) {
                store.stop(deadline);
            }
        }
    }

    /**
     * Snapshot messages copied out of the receive buffer, written to the store together.
     */
    private static final class SnapshotBatch {

        private static final int MAX_MESSAGES = 256;
        private final BatchingFixSessionMessagesStore store;
        private final SentFixMessage[] messages = new SentFixMessage[MAX_MESSAGES];
        private int count;

        SnapshotBatch(BatchingFixSessionMessagesStore store) {
            this.store = store;
        }

        void add(long sequenceNumber, ByteBuffer buffer, int messageIndex, int messageLength) {
            if (messages[count] == null) {
                messages[count] = new CopiedMessage();
            }
            ((CopiedMessage) messages[count]).copy(sequenceNumber, buffer, messageIndex, messageLength);
            if (++count == MAX_MESSAGES) {
                flush();
            }
        }

        void flush() {
            if (count == 0) {
                return;
            }
            try {
                store.storeSentFixMessages(messages, count);
            } finally {
                count = 0;
            }
        }

        void discard() {
            count = 0;
        }
    }

    /**
     * A message of a batch, its buffer kept and grown for the next one.
     */
    private static final class CopiedMessage implements SentFixMessage {

        private long sequenceNumber;
        private ByteBuffer message = ByteBuffer.allocate(256);

        /**
         * The message is lent as a range of {@code buffer}, which is put back as it was.
         */
        void copy(long sequenceNumber, ByteBuffer buffer, int messageIndex, int messageLength) {
            this.sequenceNumber = sequenceNumber;
            if (message.capacity() < messageLength) {
                message = ByteBuffer.allocate(Math.max(messageLength, message.capacity() * 2));
            }
            int position = buffer.position();
            int limit = buffer.limit();
            message.clear().put(buffer.limit(messageIndex + messageLength).position(messageIndex)).flip();
            buffer.limit(limit).position(position);
        }

        @Override
        public long getSequenceNumber() {
            return sequenceNumber;
        }

        @Override
        public ByteBuffer getMessage() {
            return message;
        }
    }
}
