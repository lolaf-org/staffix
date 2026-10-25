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
package org.lolaf.staffix.admin.http.dto;

import lombok.Value;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.admin.FixAcceptorSessions;
import org.lolaf.staffix.api.admin.FixInitiatorTargets;
import org.lolaf.staffix.api.alerting.AlertLevel;
import org.lolaf.staffix.api.alerting.ProbeScope;
import org.lolaf.staffix.api.alerting.ScopedProbeState;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionProbeScope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The live state of every session, in one response the console polls; what does not change is in
 * {@link EngineSessions}.
 */
@Value
public class EngineStatus {
    String engineId;
    /**
     * The version of {@link EngineSessions} these sessions belong to.
     */
    String sessionsVersion;
    /**
     * Moves with every alert raised, changed or cleared across the engine; fetch the alerts again when it differs.
     */
    long alertsVersion;
    List<SessionStatus> sessions;

    /**
     * @param probesVersions throws {@code IllegalArgumentException} for a session the engine no longer manages
     */
    public static EngineStatus of(AdminApi adminApi, Map<FixSessionId, FixSession> running, String sessionsVersion,
                                  long alertsVersion, Function<FixSessionId, String> probesVersions) {
        Map<ProbeScope, AlertLevel> alertLevels = worstAlertLevels(adminApi);
        List<SessionStatus> sessions = new ArrayList<>(running.size());
        for (FixInitiatorTargets initiator : adminApi.getInitiatorsTargets()) {
            FixSession session = running.get(initiator.getActiveFixSessionId());
            if (session != null) {
                String name = initiator.getMainTarget().getFixSessionId().getName();
                addIfStillManaged(sessions, adminApi, probesVersions, alertLevels, session, name);
            }
        }
        for (FixAcceptorSessions acceptor : adminApi.getAcceptorsSessions()) {
            for (FixSessionId fixSessionId : acceptor.getFixSessionIds()) {
                FixSession session = running.get(fixSessionId);
                if (session != null) {
                    addIfStillManaged(sessions, adminApi, probesVersions, alertLevels, session, fixSessionId.getName());
                }
            }
        }
        return new EngineStatus(adminApi.getInstanceId(), sessionsVersion, alertsVersion, sessions);
    }

    private static Map<ProbeScope, AlertLevel> worstAlertLevels(AdminApi adminApi) {
        List<ScopedProbeState> activeAlerts = adminApi.getActiveAlerts();
        if (activeAlerts.isEmpty()) {
            return Map.of();
        }
        Map<ProbeScope, AlertLevel> levels = new HashMap<>();
        for (ScopedProbeState active : activeAlerts) {
            active.getProbeState().getAlert().ifPresent(alert ->
                    levels.merge(active.getScope(), alert.getLevel(), (a, b) -> a.compareTo(b) >= 0 ? a : b));
        }
        return levels;
    }

    private static void addIfStillManaged(List<SessionStatus> sessions, AdminApi adminApi, Function<FixSessionId, String> probesVersions,
                                          Map<ProbeScope, AlertLevel> alertLevels, FixSession session, String name) {
        try {
            sessions.add(status(adminApi, probesVersions, alertLevels, session, name));
        } catch (IllegalArgumentException removedSinceListed) {
            // the seqnum and probe lookups refuse a session unregistered after getManagedFixSessions(); it is gone, skip it
        }
    }

    private static SessionStatus status(AdminApi adminApi, Function<FixSessionId, String> probesVersions, Map<ProbeScope, AlertLevel> alertLevels,
                                        FixSession session, String name) {
        FixSessionId fixSessionId = session.getFixSessionId();
        return SessionStatus.builder()
                .group(fixSessionId.getGroup())
                .name(name)
                .selectedConfig(fixSessionId.getName())
                .desiredState(session.getDesiredState())
                .status(session.getStatus())
                .incomingSeqNum(adminApi.getIncomingSeqNum(fixSessionId))
                .outgoingSeqNum(adminApi.getOutgoingSeqNum(fixSessionId))
                .probesVersion(probesVersions.apply(fixSessionId))
                .alertLevel(alertLevels.get(FixSessionProbeScope.of(fixSessionId)))
                .build();
    }
}
