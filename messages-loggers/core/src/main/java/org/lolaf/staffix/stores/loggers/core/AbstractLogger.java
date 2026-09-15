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
package org.lolaf.staffix.stores.loggers.core;

import lombok.Getter;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.session.FixSessionId;

/**
 * The per-session, per-message-type logger a {@link MessagesCoreLogger} hands out.
 */
public abstract class AbstractLogger extends Startable.SimpleStartable<FixMessagesLogger.Logger> implements FixMessagesLogger.Logger {

    private final boolean logIncoming;
    private final boolean logOutgoing;
    private final boolean logEvents;
    @Getter
    private final FixSessionId fixSessionId;

    protected AbstractLogger(FixMessagesLoggerSettings settings, FixSessionId fixSessionId) {
        this.fixSessionId = fixSessionId;
        this.logIncoming = settings.isLogIncoming();
        this.logOutgoing = settings.isLogOutgoing();
        this.logEvents = settings.isLogEvents();
    }

    protected AbstractLogger(boolean logIncoming, boolean logOutgoing, boolean logEvents, FixSessionId fixSessionId) {
        this.logIncoming = logIncoming;
        this.logOutgoing = logOutgoing;
        this.logEvents = logEvents;
        this.fixSessionId = fixSessionId;
    }

    @Override
    public boolean isLoggingOutgoing() {
        return logOutgoing;
    }

    @Override
    public boolean isLoggingEvents() {
        return logEvents;
    }

    @Override
    public boolean isLoggingIncoming() {
        return logIncoming;
    }
}