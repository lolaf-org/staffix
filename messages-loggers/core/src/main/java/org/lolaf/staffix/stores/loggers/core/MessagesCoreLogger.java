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

import lombok.AccessLevel;
import lombok.Getter;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.logging.LogObfuscator;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * The base every message logger extends: obfuscation and filtering applied before a subclass writes anything.
 */
@Getter(value = AccessLevel.PACKAGE)
public abstract class MessagesCoreLogger extends Startable.SimpleStartable<FixMessagesLogger> implements FixMessagesLogger {

    @Getter
    private final String instanceId;
    private final List<LogObfuscator> obfuscators;
    private final BiPredicate<MessageType, LogEventType> messageTypeFilter;
    private final Map<FixSessionId, Logger> loggers;

    protected MessagesCoreLogger(FixMessagesLoggerSettings settings) {
        this.instanceId = settings.getInstanceId();
        this.obfuscators = settings.getLogObfuscators();
        this.messageTypeFilter = settings.getMessageFilter() != null ? settings.getMessageFilter() : (mt, direction) -> false;
        this.loggers = new ConcurrentHashMap<>();
    }

    public static byte[] toByteArray(ByteBuffer message) {
        byte[] messageContent = new byte[message.remaining()];
        message.get(messageContent);
        return messageContent;
    }

    @Override
    public Logger getLogger(String fixInstanceId, FixSessionId fixSessionId, MessageTypeRegistry messageTypeRegistry) {
        return loggers.computeIfAbsent(fixSessionId, sid -> new LoggerWrapperImpl(
                fixSessionId, instanciateLogger(fixInstanceId, fixSessionId, messageTypeRegistry), messageTypeFilter, obfuscators));
    }

    public abstract Logger instanciateLogger(String fixInstanceId, FixSessionId fixSessionId, MessageTypeRegistry messageTypeRegistry);

    static class LoggerWrapperImpl extends AbstractLogger implements Logger {

        private final Logger wrappedLogger;
        private final BiPredicate<MessageType, LogEventType> messageTypeFilter;
        private final LogObfuscator[] obfuscators;

        public LoggerWrapperImpl(FixSessionId fixSessionId, Logger wrappedLogger, BiPredicate<MessageType, LogEventType> messageTypeFilter, List<LogObfuscator> obfuscators) {
            super(wrappedLogger.isLoggingIncoming(), wrappedLogger.isLoggingOutgoing(), wrappedLogger.isLoggingEvents(), fixSessionId);
            this.wrappedLogger = wrappedLogger;
            this.messageTypeFilter = messageTypeFilter;
            this.obfuscators = obfuscators.isEmpty() ? null : obfuscators.toArray(obfuscators.toArray(new LogObfuscator[0]));
        }

        @Override
        public void logIncoming(UTCTime logTime, MessageType messageType, ByteBuffer message) throws LoggingException {
            if (messageTypeFilter.test(messageType, LogEventType.INCOMING_MSG)) {
                return;
            }
            wrappedLogger.logIncoming(logTime, messageType, obfuscateLogIfNeeded(message, messageType));
        }

        @Override
        public void logOutgoing(UTCTime logTime, MessageType messageType, ByteBuffer message) throws LoggingException {
            if (messageTypeFilter.test(messageType, LogEventType.OUTGOING_MSG)) {
                return;
            }
            wrappedLogger.logOutgoing(logTime, messageType, obfuscateLogIfNeeded(message, messageType));
        }

        @Override
        public void logEvent(UTCTime eventTime, String event) throws LoggingException {
            wrappedLogger.logEvent(eventTime, event);
        }

        @Override
        public void logEvent(UTCTime eventTime, String event, Object... params) throws LoggingException {
            wrappedLogger.logEvent(eventTime, event, params);
        }

        @Override
        protected void stopMe(Deadline stopDeadline) throws StartStopException {
            wrappedLogger.stop(stopDeadline);
        }

        @Override
        protected void startMe() throws StartStopException {
            wrappedLogger.start();
        }

        @Override
        public boolean isStarted() {
            return wrappedLogger.isStarted();
        }

        private ByteBuffer obfuscateLogIfNeeded(ByteBuffer message, MessageType messageType) {
            if (obfuscators == null) {
                return message;
            }
            for (LogObfuscator logObfuscator : obfuscators) {
                if (logObfuscator.isForMessageType(messageType)) {
                    int position = message.position();
                    message = logObfuscator.obfuscate(message);
                    message.position(position);
                }
            }
            return message;
        }
    }
}