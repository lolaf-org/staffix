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

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.ha.FixEngineHa.EngineAccess;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.replicos.spi.ReplicaSinks;
import org.lolaf.replicos.spi.StreamAnswer;
import org.lolaf.replicos.spi.WireFormatException;

import java.util.Optional;

/**
 * Decides, from this node's own settings, whether the follower holds a session the leader announces, and opens the
 * store of one it replicates. The engine's settings are listed for each, a session being announced rarely. The
 * {@link SessionSettingsStream} is always replicated, into the settings comparison.
 */
@Slf4j
public final class FixSessionSinks implements ReplicaSinks {

    private final EngineAccess engine;
    private final SessionSettingsReplica settingsReplica;

    /**
     * @param settingsComparison what the leader's session settings stream is applied into
     */
    public FixSessionSinks(EngineAccess engine, SessionSettingsComparison settingsComparison) {
        this.engine = engine;
        this.settingsReplica = new SessionSettingsReplica(settingsComparison);
    }

    @Override
    public int getApplicationId() {
        return ReplicatedSessions.APPLICATION_ID;
    }

    /**
     * Opens and starts the session's store, unless the session is not replicated here: its settings here are missing,
     * or its storage is the leader's own. Its replication mode is the leader's alone.
     */
    @Override
    public Binding open(String streamName, @Nullable String leaderStorageIdentity) {
        if (SessionSettingsStream.STREAM_NAME.equals(streamName)) {
            return Binding.replicated(settingsReplica);
        }
        TypedSessionId session = sessionOf(streamName);
        FixSessionType fixSessionType = session.getFixSessionType();
        String fixSessionId = session.getFixSessionId();
        FixSessionSettings settings = findSettings(session).orElse(null);
        if (settings == null) {
            return refused(session, StreamAnswer.UNKNOWN_STREAM);
        }
        FixSessionMessagesStore store = engine.openStore(settings);
        String storageIdentity = store.getStorageIdentity().orElse(null);
        if (storageIdentity == null || leaderStorageIdentity == null) {
            return refused(session, StreamAnswer.UNKNOWN_STORAGE);
        }
        if (storageIdentity.equals(leaderStorageIdentity)) {
            log.error("FIX session {} {} is not replicated here: its storage is the leader's own (the same database, "
                    + "schema and table prefix, or the same directory); give each node its own", fixSessionType, fixSessionId);
            return Binding.refused(StreamAnswer.SHARED_STORAGE);
        }
        store.start();
        return Binding.replicated(new FixSessionReplica(store, fixSessionId));
    }

    @Override
    public boolean isConfigured(String streamName) {
        return SessionSettingsStream.STREAM_NAME.equals(streamName) || findSettings(sessionOf(streamName)).isPresent();
    }

    private static Binding refused(TypedSessionId session, StreamAnswer refusal) {
        log.warn("FIX session {} {} is not replicated here: {}", session.getFixSessionType(), session.getFixSessionId(),
                NotReplicatedReason.of(refusal));
        return Binding.refused(refusal);
    }

    private static TypedSessionId sessionOf(String streamName) {
        try {
            return TypedSessionId.ofStreamName(streamName);
        } catch (IllegalArgumentException e) {
            throw new WireFormatException(e.getMessage());
        }
    }

    private Optional<FixSessionSettings> findSettings(TypedSessionId session) {
        return engine.getFixSessionsSettings().stream()
                .filter(settings -> settings.getFixSessionType() == session.getFixSessionType()
                        && settings.getFixSessionId().toString().equals(session.getFixSessionId()))
                .findFirst();
    }
}
