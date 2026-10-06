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

import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.logging.LogObfuscator;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MessagesCoreLoggerSessionLoggersTest {

    private static final FixSessionId SESSION = FixSessionId.of("trading", FixRegularVersion.VERSION_44, "SENDER", "TARGET");

    private final List<FixMessagesLogger.Logger> instantiated = new ArrayList<>();
    private final MessagesCoreLogger logger = new MessagesCoreLogger(new FixMessagesLoggerSettings() {
        @Override
        public String getInstanceId() {
            return "test";
        }

        @Override
        public List<LogObfuscator> getLogObfuscators() {
            return List.of();
        }

        @Override
        public BiPredicate<MessageType, FixMessagesLogger.LogEventType> getMessageFilter() {
            return null;
        }
    }) {
        @Override
        public Logger instanciateLogger(String fixEngineId, String fixInstanceId, FixSessionId fixSessionId, MessageTypeRegistry messageTypeRegistry) {
            Logger sessionLogger = mock(Logger.class);
            instantiated.add(sessionLogger);
            return sessionLogger;
        }

        @Override
        protected void startMe() {
            // nothing to do
        }

        @Override
        protected void stopMe(Deadline stopDeadline) {
            // nothing to do
        }
    };

    @Test
    void handsARunningSessionItsLogger() {
        FixMessagesLogger.Logger first = sessionLogger();
        first.start();

        assertThat(sessionLogger()).isSameAs(first);
        assertThat(instantiated).hasSize(1);
    }

    /**
     * As when an initiator switches to a backup and back: its session stops, then starts again under the same id.
     */
    @Test
    void handsASessionStartedAgainANewLogger() {
        FixMessagesLogger.Logger first = sessionLogger();
        first.start();
        first.stop(Deadline.of(Duration.ofSeconds(1)));

        FixMessagesLogger.Logger second = sessionLogger();

        assertThat(second).isNotSameAs(first);
        assertThat(instantiated).hasSize(2);
        verify(instantiated.get(0)).stop(any());
    }

    private FixMessagesLogger.Logger sessionLogger() {
        return logger.getLogger("engine", "initiator", SESSION, mock(MessageTypeRegistry.class));
    }
}
