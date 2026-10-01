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

import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionState;

import java.util.List;

/**
 * One session box of the console: an acceptor session, or an initiator with its configs (main first, then
 * backups), of which only the selected one is running.
 */
@Value
@Builder
public class SessionStatus {
    String group;
    /**
     * The main config's name, unique within the group.
     */
    String name;
    FixSessionType type;
    /**
     * The initiator or acceptor serving the session.
     */
    String instanceId;
    @Singular
    List<String> configs;
    String selectedConfig;
    /**
     * The selected config's FIX session id.
     */
    FixIdentity identity;
    boolean loggedIn;
    boolean connected;
    boolean withinSessionTime;
    /**
     * LOGGED_OUT when an operator logged the session out, so the console does not report it as an incident.
     */
    FixSessionState desiredState;
    long incomingSeqNum;
    long outgoingSeqNum;
}
