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
package org.lolaf.staffix.stores.loggers.demux;

import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Fans every message out to several loggers, so a session can log to a file and to a collector without either
 * knowing about the other.
 */
public class DemuxMessagesLogger extends Startable.SimpleStartable<FixMessagesLogger> implements FixMessagesLogger {

    private final List<FixMessagesLogger> loggers;
    private final String instanceId;

    protected DemuxMessagesLogger(DemuxMessagesLoggerSettings settings) {
        this.instanceId = settings.getInstanceId();
        this.loggers = settings.getDemuxedLoggers();
    }

    @Override
    public String getInstanceId() {
        return instanceId;
    }

    @Override
    protected void startMe() throws StartStopException {
        loggers.forEach(Startable::start);
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        loggers.forEach(l -> l.stop(stopDeadline));
    }

    @Override
    public Logger getLogger(String fixInstanceId, FixSessionId fixSessionId, MessageTypeRegistry messageTypeRegistry) {
        List<Logger> wrappedLoggers = loggers.stream().map(l -> l.getLogger(fixInstanceId, fixSessionId, messageTypeRegistry)).collect(Collectors.toList());
        if (wrappedLoggers.stream().allMatch(BatchingLogger.class::isInstance)) {
            return new BatchingLoggerImpl(wrappedLoggers);
        }
        return new LoggerImpl(wrappedLoggers);
    }

    private static class BatchingLoggerImpl extends LoggerImpl implements BatchingLogger {

        private final BatchingLogger[] batchingLoggers;

        public BatchingLoggerImpl(List<Logger> wrappedLoggers) {
            super(wrappedLoggers);
            this.batchingLoggers = wrappedLoggers.stream().filter(BatchingLogger.class::isInstance)
                    .map(l -> (BatchingLogger) l).toArray(BatchingLogger[]::new);
        }


        @Override
        public void logEvents(LogEvent[] logEvents, int eventsCount) throws LoggingException {
            for (BatchingLogger l : batchingLoggers) {
                l.logEvents(logEvents, eventsCount);
            }
        }
    }

    private static class LoggerImpl extends SimpleStartable<Logger> implements Logger {
        private final Logger[] loggers;
        private final Logger[] incomingLoggers;
        private final Logger[] outgoingLoggers;
        private final Logger[] eventsLoggers;
        private final boolean loggingOutgoing;
        private final boolean loggingIncoming;
        private final boolean loggingEvents;

        public LoggerImpl(List<Logger> wrappedLoggers) {
            this.loggers = wrappedLoggers.toArray(Logger[]::new);
            incomingLoggers = wrappedLoggers.stream().filter(Logger::isLoggingIncoming).toArray(Logger[]::new);
            loggingIncoming = incomingLoggers.length > 0;
            outgoingLoggers = wrappedLoggers.stream().filter(Logger::isLoggingOutgoing).toArray(Logger[]::new);
            loggingOutgoing = outgoingLoggers.length > 0;
            eventsLoggers = wrappedLoggers.stream().filter(Logger::isLoggingEvents).toArray(Logger[]::new);
            loggingEvents = eventsLoggers.length > 0;
        }

        @Override
        public boolean isUnderlyingStorageResourceAvailable() {
            return Arrays.stream(loggers).allMatch(Logger::isUnderlyingStorageResourceAvailable);
        }

        @Override
        public String getUnderlyingStorageResourceDescription() {
            return Arrays.stream(loggers).map(Logger::getUnderlyingStorageResourceDescription)
                    .collect(Collectors.joining(", "));
        }

        @Override
        public void logIncoming(UTCTime logTime, MessageType messageType, ByteBuffer message) throws LoggingException {
            LoggingException ex = null;
            for (Logger l : incomingLoggers) {
                try {
                    l.logIncoming(logTime, messageType, message);
                } catch (LoggingException e) {
                    ex = e;
                }
            }
            if (ex != null) {
                throw ex;
            }
        }

        @Override
        public void logOutgoing(UTCTime logTime, MessageType messageType, ByteBuffer message) throws LoggingException {
            LoggingException ex = null;
            for (Logger l : outgoingLoggers) {
                try {
                    l.logOutgoing(logTime, messageType, message);
                } catch (LoggingException e) {
                    ex = e;
                }
            }
            if (ex != null) {
                throw ex;
            }
        }

        @Override
        public void logEvent(UTCTime eventTime, String event) throws LoggingException {
            LoggingException ex = null;
            for (Logger l : eventsLoggers) {
                try {
                    l.logEvent(eventTime, event);
                } catch (LoggingException e) {
                    ex = e;
                }
            }
            if (ex != null) {
                throw ex;
            }
        }

        @Override
        public void logEvent(UTCTime eventTime, String event, Object... params) throws LoggingException {
            LoggingException ex = null;
            for (Logger l : eventsLoggers) {
                try {
                    l.logEvent(eventTime, event, params);
                } catch (LoggingException e) {
                    ex = e;
                }
            }
            if (ex != null) {
                throw ex;
            }
        }

        @Override
        public boolean isLoggingOutgoing() {
            return loggingOutgoing;
        }

        @Override
        public boolean isLoggingIncoming() {
            return loggingIncoming;
        }

        @Override
        public boolean isLoggingEvents() {
            return loggingEvents;
        }


        @Override
        protected void startMe() throws StartStopException {
            for (Logger l : loggers) {
                l.start();
            }
        }

        @Override
        protected void stopMe(Deadline stopDeadline) throws StartStopException {
            for (Logger l : loggers) {
                l.stop(stopDeadline);
            }
        }
    }

    public static class DemuxMessagesLoggerFactoryImpl implements FixMessagesLoggerSettings.FixMessagesLoggerFactory<DemuxMessagesLoggerSettings> {

        @Override
        public FixMessagesLogger newInstance(DemuxMessagesLoggerSettings settings) {
            return new DemuxMessagesLogger(settings);
        }

        @Override
        public Class<DemuxMessagesLoggerSettings> getSettingsClass() {
            return DemuxMessagesLoggerSettings.class;
        }
    }
}