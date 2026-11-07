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

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.ha.FixEngineHa.EngineAccess;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.ha.wire.SessionAnswer;

import java.util.Optional;

/**
 * Decides, from this node's own settings, how the follower holds a session the leader announces, and opens the store
 * of one it replicates. The engine's settings are listed for each, a session being announced rarely.
 */
@RequiredArgsConstructor
final class SessionBinder {

    private final EngineAccess engine;

    /**
     * Opens and starts the session's store, unless the session is not replicated here: its settings here are missing
     * or disagree, or its storage is the leader's own.
     */
    ReplicaSession open(FixSessionType fixSessionType, String fixSessionId, ReplicationMode leaderMode,
                        @Nullable String leaderStorageIdentity) {
        ReplicaSession session = newSession(fixSessionType, fixSessionId, leaderMode, leaderStorageIdentity);
        session.setLeaderMode(leaderMode);
        return session;
    }

    /**
     * Whether a session refused for its settings here would now be bound: they appeared, or agree with the leader's.
     */
    boolean settingsHereNowAgree(ReplicaSession refused) {
        return findSettings(refused.getFixSessionType(), refused.getFixSessionId())
                .filter(settings -> refused.getNotReplicatedAnswer() == SessionAnswer.UNKNOWN_SESSION
                        || settings.getHighAvailability().getReplicationMode() == refused.getLeaderMode())
                .isPresent();
    }

    /**
     * Null for a session not configured here.
     */
    @Nullable ReplicationMode modeHere(FixSessionType fixSessionType, String fixSessionId) {
        return findSettings(fixSessionType, fixSessionId)
                .map(settings -> settings.getHighAvailability().getReplicationMode())
                .orElse(null);
    }

    private ReplicaSession newSession(FixSessionType fixSessionType, String fixSessionId, ReplicationMode leaderMode,
                                      @Nullable String leaderStorageIdentity) {
        FixSessionSettings settings = findSettings(fixSessionType, fixSessionId).orElse(null);
        if (settings == null) {
            return leaderMode == ReplicationMode.NONE
                    ? ReplicaSession.notReplicated(fixSessionType, fixSessionId, SessionAnswer.NOT_REPLICATED)
                    : ReplicaSession.refused(fixSessionType, fixSessionId, SessionAnswer.UNKNOWN_SESSION, leaderMode, leaderStorageIdentity);
        }
        if (settings.getHighAvailability().getReplicationMode() != leaderMode) {
            return ReplicaSession.refused(fixSessionType, fixSessionId, SessionAnswer.MODE_MISMATCH, leaderMode, leaderStorageIdentity);
        }
        if (leaderMode == ReplicationMode.NONE) {
            return ReplicaSession.notReplicated(fixSessionType, fixSessionId, SessionAnswer.NOT_REPLICATED);
        }
        FixSessionMessagesStore store = engine.openStore(settings);
        String storageIdentity = store.getStorageIdentity().orElse(null);
        if (storageIdentity == null || leaderStorageIdentity == null) {
            return ReplicaSession.notReplicated(fixSessionType, fixSessionId, SessionAnswer.UNKNOWN_STORAGE);
        }
        if (storageIdentity.equals(leaderStorageIdentity)) {
            return ReplicaSession.notReplicated(fixSessionType, fixSessionId, SessionAnswer.SHARED_STORAGE);
        }
        store.start();
        return ReplicaSession.replicated(fixSessionType, fixSessionId, store);
    }

    private Optional<FixSessionSettings> findSettings(FixSessionType fixSessionType, String fixSessionId) {
        return engine.getFixSessionsSettings().stream()
                .filter(settings -> settings.getFixSessionType() == fixSessionType
                        && settings.getFixSessionId().toString().equals(fixSessionId))
                .findFirst();
    }
}
