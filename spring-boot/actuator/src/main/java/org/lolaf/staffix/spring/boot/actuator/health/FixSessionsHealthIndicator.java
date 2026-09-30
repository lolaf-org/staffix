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
package org.lolaf.staffix.spring.boot.actuator.health;

import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.spring.boot.actuator.ActuatorSessionStats;
import org.lolaf.staffix.spring.boot.actuator.ActuatorSessionsRegistry;
import org.lolaf.staffix.spring.boot.actuator.spring.ActuatorMonitoringProps;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports the engine's health from its sessions.
 *
 * <p>Only a session that should be logged in and is not counts as DOWN: one outside its schedule, or logged out
 * on purpose, is UP. The details carry the schedule and the intent so that a DOWN can be told apart.
 */
public class FixSessionsHealthIndicator implements HealthIndicator {

    private final ActuatorSessionsRegistry registry;
    private final ActuatorMonitoringProps props;

    public FixSessionsHealthIndicator(ActuatorSessionsRegistry registry, ActuatorMonitoringProps props) {
        this.registry = registry;
        this.props = props;
    }

    private static Map<String, Object> describe(ActuatorSessionStats stats, boolean up) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("status", up ? "UP" : "DOWN");
        d.put("state", stats.getState().name());
        d.put("desiredState", stats.getFixSession().getDesiredState().name());
        d.put("withinSessionTime", stats.getFixSession().isWithinSessionTime());
        d.put("messagesReceived", stats.getMessagesReceived());
        d.put("messagesSent", stats.getMessagesSent());
        d.put("lastEventEpochMillis", stats.getLastEventEpochMillis());
        return d;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean anyUnhealthy = false;
        for (ActuatorSessionStats stats : registry.snapshot()) {
            boolean up = isUp(stats);
            if (!up) {
                anyUnhealthy = true;
            }
            details.put(stats.getFixSession().getFixSessionId().getId(), describe(stats, up));
        }
        Status overall = anyUnhealthy ? Status.DOWN : Status.UP;
        return Health.status(overall).withDetails(details).build();
    }

    private boolean isUp(ActuatorSessionStats stats) {
        if (!props.isFixSessionStateContributesToHealthStatus()) {
            return true;
        }
        FixSession fixSession = stats.getFixSession();
        boolean shouldBeLoggedIn = fixSession.isWithinSessionTime()
                && fixSession.getDesiredState() == FixSessionState.LOGGED_IN;
        return !shouldBeLoggedIn || stats.getState() == FixSessionState.LOGGED_IN;
    }
}
