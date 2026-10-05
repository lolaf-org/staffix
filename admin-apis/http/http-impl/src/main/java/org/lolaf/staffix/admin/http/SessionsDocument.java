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

import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.admin.FixAcceptorSessions;
import org.lolaf.staffix.api.admin.FixInitiatorTargets;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link EngineSessions} encoded once, with the sessions and settings it was built from, so a status call can tell
 * it is still current without building it again.
 */
final class SessionsDocument {

    final String version;
    final byte[] json;
    private final Map<FixSessionId, FixSessionSettings> builtFrom;

    private SessionsDocument(String version, byte[] json, Map<FixSessionId, FixSessionSettings> builtFrom) {
        this.version = version;
        this.json = json;
        this.builtFrom = builtFrom;
    }

    static Map<FixSessionId, FixSession> running(AdminApi adminApi) {
        Map<FixSessionId, FixSession> running = new HashMap<>();
        adminApi.getManagedFixSessions().forEach(session -> running.put(session.getFixSessionId(), session));
        return running;
    }

    static SessionsDocument of(AdminApi adminApi, Map<FixSessionId, FixSession> running) throws IOException {
        List<SessionDescription> sessions = new ArrayList<>();
        for (FixInitiatorTargets initiator : adminApi.getInitiatorsTargets()) {
            FixSession session = running.get(initiator.getActiveFixSessionId());
            if (session != null) {
                FixSessionId main = initiator.getMainTarget().getFixSessionId();
                SessionDescription.SessionDescriptionBuilder description = description(session, initiator.getInstanceId())
                        .name(main.getName())
                        .sessionSettingsStore(adminApi.findFixSessionsSettingsStore(main, FixSessionType.INITIATOR).orElse(null));
                initiator.getTargets().forEach(target -> description.config(config(target.getFixSessionId(),
                        target.getConnectAddresses().stream().map(SessionsDocument::hostAndPort).collect(Collectors.toList()),
                        session.getApplication())));
                sessions.add(description.build());
            }
        }
        for (FixAcceptorSessions acceptor : adminApi.getAcceptorsSessions()) {
            for (FixSessionId fixSessionId : acceptor.getFixSessionIds()) {
                FixSession session = running.get(fixSessionId);
                if (session != null) {
                    sessions.add(description(session, acceptor.getInstanceId())
                            .name(fixSessionId.getName())
                            .sessionSettingsStore(adminApi.findFixSessionsSettingsStore(fixSessionId, FixSessionType.ACCEPTOR).orElse(null))
                            .config(config(fixSessionId, List.of(), session.getApplication()))
                            .build());
                }
            }
        }
        String version = Sha256.hex(AdminApiHandler.MAPPER.writeValueAsBytes(sessions));
        byte[] json = AdminApiHandler.MAPPER.writeValueAsBytes(new EngineSessions(adminApi.getInstanceId(), version, sessions));
        Map<FixSessionId, FixSessionSettings> builtFrom = new HashMap<>();
        running.values().forEach(session -> builtFrom.put(session.getFixSessionId(), session.getFixSessionSettings()));
        return new SessionsDocument(version, json, builtFrom);
    }

    private static SessionDescription.SessionDescriptionBuilder description(FixSession session, String instanceId) {
        FixSessionSettings settings = session.getFixSessionSettings();
        return SessionDescription.builder()
                .group(session.getFixSessionId().getGroup())
                .type(settings.getFixSessionType())
                .instanceId(instanceId)
                .messagesLoggerInstanceId(settings.getFixMessageLoggerInstanceId())
                .monitoringInstanceId(settings.getFixSessionPluginsInstanceIds().get(FixSessionsMonitoringManager.class));
    }

    /**
     * A backup runs on its main config's application, so its application dictionary is theirs while a FIXT
     * transport is its own.
     */
    private static ConfigDescription config(FixSessionId fixSessionId, List<String> connectAddresses, FixApplication application) {
        return new ConfigDescription(fixSessionId.getName(), connectAddresses, FixIdentity.of(fixSessionId),
                Dictionaries.of(fixSessionId, application.getDictionaryId()));
    }

    private static String hostAndPort(InetSocketAddress address) {
        String host = address.getHostString();
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + address.getPort();
    }

    /**
     * Settings are compared by reference: a session restarted on new settings, even under the same id, holds
     * another instance.
     */
    boolean describes(Map<FixSessionId, FixSession> running) {
        if (running.size() != builtFrom.size()) {
            return false;
        }
        for (FixSession session : running.values()) {
            if (builtFrom.get(session.getFixSessionId()) != session.getFixSessionSettings()) {
                return false;
            }
        }
        return true;
    }
}
