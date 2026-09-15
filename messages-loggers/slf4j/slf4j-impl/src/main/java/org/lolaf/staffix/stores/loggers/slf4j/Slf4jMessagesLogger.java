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
package org.lolaf.staffix.stores.loggers.slf4j;

import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.stores.loggers.core.AbstractLogger;
import org.lolaf.staffix.stores.loggers.core.MessagesCoreLogger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Writes every message through SLF4J, so it lands wherever the application's logging is already configured.
 */
public class Slf4jMessagesLogger extends MessagesCoreLogger {

    private final Slf4jMessagesLoggerSettings settings;

    protected Slf4jMessagesLogger(Slf4jMessagesLoggerSettings settings) {
        super(settings);
        this.settings = settings;
    }

    @Override
    public Logger instanciateLogger(String fixInstanceId, FixSessionId fixSessionId, MessageTypeRegistry messageTypeRegistry) {
        return new LoggerImpl(settings, fixSessionId);
    }

    @Override
    protected void startMe() throws StartStopException {
        // nothing to do
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        // nothing to do
    }

    private static final class LoggerImpl extends AbstractLogger {

        private static final String FIX_SESSION_ID = "fixSessionId";
        private final String logEventTemplate;
        private final String logOutTemplate;
        private final String logInTemplate;
        private final org.slf4j.Logger logger;
        private final Consumer<FixSessionId> mdcAdd;
        private final Consumer<FixSessionId> mdcRemove;
        private final byte messageFieldsDelimiter;

        private LoggerImpl(Slf4jMessagesLoggerSettings settings, FixSessionId fixSessionId) {
            super(settings, fixSessionId);
            this.logger = LoggerFactory.getLogger(settings.getLoggerNameForFixSession().apply(fixSessionId));
            this.mdcAdd = settings.isUseMDC() ? sid -> MDC.put(FIX_SESSION_ID, fixSessionId.getId()) : this::doNotMdc;
            this.mdcRemove = settings.isUseMDC() ? sid -> MDC.remove(FIX_SESSION_ID) : this::doNotMdc;
            this.logEventTemplate = settings.getLogEventTemplate();
            this.logInTemplate = settings.getLogInTemplate();
            this.logOutTemplate = settings.getLogOutTemplate();
            this.messageFieldsDelimiter = settings.getMessageFieldsDelimiter() != null ? (byte) settings.getMessageFieldsDelimiter().charValue() : 0;
        }

        @Override
        protected void stopMe(Deadline stopDeadline) throws StartStopException {
            // nothing to do
        }

        @Override
        protected void startMe() throws StartStopException {
            // nothing to do
        }

        private void doNotMdc(FixSessionId sid) {
            // nothing to do
        }

        @Override
        public void logIncoming(UTCTime logTime, MessageType messageType, ByteBuffer message) {
            logMessage(message, logInTemplate);
        }

        @Override
        public void logOutgoing(UTCTime logTime, MessageType messageType, ByteBuffer message) {
            logMessage(message, logOutTemplate);
        }

        private void logMessage(ByteBuffer message, String logTemplate) {
            mdcAdd.accept(getFixSessionId());
            byte[] bytes = toByteArray(message);
            if (messageFieldsDelimiter > 0) {
                for (int i = 0; i < bytes.length; i++) {
                    if (bytes[i] == CoreFields.FIELD_SEPARATOR_BYTE) {
                        bytes[i] = messageFieldsDelimiter;
                    }
                }
            }
            logger.info(logTemplate, new String(bytes, SerDe.CHARSET));
            mdcRemove.accept(getFixSessionId());
        }

        @Override
        public void logEvent(UTCTime eventTime, String event) {
            mdcAdd.accept(getFixSessionId());
            logger.info(logEventTemplate, event);
            mdcRemove.accept(getFixSessionId());
        }
    }

    public static class Slf4jMessagesLoggerFactoryImpl implements FixMessagesLoggerSettings.FixMessagesLoggerFactory<Slf4jMessagesLoggerSettings> {

        @Override
        public FixMessagesLogger newInstance(Slf4jMessagesLoggerSettings settings) {
            return new Slf4jMessagesLogger(settings);
        }

        @Override
        public Class<Slf4jMessagesLoggerSettings> getSettingsClass() {
            return Slf4jMessagesLoggerSettings.class;
        }
    }
}