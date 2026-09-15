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
package org.lolaf.staffix.spring.boot.actuator.endpoint;

import org.lolaf.staffix.spring.boot.actuator.ActuatorSessionStats;
import org.lolaf.staffix.spring.boot.actuator.ActuatorSessionsRegistry;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code /actuator/fixsessions} endpoint: every session, its state and its sequence numbers.
 */
@Endpoint(id = "fix-sessions")
public class FixSessionsEndpoint {

    private final ActuatorSessionsRegistry registry;

    public FixSessionsEndpoint(ActuatorSessionsRegistry registry) {
        this.registry = registry;
    }

    private static Map<String, Object> toJson(ActuatorSessionStats stats) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", stats.getFixSession().getFixSessionId().getId());
        m.put("fixSessionId", stats.getFixSession().getFixSessionId().toString());
        m.put("instanceId", stats.getFixInstanceId());
        m.put("group", stats.getFixSession().getFixSessionId().getGroup());
        m.put("state", stats.getState().name());
        m.put("messagesReceived", stats.getMessagesReceived());
        m.put("messagesSent", stats.getMessagesSent());
        m.put("bytesReceived", stats.getBytesReceived());
        m.put("bytesSent", stats.getBytesSent());
        m.put("lastEventEpochMillis", stats.getLastEventEpochMillis());
        m.put("lastLogonEpochMillis", stats.getLastLogonEpochMillis());
        m.put("lastLogoutEpochMillis", stats.getLastLogoutEpochMillis());
        return m;
    }

    @ReadOperation
    public Map<String, Object> list() {
        List<Map<String, Object>> sessions = registry.snapshot().stream()
                .map(FixSessionsEndpoint::toJson)
                .toList();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("count", sessions.size());
        root.put("sessions", sessions);
        return root;
    }

    @ReadOperation
    public Map<String, Object> getOne(@Selector String id) {
        return registry.findById(id)
                .map(FixSessionsEndpoint::toJson)
                .orElse(null);
    }
}
