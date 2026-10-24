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

import lombok.Value;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The engine's replicated sessions, each with a key that stays its own for the life of the engine, across the
 * restarts that rebuild a session. Keys are dense from 0, in the order sessions were first registered.
 */
public final class ReplicatedSessions {

    private final Map<Id, ReplicatedSession> byId = new HashMap<>();
    private final List<ReplicatedSession> byKey = new CopyOnWriteArrayList<>();

    /**
     * Registers a session as it is built, or takes the latest settings and store of one already known.
     */
    synchronized ReplicatedSession register(FixSessionSettings settings, FixSessionMessagesStore store) {
        ReplicatedSession session = byId.computeIfAbsent(new Id(settings.getFixSessionId().toString(), settings.getFixSessionType()),
                id -> {
                    ReplicatedSession added = new ReplicatedSession(byKey.size(), settings.getFixSessionId(), settings.getFixSessionType());
                    byKey.add(added);
                    return added;
                });
        FixSessionSettings.HaSessionSettings haSettings = settings.getHaSessionSettings();
        session.update(haSettings.getReplicationMode(), haSettings.getReplicationAckTimeout(), store);
        return session;
    }

    /**
     * Any thread.
     */
    public ReplicatedSession get(int key) {
        return byKey.get(key);
    }

    /**
     * How many sessions were registered; keys run from 0 to this, excluded. Any thread.
     */
    public int count() {
        return byKey.size();
    }

    public Collection<ReplicatedSession> all() {
        return byKey;
    }

    /**
     * One id may be configured as an initiator and as an acceptor: two sessions.
     */
    @Value
    private static class Id {
        String fixSessionId;
        FixSessionType fixSessionType;
    }
}
