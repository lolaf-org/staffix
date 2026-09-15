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
package org.lolaf.staffix.api.logging;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.time.UTCTime;

import java.nio.ByteBuffer;

/**
 * The logger a session gets when it is configured with none.
 *
 * <p>A singleton doing nothing, rather than a null: the session layer calls the logger on every message in both
 * directions, and an empty method the JIT can inline costs less than a branch that is never taken.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class VoidMessageLogger extends Startable.VoidStartable<FixMessagesLogger.Logger> implements FixMessagesLogger.Logger {

    private static final VoidMessageLogger INSTANCE = new VoidMessageLogger();

    public static VoidMessageLogger getInstance() {
        return INSTANCE;
    }

    @Override
    public boolean isLoggingEvents() {
        return false;
    }

    @Override
    public boolean isLoggingOutgoing() {
        return false;
    }

    @Override
    public boolean isLoggingIncoming() {
        return false;
    }

    @Override
    public void logIncoming(UTCTime time, MessageType messageType, ByteBuffer message) {
        // nothing to do
    }

    @Override
    public void logOutgoing(UTCTime time, MessageType messageType, ByteBuffer message) {
        // nothing to do
    }

    @Override
    public void logEvent(UTCTime time, String event) {
        // nothing to do
    }
}