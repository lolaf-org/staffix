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

import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.ha.replication.spi.LeaderStream;
import org.lolaf.staffix.ha.replication.spi.LeaderStreams;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;

/**
 * The engine's replicated streams, its FIX sessions and its own, each with a key that stays its own for the life of the
 * engine, across the restarts that rebuild a session. Keys are dense from 0, in the order streams were first
 * registered.
 */
public final class ReplicatedSessions implements LeaderStreams {

    /**
     * Identifies FIX session streams in HELLO and WELCOME, so a peer replicating another application is refused:
     * 'SFIX'.
     */
    public static final int APPLICATION_ID = 0x53464958;

    /**
     * By type first, as one id may be configured as an initiator and as an acceptor: two sessions.
     */
    private final Map<FixSessionType, Map<FixSessionId, ReplicatedSession>> byTypeAndId;
    private final List<LeaderStream> byKey;
    private final List<ReplicatedSession> sessions;

    public ReplicatedSessions() {
        this.byTypeAndId = new EnumMap<>(FixSessionType.class);
        this.byKey = new CopyOnWriteArrayList<>();
        this.sessions = new CopyOnWriteArrayList<>();
        for (FixSessionType fixSessionType : FixSessionType.values()) {
            byTypeAndId.put(fixSessionType, new HashMap<>());
        }
    }

    /**
     * Registers a session as it is built, or takes the latest settings and store of one already known.
     */
    synchronized ReplicatedSession register(FixSessionSettings settings, FixSessionMessagesStore store) {
        ReplicatedSession.Config config = ReplicatedSession.Config.of(settings.getHighAvailability(), store);
        Map<FixSessionId, ReplicatedSession> byId = byTypeAndId.get(settings.getFixSessionType());
        ReplicatedSession known = byId.get(settings.getFixSessionId());
        if (known != null) {
            known.update(config);
            return known;
        }
        ReplicatedSession added = add(key -> new ReplicatedSession(key, settings.getFixSessionId(), settings.getFixSessionType(), config));
        byId.put(settings.getFixSessionId(), added);
        sessions.add(added);
        return added;
    }

    /**
     * Registers a stream of the engine's own, at the next key.
     */
    synchronized <T extends LeaderStream> T add(IntFunction<T> newStream) {
        // built before it is listed, so a sender never finds it half made
        T added = newStream.apply(byKey.size());
        byKey.add(added);
        return added;
    }

    @Override
    public int getApplicationId() {
        return APPLICATION_ID;
    }

    @Override
    public LeaderStream get(int key) {
        return byKey.get(key);
    }

    /**
     * Null for a session not built yet. Any thread.
     */
    synchronized @Nullable ReplicatedSession find(FixSessionId fixSessionId, FixSessionType fixSessionType) {
        return byTypeAndId.get(fixSessionType).get(fixSessionId);
    }

    @Override
    public int count() {
        return byKey.size();
    }

    /**
     * The FIX sessions, by key.
     */
    public List<ReplicatedSession> all() {
        return sessions;
    }
}
