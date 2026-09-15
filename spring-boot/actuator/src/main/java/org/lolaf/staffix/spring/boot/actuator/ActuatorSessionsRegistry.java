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
package org.lolaf.staffix.spring.boot.actuator;

import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the {@link ActuatorSessionStats} for every session observed by an
 * {@link ActuatorMonitoringManager}. Read by the actuator beans (health, endpoint, info)
 * to expose session state without touching the FIX engine internals.
 */
public class ActuatorSessionsRegistry {

    private final Map<FixSessionId, ActuatorSessionStats> stats = new ConcurrentHashMap<>();

    ActuatorSessionStats register(String fixInstanceId, FixSession fixSession) {
        return stats.computeIfAbsent(fixSession.getFixSessionId(), id -> new ActuatorSessionStats(fixInstanceId, fixSession));
    }

    void unregister(FixSessionId fixSessionId) {
        stats.remove(fixSessionId);
    }

    void clear() {
        stats.clear();
    }

    public Optional<ActuatorSessionStats> find(FixSessionId fixSessionId) {
        return Optional.ofNullable(stats.get(fixSessionId));
    }

    public Optional<ActuatorSessionStats> findById(String id) {
        return stats.values().stream().filter(s -> s.getFixSession().getFixSessionId().getId().equals(id)).findFirst();
    }

    public Collection<ActuatorSessionStats> snapshot() {
        return Collections.unmodifiableCollection(stats.values());
    }
}