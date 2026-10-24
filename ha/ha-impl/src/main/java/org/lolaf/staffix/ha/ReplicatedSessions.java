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

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The engine's replicated sessions, each with a key that stays its own for the life of the engine, across the
 * restarts that rebuild a session.
 */
final class ReplicatedSessions {

    private final Map<Id, ReplicatedSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger nextKey = new AtomicInteger();

    ReplicatedSession register(FixSessionSettings settings) {
        return sessions.computeIfAbsent(new Id(settings.getFixSessionId().toString(), settings.getFixSessionType()),
                id -> new ReplicatedSession(nextKey.getAndIncrement(), settings.getFixSessionId(), settings.getFixSessionType()));
    }

    Collection<ReplicatedSession> all() {
        return sessions.values();
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
