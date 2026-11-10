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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.FixEngineHa.EngineAccess;
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.monitoring.Timer;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.wire.SessionAnswer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;
import java.util.stream.Collectors;

/**
 * What the follower holds of the leader's sessions, kept across connections so a reconnection can resume: each
 * session's store, opened outside the session, whether it is in sync, and each lane's {@link LanePositions position}.
 *
 * <p>A session is in sync once a snapshot filled it, and until a stream fails to resume. A snapshot resets the store
 * before filling it, so nothing older than the leader's copy survives. It may hold messages the stream then brings
 * again, which are skipped, a store being free to refuse a seqnum it holds; seqnum events are absolute, so one
 * applied twice changes nothing. A read's writes are gathered per session and written once, before it is
 * acknowledged.
 *
 * <p>The leader's sessions come over its lanes, session {@code key} over lane {@code key % lanes} only, each lane on
 * its own thread: a session is touched by its lane's thread only.
 */
@Slf4j
public final class ReplicaSessions {

    private final EngineAccess engine;
    private final SessionBinder binder;
    private final LanePositions positions = new LanePositions();
    private volatile ReplicaSession[] sessionsByKey = new ReplicaSession[16];
    @Getter
    private final SessionSettingsComparison settingsComparison;
    /**
     * Times a read's store write after its events were acknowledged as received.
     */
    @Getter(AccessLevel.PACKAGE)
    @Setter
    private Timer receivedWriteLatency = Timer.VoidTimer.getInstance();

    public ReplicaSessions(EngineAccess engine) {
        this.engine = engine;
        this.settingsComparison = new SessionSettingsComparison(engine);
        this.binder = new SessionBinder(engine);
    }

    private static void jumpOutgoingSeqNum(FixSessionSettings settings, FixSessionMessagesStore store, long jump) {
        if (jump == 0) {
            return;
        }
        long outgoingSeqNum = store.getOutgoingSeqNum();
        store.storeNextOutgoingSeqNum(outgoingSeqNum + jump);
        log.warn("FIX session {} {} outgoing sequence number moved from {} to {}, past what the old leader may have sent "
                + "unreplicated", settings.getFixSessionType(), settings.getFixSessionId(), outgoingSeqNum, outgoingSeqNum + jump);
    }

    public int getLanes() {
        return positions.count();
    }

    /**
     * The leader's lane count, from its WELCOME. A lane's position is kept while the count does not change.
     */
    void setLanes(int lanes) {
        positions.setCount(lanes);
    }

    public boolean carries(int lane, int key) {
        return key % positions.count() == lane;
    }

    public long getLastAppliedEpoch(int lane) {
        return positions.getLastAppliedEpoch(lane);
    }

    /**
     * The offset in the lane's log of the last event written, -1 for none.
     */
    public long getLastAppliedOffset(int lane) {
        return positions.getLastAppliedOffset(lane);
    }

    /**
     * The offset in the lane's log of the last event received, written or not yet, when the read held an event of a
     * {@code SYNC_RECEIVED} session since the last call, else -1: only such a send waits for it. The lane's thread only.
     */
    long takeReceivedToAck(int lane) {
        return positions.takeReceivedToAck(lane);
    }

    /**
     * Starts a lane's stream, ending any handover of its sessions: one that did not resume leaves each a gap only a
     * snapshot fills, and nothing to resume from.
     */
    void streamStarted(int lane, boolean resumed) {
        forEachOf(lane, session -> session.streamStarted(resumed));
        positions.streamStarted(lane, resumed);
    }

    /**
     * The leader stopped cleanly after sending every event of the lane, so each of its sessions complete here holds
     * exactly what the leader last wrote: taking it over needs no seqnum jump. Holds until the lane's next stream.
     */
    void handedOver(int lane) {
        forEachOf(lane, ReplicaSession::handOver);
    }

    /**
     * Every session the leader replicates is in sync here: one refused is missing, although nothing is replicated
     * into it.
     */
    public boolean isInSync() {
        for (ReplicaSession session : sessionsByKey) {
            if (session != null && session.isOutOfSync()) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@link #isInSync()} for the sessions of one lane.
     */
    boolean isLaneInSync(int lane) {
        ReplicaSession[] sessions = sessionsByKey;
        int lanes = positions.count();
        for (int key = lane; key < sessions.length; key += lanes) {
            if (sessions[key] != null && sessions[key].isOutOfSync()) {
                return false;
            }
        }
        return true;
    }

    private void forEachOf(int lane, Consumer<ReplicaSession> action) {
        ReplicaSession[] sessions = sessionsByKey;
        int lanes = positions.count();
        for (int key = lane; key < sessions.length; key += lanes) {
            if (sessions[key] != null) {
                action.accept(sessions[key]);
            }
        }
    }

    /**
     * Binds the leader's key to the session configured here with that id and type, opening and starting its store
     * the first time, when it is not the leader's own storage. A session refused is looked at again on the next
     * connection, its storage being readable by then perhaps. One the leader no longer replicates, or with another
     * replication mode here, has its store closed, and is caught up again by snapshot once both agree it is replicated.
     */
    synchronized SessionAnswer bind(int key, FixSessionType fixSessionType, String fixSessionId, ReplicationMode leaderMode,
                                    @Nullable String leaderStorageIdentity) {
        ReplicaSession current = key < sessionsByKey.length ? sessionsByKey[key] : null;
        if (current != null && current.matches(fixSessionType, fixSessionId) && current.isReplicated()
                && leaderMode != ReplicationMode.NONE && leaderMode == binder.modeHere(fixSessionType, fixSessionId)) {
            // switched between the SYNC modes: the same copy, only its acknowledgement changes
            current.setLeaderMode(leaderMode);
            return current.answer();
        }
        if (current != null) {
            current.stop(Deadline.unlimited());
        }
        SessionAnswer answer = put(key, binder.open(fixSessionType, fixSessionId, leaderMode, leaderStorageIdentity));
        if (answer == SessionAnswer.NOT_REPLICATED) {
            log.info("FIX session {} {} is not replicated: the leader's settings do not ask for it", fixSessionType, fixSessionId);
        } else if (answer == SessionAnswer.SHARED_STORAGE) {
            log.error("FIX session {} {} is not replicated here: its storage is the leader's own (the same database, "
                    + "schema and table prefix, or the same directory); give each node its own", fixSessionType, fixSessionId);
        } else if (answer == SessionAnswer.MODE_MISMATCH) {
            log.error("FIX session {} {} is not replicated here: the leader's settings give it replication mode {}, this "
                    + "node's {}", fixSessionType, fixSessionId, leaderMode, binder.modeHere(fixSessionType, fixSessionId));
        } else if (answer != SessionAnswer.SNAPSHOT_NEEDED) {
            log.warn("FIX session {} {} is not replicated here: {}", fixSessionType, fixSessionId, answer);
        }
        return answer;
    }

    /**
     * Republishes the array, so a thread reading the sessions of another lane sees the one put.
     */
    private SessionAnswer put(int key, ReplicaSession session) {
        ReplicaSession[] sessions = sessionsByKey;
        if (key >= sessions.length) {
            sessions = Arrays.copyOf(sessions, Math.max(key + 1, sessions.length * 2));
        }
        sessions[key] = session;
        sessionsByKey = sessions;
        return session.answer();
    }


    /**
     * What the stores of the lane's sessions have accepted and not stored yet. Allocates nothing.
     */
    long getPendingWrites(int lane) {
        ReplicaSession[] sessions = sessionsByKey;
        int lanes = positions.count();
        long pendingWrites = 0;
        for (int key = lane; key < sessions.length; key += lanes) {
            ReplicaSession session = sessions[key];
            if (session != null) {
                pendingWrites += session.getPendingWrites();
            }
        }
        return pendingWrites;
    }

    /**
     * Binds again each session refused as unknown whose settings the engine has by now, or for a mode mismatch its
     * settings here now agree with: a follower's connectors may register their sessions after the leader announced
     * them, and a settings change may reach this node after the leader. Either would otherwise stay refused until
     * the next connection.
     *
     * @param onBound told each key of the lane bound now, with its answer for the leader
     */
    synchronized void bindAgain(int lane, ObjIntConsumer<SessionAnswer> onBound) {
        int lanes = positions.count();
        for (int key = lane; key < sessionsByKey.length; key += lanes) {
            ReplicaSession refused = sessionsByKey[key];
            if (refused == null || !refused.isRefusedUntilSettingsChange() || !binder.settingsHereNowAgree(refused)) {
                continue;
            }
            SessionAnswer answer = put(key, binder.open(refused.getFixSessionType(), refused.getFixSessionId(),
                    refused.getLeaderMode(), refused.getLeaderStorageIdentity()));
            log.info("FIX session {} {} is now configured here as on the leader: {}", refused.getFixSessionType(),
                    refused.getFixSessionId(), answer);
            onBound.accept(answer, key);
        }
    }

    boolean hasSessionsToBindAgain(int lane) {
        ReplicaSession[] sessions = sessionsByKey;
        int lanes = positions.count();
        for (int key = lane; key < sessions.length; key += lanes) {
            if (sessions[key] != null && sessions[key].isRefusedUntilSettingsChange()) {
                return true;
            }
        }
        return false;
    }


    /**
     * @return false for a key the leader never bound, or of another lane
     */
    boolean apply(int lane, long epoch, long offset, int key, StoreEventType eventType, long seqNum,
                  ByteBuffer receiveBuffer, int messageStart, int messageLength, byte fragmentFlags) {
        ReplicaSession session = session(lane, key);
        if (session == null || !session.apply(eventType, seqNum, receiveBuffer, messageStart, messageLength, fragmentFlags)) {
            return false;
        }
        positions.applied(lane, epoch, offset, session.getLeaderMode() == ReplicationMode.SYNC_RECEIVED);
        return true;
    }

    /**
     * @return false for a key the leader never bound, of another lane, or not replicated here
     */
    boolean snapshotBegin(int lane, int key, long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        ReplicaSession session = replicatedSession(lane, key);
        if (session == null) {
            return false;
        }
        session.snapshotBegin(nextIncomingSeqNum, nextOutgoingSeqNum);
        return true;
    }

    boolean snapshotMessage(int lane, int key, long seqNum, ByteBuffer receiveBuffer, int messageStart,
                            int messageLength, byte fragmentFlags) {
        ReplicaSession session = replicatedSession(lane, key);
        return session != null && session.snapshotMessage(seqNum, receiveBuffer, messageStart, messageLength, fragmentFlags);
    }

    /**
     * @return false when the session is unknown or the count is not what was received
     */
    boolean snapshotEnd(int lane, int key, int messageCount, long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        ReplicaSession session = replicatedSession(lane, key);
        return session != null && session.snapshotEnd(messageCount, nextIncomingSeqNum, nextOutgoingSeqNum);
    }

    /**
     * Writes what the frames of a lane's read left pending, before it is acknowledged, then counts their events
     * applied: a write failing leaves them unapplied, so a reconnection resumes before them.
     */
    void writeApplied(int lane) {
        forEachOf(lane, ReplicaSession::writePending);
        positions.written(lane);
    }

    /**
     * Whether the leader waits for this follower to receive the session's events, not to apply them. Any thread: the
     * network thread reads it for a lane applied on another.
     */
    boolean acksOnReceipt(int lane, int key) {
        ReplicaSession session = session(lane, key);
        return session != null && session.getLeaderMode() == ReplicationMode.SYNC_RECEIVED;
    }

    private @Nullable ReplicaSession session(int lane, int key) {
        ReplicaSession[] sessions = sessionsByKey;
        return key < sessions.length && carries(lane, key) ? sessions[key] : null;
    }

    private @Nullable ReplicaSession replicatedSession(int lane, int key) {
        ReplicaSession session = session(lane, key);
        return session == null || !session.isReplicated() ? null : session;
    }

    /**
     * The store replaying the session, null when it is not replicated here.
     */
    public @Nullable FixSessionMessagesStore getStore(FixSessionType fixSessionType, String fixSessionId) {
        ReplicaSession session = find(fixSessionType, fixSessionId);
        return session == null || !session.isReplicated() ? null : session.getStore();
    }

    private @Nullable ReplicaSession find(FixSessionType fixSessionType, String fixSessionId) {
        for (ReplicaSession session : sessionsByKey) {
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
        if (session != null) {
            session.describe(status);
        }
    }

    /**
     * Moves the outgoing seqnum of every session configured with replication by {@code jump}, before this node runs
     * them as leader: in the replica's store, or in one opened for it when the replica holds none (refused or never
     * announced). A session the old leader {@link #handedOver(int) handed
     * over} cleanly keeps its seqnum.
     *
     * @return the sessions taken over, moved or handed over
     */
    public List<FixSessionSettings> jumpOutgoingSeqNums(long jump) {
        List<FixSessionSettings> takenOver = engine.getFixSessionsSettings().stream()
                .filter(settings -> settings.getHighAvailability().getReplicationMode() != ReplicationMode.NONE)
                .collect(Collectors.toList());
        takenOver.forEach(settings -> takeOver(settings, jump));
        return takenOver;
    }

    private void takeOver(FixSessionSettings settings, long jump) {
        ReplicaSession session = find(settings.getFixSessionType(), settings.getFixSessionId().toString());
        if (session != null && session.isHandedOver()) {
            log.info("FIX session {} {} taken over where the old leader cleanly left it", settings.getFixSessionType(),
                    settings.getFixSessionId());
        } else if (session != null && session.isReplicated()) {
            jumpOutgoingSeqNum(settings, session.getStore(), jump);
        } else {
            FixSessionMessagesStore store = engine.openStore(settings).start();
            try {
                jumpOutgoingSeqNum(settings, store, jump);
            } finally {
                store.stop(Deadline.unlimited());
            }
        }
    }

    /**
     * Stops every store, before the sessions start on their own.
     */
    public synchronized void close(Deadline deadline) {
        for (ReplicaSession session : sessionsByKey) {
            if (session != null) {
                session.stop(deadline);
            }
        }
        Arrays.fill(sessionsByKey, null);
    }

}
