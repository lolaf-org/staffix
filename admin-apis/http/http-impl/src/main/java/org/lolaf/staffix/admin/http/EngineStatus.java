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
package org.lolaf.staffix.admin.http;

import lombok.Value;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.admin.FixAcceptorSessions;
import org.lolaf.staffix.api.admin.FixInitiatorTargets;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Every session of an engine with its state, in one response the console polls.
 */
@Value
public class EngineStatus {
    String instanceId;
    List<SessionStatus> sessions;

    static EngineStatus of(AdminApi adminApi) {
        Map<FixSessionId, FixSession> running = new HashMap<>();
        adminApi.getManagedFixSessions().forEach(session -> running.put(session.getFixSessionId(), session));
        List<SessionStatus> sessions = new ArrayList<>();
        for (FixInitiatorTargets initiator : adminApi.getInitiatorsTargets()) {
            FixSession session = running.get(initiator.getActiveFixSessionId());
            if (session != null) {
                addIfStillManaged(sessions, () -> {
                    SessionStatus.SessionStatusBuilder status = status(adminApi, session, initiator.getInstanceId())
                            .name(initiator.getTargets().get(0).getFixSessionId().getName());
                    initiator.getTargets().forEach(target -> status.config(new ConfigStatus(target.getFixSessionId().getName(),
                            target.getConnectAddresses().stream().map(EngineStatus::hostAndPort).collect(Collectors.toList()))));
                    return status.build();
                });
            }
        }
        for (FixAcceptorSessions acceptor : adminApi.getAcceptorsSessions()) {
            for (FixSessionId fixSessionId : acceptor.getFixSessionIds()) {
                FixSession session = running.get(fixSessionId);
                if (session != null) {
                    addIfStillManaged(sessions, () -> status(adminApi, session, acceptor.getInstanceId())
                            .name(fixSessionId.getName())
                            .config(new ConfigStatus(fixSessionId.getName(), List.of()))
                            .build());
                }
            }
        }
        return new EngineStatus(adminApi.getInstanceId(), sessions);
    }

    private static String hostAndPort(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }

    private static void addIfStillManaged(List<SessionStatus> sessions, Supplier<SessionStatus> status) {
        try {
            sessions.add(status.get());
        } catch (IllegalArgumentException removedSinceListed) {
            // the seqnum lookups refuse a session unregistered after getManagedFixSessions(); it is gone, skip it
        }
    }

    private static SessionStatus.SessionStatusBuilder status(AdminApi adminApi, FixSession session, String instanceId) {
        FixSessionId fixSessionId = session.getFixSessionId();
        FixSessionSettings settings = session.getFixSessionSettings();
        return SessionStatus.builder()
                .group(fixSessionId.getGroup())
                .type(settings.getFixSessionType())
                .instanceId(instanceId)
                .selectedConfig(fixSessionId.getName())
                .identity(FixIdentity.of(fixSessionId))
                .loggedIn(session.isLoggedIn())
                .connected(session.isConnected())
                .withinSessionTime(session.isWithinSessionTime())
                .desiredState(session.getDesiredState())
                .incomingSeqNum(adminApi.getIncomingSeqNum(fixSessionId))
                .outgoingSeqNum(adminApi.getOutgoingSeqNum(fixSessionId))
                .dictionaries(Dictionaries.of(fixSessionId, settings.getDictionaryId()))
                .messagesLoggerInstanceId(settings.getFixMessageLoggerInstanceId())
                .monitoringInstanceId(settings.getFixSessionPluginsInstanceIds().get(FixSessionsMonitoringManager.class));
    }
}
