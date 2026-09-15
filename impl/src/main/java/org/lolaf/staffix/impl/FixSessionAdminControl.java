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
package org.lolaf.staffix.impl;

import org.lolaf.staffix.api.admin.AdminApi.ResetFixSessionMode;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;

import java.util.List;

/**
 * Internal per-runtime (initiator/acceptor) session-control surface. The engine's
 * {@link org.lolaf.staffix.api.admin.AdminApi} routes operations to the implementation managing a
 * given session.
 */
interface FixSessionAdminControl {

    void logonSession(FixSessionId fixSessionId);

    void logoutSession(FixSessionId fixSessionId);

    void resetSession(FixSessionId fixSessionId, ResetFixSessionMode resetFixSessionMode);

    void setIncomingSeqNum(FixSessionId fixSessionId, long incomingSeqNum);

    void setOutgoingSeqNum(FixSessionId fixSessionId, long outgoingSeqNum);

    void sendFixMessage(FixSessionId fixSessionId, String fixMessage, char separator, boolean possDupFlag);

    long getIncomingSeqNum(FixSessionId fixSessionId);

    long getOutgoingSeqNum(FixSessionId fixSessionId);

    List<FixSessionSettings> getManagedFixSessionsSettings();

    List<FixSession> getManagedFixSessions();

    /**
     * @return {@code true} if this control runs initiator sessions, {@code false} if it runs acceptor sessions
     */
    boolean isInitiator();
}
