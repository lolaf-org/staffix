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


import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.logging.LogObfuscator;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * A message logger that accumulates and writes in batches.
 *
 * <p>A write per message would put a syscall on the message path, so the trade is a bounded delay before a
 * message reaches the log against not paying for it inline.
 */
public abstract class MessagesCoreBatchingLogger extends MessagesCoreLogger {

    protected MessagesCoreBatchingLogger(FixMessagesLoggerSettings settings) {
        super(settings);
    }

    @Override
    public Logger getLogger(String fixInstanceId, FixSessionId fixSessionId, MessageTypeRegistry messageTypeRegistry) {
        return getLoggers().computeIfAbsent(fixSessionId, sid -> new BatchingLoggerWrapperImpl(
                fixSessionId, instanciateLogger(fixInstanceId, fixSessionId, messageTypeRegistry), getMessageTypeFilter(), getObfuscators()));
    }

    @Override
    public abstract BatchingLogger instanciateLogger(String fixInstanceId, FixSessionId fixSessionId, MessageTypeRegistry messageTypeRegistry);

    private static class BatchingLoggerWrapperImpl extends LoggerWrapperImpl implements BatchingLogger {

        private final BatchingLogger wrappedBatchingLogger;

        public BatchingLoggerWrapperImpl(FixSessionId fixSessionId, BatchingLogger wrappedLogger, BiPredicate<MessageType, LogEventType> messageTypeFilter, List<LogObfuscator> obfuscators) {
            super(fixSessionId, wrappedLogger, messageTypeFilter, obfuscators);
            this.wrappedBatchingLogger = wrappedLogger;
        }

        @Override
        public void logEvents(LogEvent[] logEvents, int eventsCount) throws LoggingException {
            wrappedBatchingLogger.logEvents(logEvents, eventsCount);
        }
    }
}