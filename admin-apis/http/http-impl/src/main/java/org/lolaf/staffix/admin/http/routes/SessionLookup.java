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
package org.lolaf.staffix.admin.http.routes;

import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.admin.http.HttpProblemException;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.admin.FixAcceptorSessions;
import org.lolaf.staffix.api.admin.FixInitiatorTargets;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;

/**
 * Finds a session by its group and name. A session is named after its main config, as in {@code sessions}; an
 * initiator may be running a backup.
 */
@RequiredArgsConstructor
public final class SessionLookup {

    private final AdminApi adminApi;

    FixSessionId runningConfig(String group, String name) {
        FixInitiatorTargets initiator = initiator(group, name);
        if (initiator != null) {
            return initiator.getActiveFixSessionId();
        }
        FixSessionId acceptorSession = acceptorSession(group, name);
        if (acceptorSession == null) {
            throw noSession(group, name);
        }
        return acceptorSession;
    }

    FixSessionId initiatorConfig(String group, String name, String config) {
        FixInitiatorTargets initiator = initiator(group, name);
        if (initiator == null) {
            if (acceptorSession(group, name) == null) {
                throw noSession(group, name);
            }
            throw new HttpProblemException(409, "Session " + name + " of group " + group + " is an acceptor's, it has a single config");
        }
        return initiator.getTargets().stream()
                .map(FixInitiatorTarget::getFixSessionId)
                .filter(fixSessionId -> fixSessionId.getName().equals(config))
                .findFirst()
                .orElseThrow(() -> new HttpProblemException(404, "Session " + name + " of group " + group + " has no config " + config));
    }

    /**
     * The settings as stored: under the main config's id, which a running backup's settings are a copy of.
     */
    StoredSession storedSession(String group, String name) {
        FixInitiatorTargets initiator = initiator(group, name);
        FixSessionId stored = initiator != null ? initiator.getMainTarget().getFixSessionId() : acceptorSession(group, name);
        if (stored == null) {
            throw noSession(group, name);
        }
        FixSessionId running = initiator != null ? initiator.getActiveFixSessionId() : stored;
        FixSessionType type = initiator != null ? FixSessionType.INITIATOR : FixSessionType.ACCEPTOR;
        FixSessionSettings settings = adminApi.getManagedFixSessionsSettings().stream()
                .filter(candidate -> candidate.getFixSessionType() == type && candidate.getFixSessionId().equals(running))
                .findFirst()
                .orElseThrow(() -> new HttpProblemException(404, "Session " + name + " of group " + group + " has no settings"));
        return new StoredSession(stored, type, settings.toBuilder().fixSessionId(stored).build());
    }

    private FixInitiatorTargets initiator(String group, String name) {
        for (FixInitiatorTargets initiator : adminApi.getInitiatorsTargets()) {
            if (isNamed(initiator.getMainTarget().getFixSessionId(), group, name)) {
                return initiator;
            }
        }
        return null;
    }

    private FixSessionId acceptorSession(String group, String name) {
        for (FixAcceptorSessions acceptor : adminApi.getAcceptorsSessions()) {
            for (FixSessionId fixSessionId : acceptor.getFixSessionIds()) {
                if (isNamed(fixSessionId, group, name)) {
                    return fixSessionId;
                }
            }
        }
        return null;
    }

    private static boolean isNamed(FixSessionId fixSessionId, String group, String name) {
        return fixSessionId.getGroup().equals(group) && fixSessionId.getName().equals(name);
    }

    private static HttpProblemException noSession(String group, String name) {
        return new HttpProblemException(404, "No session " + name + " in group " + group);
    }

    @RequiredArgsConstructor
    static final class StoredSession {
        final FixSessionId fixSessionId;
        final FixSessionType type;
        final FixSessionSettings settings;
    }
}
