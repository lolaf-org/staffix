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
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;

import java.util.List;

/**
 * What a session box of the console shows that changes only with the engine's sessions or their settings: an
 * acceptor session, or an initiator with its configs (main first, then backups).
 */
@Value
@Builder
public class SessionDescription {
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
    List<ConfigDescription> configs;
    /**
     * The session's messages logger instance, the same for every config: a backup runs on its main one's settings.
     */
    String messagesLoggerInstanceId;
    /**
     * The session's {@link FixSessionsMonitoringManager} plugin instance, null when the session is not monitored;
     * the same for every config, like {@link #messagesLoggerInstanceId}.
     */
    String monitoringInstanceId;
    /**
     * The settings store holding the session's settings, null when none does.
     */
    String settingsStore;
}
