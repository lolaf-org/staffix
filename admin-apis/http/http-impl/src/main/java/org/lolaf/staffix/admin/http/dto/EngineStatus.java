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
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    List<SessionStatus> sessions;

    public static EngineStatus of(AdminApi adminApi, Map<FixSessionId, FixSession> running, String sessionsVersion) {
        List<SessionStatus> sessions = new ArrayList<>(running.size());
        for (FixInitiatorTargets initiator : adminApi.getInitiatorsTargets()) {
            FixSession session = running.get(initiator.getActiveFixSessionId());
            if (session != null) {
                String name = initiator.getMainTarget().getFixSessionId().getName();
                addIfStillManaged(sessions, adminApi, session, name);
            }
        }
        for (FixAcceptorSessions acceptor : adminApi.getAcceptorsSessions()) {
            for (FixSessionId fixSessionId : acceptor.getFixSessionIds()) {
                FixSession session = running.get(fixSessionId);
                if (session != null) {
                    addIfStillManaged(sessions, adminApi, session, fixSessionId.getName());
                }
            }
        }
        return new EngineStatus(adminApi.getInstanceId(), sessionsVersion, sessions);
    }

    private static void addIfStillManaged(List<SessionStatus> sessions, AdminApi adminApi, FixSession session, String name) {
        try {
            sessions.add(status(adminApi, session, name));
        } catch (IllegalArgumentException removedSinceListed) {
            // the seqnum lookups refuse a session unregistered after getManagedFixSessions(); it is gone, skip it
        }
    }

    private static SessionStatus status(AdminApi adminApi, FixSession session, String name) {
        FixSessionId fixSessionId = session.getFixSessionId();
        return SessionStatus.builder()
                .group(fixSessionId.getGroup())
                .name(name)
                .selectedConfig(fixSessionId.getName())
                .loggedIn(session.isLoggedIn())
                .connected(session.isConnected())
                .withinSessionTime(session.isWithinSessionTime())
                .desiredState(session.getDesiredState())
                .status(session.getStatus())
                .incomingSeqNum(adminApi.getIncomingSeqNum(fixSessionId))
                .outgoingSeqNum(adminApi.getOutgoingSeqNum(fixSessionId))
                .build();
    }
}
