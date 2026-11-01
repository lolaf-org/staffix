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
import java.util.List;
import java.util.Optional;
import java.util.function.ObjIntConsumer;
import java.util.stream.Collectors;

/**
 * What the follower holds of the leader's sessions, kept across connections so a reconnection can resume: each
 * session's store, opened outside the session, whether it is complete, and the last event applied.
 *
 * <p>A session is complete once a snapshot filled it, and until a stream fails to resume. A snapshot resets the store
 * before filling it, so nothing older than the leader's copy survives. It may hold messages the stream then brings
 * again, which are skipped, a store being free to refuse a seqnum it holds; seqnum events are absolute, so one
 * applied twice changes nothing. A read's writes are gathered per session and written once, before it is
 * acknowledged. Written from the connection's IO thread only.
 */
@Slf4j
public final class Replica {

    private final EngineAccess engine;
    private volatile ReplicaSession[] sessionsByKey = new ReplicaSession[16];
    @Getter
    private volatile long lastAppliedEpoch;
    /**
     * The offset of the last event written, -1 for none.
     */
    @Getter
    private volatile long lastAppliedOffset = -1;
    private long pendingEpoch;
    /**
     * The offset of the last event applied but not yet written, promoted by {@link #writeApplied()}.
     */
    private long pendingOffset = -1;

    public Replica(EngineAccess engine) {
        this.engine = engine;
    }

    private static void jumpOutgoingSeqNum(FixSessionSettings settings, FixSessionMessagesStore store, long jump) {
        long outgoingSeqNum = store.getOutgoingSeqNum();
        store.storeNextOutgoingSeqNum(outgoingSeqNum + jump);
        log.warn("FIX session {} {} outgoing sequence number moved from {} to {}, past what the old leader may have sent "
                + "unreplicated", settings.getFixSessionType(), settings.getFixSessionId(), outgoingSeqNum, outgoingSeqNum + jump);
    }

    /**
     * Starts a stream, ending any handover: one that did not resume leaves every session a gap only a snapshot fills,
     * and nothing to resume from.
     */
    void streamStarted(boolean resumed) {
        for (ReplicaSession session : sessionsByKey) {
            if (session != null) {
                session.streamStarted(resumed);
            }
        }
        if (!resumed) {
            lastAppliedOffset = -1;
        }
        pendingEpoch = lastAppliedEpoch;
        pendingOffset = lastAppliedOffset;
    }

    /**
     * The leader stopped cleanly after sending every event, so each session complete here, or whose storage is the
     * leader's own, holds exactly what the leader last wrote: taking it over needs no seqnum jump. Holds until the
     * next stream.
     */
    void handedOver() {
        for (ReplicaSession session : sessionsByKey) {
            if (session != null) {
                session.handOver();
            }
        }
    }

    /**
     * Every session the leader replicates is complete here, or held by the leader's own storage: one refused for an
     * unknown session or storage is missing, although nothing is replicated into it.
     */
    public boolean isComplete() {
        for (ReplicaSession session : sessionsByKey) {
            if (session != null && !session.isCaughtUp()) {
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
    SessionStatus bind(int key, FixSessionType fixSessionType, String fixSessionId, ReplicationMode leaderMode,
                       @Nullable String leaderStorageIdentity) {
        ReplicaSession current = key < sessionsByKey.length ? sessionsByKey[key] : null;
        if (current != null && current.matches(fixSessionType, fixSessionId) && current.isReplicated()
                && leaderMode != ReplicationMode.NONE && leaderMode == modeHere(fixSessionType, fixSessionId)) {
            return current.status();
        }
        if (current != null) {
            current.stop(Deadline.unlimited());
        }
        if (key >= sessionsByKey.length) {
            sessionsByKey = Arrays.copyOf(sessionsByKey, Math.max(key + 1, sessionsByKey.length * 2));
        }
        sessionsByKey[key] = openSession(fixSessionType, fixSessionId, leaderMode, leaderStorageIdentity);
        SessionStatus status = sessionsByKey[key].status();
        if (status == SessionStatus.NOT_REPLICATED) {
            log.info("FIX session {} {} is not replicated: the leader's settings do not ask for it", fixSessionType, fixSessionId);
        } else if (status == SessionStatus.SHARED_STORAGE) {
            log.info("FIX session {} {} is not replicated: its storage is the leader's own", fixSessionType, fixSessionId);
        } else if (status == SessionStatus.MODE_MISMATCH) {
            log.error("FIX session {} {} is not replicated here: the leader's settings give it replication mode {}, this "
                    + "node's {}", fixSessionType, fixSessionId, leaderMode, modeHere(fixSessionType, fixSessionId));
        } else if (status != SessionStatus.SNAPSHOT_NEEDED) {
            log.warn("FIX session {} {} is not replicated here: {}", fixSessionType, fixSessionId, status);
        }
        return status;
    }

    private ReplicaSession openSession(FixSessionType fixSessionType, String fixSessionId, ReplicationMode leaderMode,
                                       @Nullable String leaderStorageIdentity) {
        FixSessionSettings settings = findSettings(fixSessionType, fixSessionId).orElse(null);
        if (settings == null) {
            return leaderMode == ReplicationMode.NONE
                    ? ReplicaSession.notReplicated(fixSessionType, fixSessionId, SessionStatus.NOT_REPLICATED)
                    : ReplicaSession.refused(fixSessionType, fixSessionId, SessionStatus.UNKNOWN_SESSION, leaderMode, leaderStorageIdentity);
        }
        if (settings.getHaSessionSettings().getReplicationMode() != leaderMode) {
            return ReplicaSession.refused(fixSessionType, fixSessionId, SessionStatus.MODE_MISMATCH, leaderMode, leaderStorageIdentity);
        }
        if (leaderMode == ReplicationMode.NONE) {
            return ReplicaSession.notReplicated(fixSessionType, fixSessionId, SessionStatus.NOT_REPLICATED);
        }
        FixSessionMessagesStore store = engine.openStore(settings);
        String storageIdentity = store.getStorageIdentity().orElse(null);
        if (storageIdentity == null || leaderStorageIdentity == null) {
            return ReplicaSession.notReplicated(fixSessionType, fixSessionId, SessionStatus.UNKNOWN_STORAGE);
        }
        if (storageIdentity.equals(leaderStorageIdentity)) {
            return ReplicaSession.notReplicated(fixSessionType, fixSessionId, SessionStatus.SHARED_STORAGE);
        }
        store.start();
        return ReplicaSession.replicated(fixSessionType, fixSessionId, store);
    }

    /**
     * Binds again each session refused as unknown whose settings the engine has by now, or for a mode mismatch its
     * settings here now agree with: a standby's connectors may register their sessions after the leader announced
     * them, and a settings change may reach this node after the leader. Either would otherwise stay refused until
     * the next connection.
     *
     * @param onBound told each key bound now, with its status for the leader
     */
    void bindAgain(ObjIntConsumer<SessionStatus> onBound) {
        for (int key = 0; key < sessionsByKey.length; key++) {
            ReplicaSession refused = sessionsByKey[key];
            if (refused == null || !refused.isRefusedUntilSettingsChange() || !settingsHereNowAgree(refused)) {
                continue;
            }
            sessionsByKey[key] = openSession(refused.getFixSessionType(), refused.getFixSessionId(), refused.getLeaderMode(),
                    refused.getLeaderStorageIdentity());
            SessionStatus status = sessionsByKey[key].status();
            log.info("FIX session {} {} is now configured here as on the leader: {}", refused.getFixSessionType(),
                    refused.getFixSessionId(), status);
            onBound.accept(status, key);
        }
    }

    boolean hasSessionsToBindAgain() {
        for (ReplicaSession session : sessionsByKey) {
            if (session != null && session.isRefusedUntilSettingsChange()) {
                return true;
            }
        }
        return false;
    }

    private boolean settingsHereNowAgree(ReplicaSession refused) {
        return findSettings(refused.getFixSessionType(), refused.getFixSessionId())
                .filter(settings -> refused.getNotReplicatedStatus() == SessionStatus.UNKNOWN_SESSION
                        || settings.getHaSessionSettings().getReplicationMode() == refused.getLeaderMode())
                .isPresent();
    }

    private @Nullable ReplicationMode modeHere(FixSessionType fixSessionType, String fixSessionId) {
        return findSettings(fixSessionType, fixSessionId)
                .map(settings -> settings.getHaSessionSettings().getReplicationMode())
                .orElse(null);
    }

    private Optional<FixSessionSettings> findSettings(FixSessionType fixSessionType, String fixSessionId) {
        return engine.getFixSessionsSettings().stream()
                .filter(settings -> settings.getFixSessionType() == fixSessionType
                        && settings.getFixSessionId().toString().equals(fixSessionId))
                .findFirst();
    }

    /**
     * @return false for a key the leader never bound
     */
    boolean apply(long epoch, long offset, int key, StoreEventType eventType, long seqNum, ByteBuffer receiveBuffer,
                  int messageStart, int messageLength, byte fragmentFlags) {
        ReplicaSession session = key < sessionsByKey.length ? sessionsByKey[key] : null;
        if (session == null) {
            return false;
        }
        if (!session.apply(eventType, seqNum, receiveBuffer, messageStart, messageLength, fragmentFlags)) {
            return false;
        }
        pendingEpoch = epoch;
        pendingOffset = offset;
        return true;
    }

    /**
     * @return false for a key the leader never bound, or one not replicated here
     */
    boolean snapshotBegin(int key, long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        ReplicaSession session = replicatedSession(key);
        if (session == null) {
            return false;
        }
        session.snapshotBegin(nextIncomingSeqNum, nextOutgoingSeqNum);
        return true;
    }

    boolean snapshotMessage(int key, long seqNum, ByteBuffer receiveBuffer, int messageStart, int messageLength,
                            byte fragmentFlags) {
        ReplicaSession session = replicatedSession(key);
        return session != null && session.snapshotMessage(seqNum, receiveBuffer, messageStart, messageLength, fragmentFlags);
    }

    /**
     * @return false when the session is unknown or the count is not what was received
     */
    boolean snapshotEnd(int key, int messageCount, long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        ReplicaSession session = replicatedSession(key);
        return session != null && session.snapshotEnd(messageCount, nextIncomingSeqNum, nextOutgoingSeqNum);
    }

    /**
     * Writes what the frames of a read left pending, before it is acknowledged, then counts their events applied: a
     * write failing leaves them unapplied, so a reconnection resumes before them.
     */
    void writeApplied() {
        for (ReplicaSession session : sessionsByKey) {
            if (session != null) {
                session.writePending();
            }
        }
        lastAppliedEpoch = pendingEpoch;
        lastAppliedOffset = pendingOffset;
    }

    private @Nullable ReplicaSession replicatedSession(int key) {
        ReplicaSession session = key < sessionsByKey.length ? sessionsByKey[key] : null;
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
     * them as leader: in the replica's store, or in one opened for it when the replica holds none (its storage shared
     * with the old leader, unidentified, or never announced). A session the old leader {@link #handedOver() handed
     * over} cleanly keeps its seqnum.
     *
     * @return the sessions taken over, moved or handed over
     */
    public List<FixSessionSettings> jumpOutgoingSeqNums(long jump) {
        List<FixSessionSettings> takenOver = engine.getFixSessionsSettings().stream()
                .filter(settings -> settings.getHaSessionSettings().getReplicationMode() != ReplicationMode.NONE)
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
    public void close(Deadline deadline) {
        for (ReplicaSession session : sessionsByKey) {
            if (session != null) {
                session.stop(deadline);
            }
        }
        Arrays.fill(sessionsByKey, null);
    }
}
