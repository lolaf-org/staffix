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
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.FixEngineHa.EngineAccess;
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.ha.follower.ReplicaStream;
import org.lolaf.staffix.ha.follower.ReplicaStreams;

import java.util.List;
import java.util.stream.Collectors;

/**
 * What this follower holds of the leader's FIX sessions: their replicated streams, and what a FIX session needs on top
 * of them, its status and its seqnums on taking over.
 */
@Slf4j
public final class ReplicaSessions {

    private final EngineAccess engine;
    @Getter
    private final ReplicaStreams streams;
    @Getter
    private final SessionSettingsComparison settingsComparison;

    public ReplicaSessions(EngineAccess engine) {
        this.engine = engine;
        this.settingsComparison = new SessionSettingsComparison(engine);
        this.streams = new ReplicaStreams(new FixSessionBinder(engine), settingsComparison);
    }

    public boolean isInSync() {
        return streams.isInSync();
    }

    public int getLanes() {
        return streams.getLanes();
    }

    /**
     * Adds what this follower holds of the session to its status, nothing for a session the leader never announced.
     * Any thread.
     */
    public void describe(FixSessionType fixSessionType, String fixSessionId, HaSessionStatus.HaSessionStatusBuilder status) {
        ReplicaStream stream = streams.find(new TypedSessionId(fixSessionType, fixSessionId).toStreamName());
        if (stream == null) {
            return;
        }
        status.handedOver(stream.isHandedOver());
        if (stream.isReplicated()) {
            status.inSync(stream.isInSync());
            replica(stream).describe(status);
        } else {
            status.notReplicatedReason(NotReplicatedReason.of(stream.getNotReplicatedAnswer()));
        }
    }

    /**
     * The store replaying the session, null when it is not replicated here.
     */
    public @Nullable FixSessionMessagesStore getStore(FixSessionType fixSessionType, String fixSessionId) {
        ReplicaStream stream = streams.find(new TypedSessionId(fixSessionType, fixSessionId).toStreamName());
        return stream == null || !stream.isReplicated() ? null : replica(stream).getStore();
    }

    /**
     * Moves the outgoing seqnum of every session configured with replication by {@code jump}, before this node runs
     * them as leader: in the replica's store, or in one opened for it when the replica holds none (refused or never
     * announced). A session the old leader handed over cleanly keeps its seqnum.
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
        ReplicaStream stream = streams.find(TypedSessionId.of(settings).toStreamName());
        if (stream != null && stream.isHandedOver()) {
            log.info("FIX session {} {} taken over where the old leader cleanly left it", settings.getFixSessionType(),
                    settings.getFixSessionId());
        } else if (stream != null && stream.isReplicated()) {
            PromotionSeqNumJumps.jumpOutgoingSeqNum(settings, replica(stream).getStore(), jump);
        } else {
            FixSessionMessagesStore store = engine.openStore(settings).start();
            try {
                PromotionSeqNumJumps.jumpOutgoingSeqNum(settings, store, jump);
            } finally {
                store.stop(Deadline.unlimited());
            }
        }
    }

    /**
     * Every sink is a session's, {@link FixSessionBinder} binding them all.
     */
    private static FixSessionReplica replica(ReplicaStream stream) {
        return (FixSessionReplica) stream.getSink();
    }

    /**
     * Stops every store, before the sessions start on their own.
     */
    public void close(Deadline deadline) {
        streams.close(deadline);
    }
}
