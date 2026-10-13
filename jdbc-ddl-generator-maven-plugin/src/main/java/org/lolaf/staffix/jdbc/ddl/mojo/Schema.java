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
package org.lolaf.staffix.jdbc.ddl.mojo;

import lombok.Getter;
import org.lolaf.staffix.jdbc.ddl.entity.messages.FixMessage;
import org.lolaf.staffix.jdbc.ddl.entity.messages.FixMessageSessionState;
import org.lolaf.staffix.jdbc.ddl.entity.sessions.FixSessionSettingsRow;

import java.util.List;

/**
 * The JDBC stores whose tables the plugin generates, each through its own entities.
 */
@Getter
public enum Schema {
    /**
     * The messages store: {@code fix_messages} and {@code fix_messages_session_state}.
     */
    MESSAGES(FixMessage.class, FixMessageSessionState.class),
    /**
     * The sessions settings store: {@code fix_sessions_settings}.
     */
    SESSIONS_SETTINGS(FixSessionSettingsRow.class);

    private final List<Class<?>> entities;

    Schema(Class<?>... entities) {
        this.entities = List.of(entities);
    }
}
