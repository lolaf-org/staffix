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
package org.lolaf.staffix.codec.decoders;

import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.logging.LogObfuscator;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.stores.loggers.core.MessagesCoreLogger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

public class TestingLogger extends MessagesCoreLogger {

    private final List<String> loggedMessages;
    private final List<UTCTime> loggedMessagesTimeStamps;
    private final List<String> loggedEvents;

    public TestingLogger() {
        this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    public TestingLogger(List<String> loggedMessages, List<String> loggedEvents, List<UTCTime> loggedMessagesTimeStamps) {
        super(new FixMessagesLoggerSettings() {
            @Override
            public String getInstanceId() {
                return DEFAULT_INSTANCE_ID;
            }

            @Override
            public List<LogObfuscator> getLogObfuscators() {
                return List.of();
            }

            @Override
            public BiPredicate<MessageType, LogEventType> getMessageFilter() {
                return null;
            }
        });
        this.loggedMessagesTimeStamps = loggedMessagesTimeStamps;
        this.loggedMessages = loggedMessages;
        this.loggedEvents = loggedEvents;
    }

    @Override
    protected void startMe() throws StartStopException {
        // nothing to do
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        // nothing to do
    }

    @Override
    public FixMessagesLogger.Logger instanciateLogger(String fixInstanceId, FixSessionId fixSessionId, MessageTypeRegistry messageTypeRegistry) {
        return new FixMessagesLogger.Logger() {
            @Override
            public void logIncoming(UTCTime time, MessageType messageType, ByteBuffer message) {
                loggedMessages.add(new String(toByteArray(message)));
                loggedMessagesTimeStamps.add(time.asImmutable());
            }

            @Override
            public void logOutgoing(UTCTime time, MessageType messageType, ByteBuffer message) {
                loggedMessages.add(new String(toByteArray(message)));
                loggedMessagesTimeStamps.add(time.asImmutable());
            }

            @Override
            public void logEvent(UTCTime time, String event) {
                loggedEvents.add(event);
            }

            @Override
            public FixMessagesLogger.Logger stop(Deadline stopDeadline) throws StartStopException {
                return this;
            }

            @Override
            public FixMessagesLogger.Logger start() throws StartStopException {
                return this;
            }

            @Override
            public boolean isLoggingIncoming() {
                return true;
            }

            @Override
            public boolean isLoggingOutgoing() {
                return true;
            }

            @Override
            public boolean isLoggingEvents() {
                return true;
            }

            @Override
            public boolean isStarted() {
                return true;
            }
        };
    }
}