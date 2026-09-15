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
package org.lolaf.staffix.impl.session;

import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Every session the engine knows, by id, so an inbound connection can be matched to its configuration.
 */
public class FixSessionRegistryImpl implements FixSessionRegistry {

    @SuppressWarnings("unchecked")
    private static final Optional<FixSession>[] EMPTY = new Optional[0];

    private final Map<FixSessionId, Optional<FixSession>> sessions = new ConcurrentHashMap<>();
    private Optional<FixSession>[] sessionsArray = EMPTY;

    public void register(FixSession session) {
        if (sessions.containsKey(session.getFixSessionId())) {
            throw new IllegalStateException("Fix session id " + session.getFixSessionId() + " is already registered");
        }
        sessions.put(session.getFixSessionId(), Optional.of(session));
        sessionsArray = sessions.values().toArray(EMPTY);
    }

    public void unregister(FixSession session) {
        if (sessions.remove(session.getFixSessionId(), Optional.of(session))) {
            sessionsArray = sessions.values().toArray(EMPTY);
        }
    }

    @Override
    public Optional<FixSession> find(FixSessionId id) {
        return sessions.getOrDefault(id, Optional.empty());
    }

    @Override
    public Optional<FixSession> find(Predicate<FixSessionId> predicate) {
        Optional<FixSession>[] localSessionsArray = this.sessionsArray;
        for (Optional<FixSession> session : localSessionsArray) {
            if (predicate.test(session.orElseThrow().getFixSessionId())) {
                return session;
            }
        }
        return Optional.empty();
    }

    @Override
    public List<FixSession> findAll(Predicate<FixSessionId> predicate) {
        List<FixSession> result = new ArrayList<>();
        Optional<FixSession>[] localSessionsArray = this.sessionsArray;
        for (Optional<FixSession> session : localSessionsArray) {
            FixSession fixSession = session.orElseThrow();
            if (predicate.test(fixSession.getFixSessionId())) {
                result.add(fixSession);
            }
        }
        return result;
    }
}
