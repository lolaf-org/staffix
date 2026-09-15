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

import com.github.valfirst.slf4jtest.TestLogger;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.slf4j.event.Level;

import java.nio.ByteBuffer;
import java.time.Instant;

import static com.github.valfirst.slf4jtest.Assertions.assertThat;

@Slf4j
class TestSlf4jMessagesLogger {
    FixMessagesLogger.Logger logger;
    Slf4jMessagesLoggerSettings settings;
    FixSessionId fixSessionId;
    MessageType messageType;
    Slf4jMessagesLogger slf4jMessagesLogger;

    @BeforeEach
    void before() {
        TestLoggerFactory.clearAll();
        fixSessionId = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
        settings = Slf4jMessagesLoggerSettings.builder()
                .logInTemplate("IN:{}")
                .logOutTemplate("OUT:{}")
                .useMDC(false)
                .build();

        slf4jMessagesLogger = new Slf4jMessagesLogger(settings);
        logger = slf4jMessagesLogger.instanciateLogger("testInstanceId", fixSessionId, null);

        messageType = MessageType.of("A", false);
    }

    @Test
    void testIsLoggingMessages() throws FixMessagesLogger.LoggingException {

        logger.logIncoming(UTCTime.of(Instant.now()), messageType, ByteBuffer.wrap("testIn".getBytes()));
        logger.logOutgoing(UTCTime.of(Instant.now()), messageType, ByteBuffer.wrap("testOut".getBytes()));

        TestLogger testLogger = TestLoggerFactory.getTestLogger(settings.getLoggerNameForFixSession().apply(fixSessionId));

        assertThat(testLogger).hasLogged(Level.INFO, "IN:{}", "testIn");
        assertThat(testLogger).hasLogged(Level.INFO, "OUT:{}", "testOut");

    }

    @Test
    void testIsLoggingMessagesWithMdc() throws FixMessagesLogger.LoggingException {
        settings = settings.toBuilder()
                .useMDC(true)
                .build();

        slf4jMessagesLogger = new Slf4jMessagesLogger(settings);
        logger = slf4jMessagesLogger.instanciateLogger("testInstanceId", fixSessionId, null);

        logger.logIncoming(UTCTime.of(Instant.now()), messageType, ByteBuffer.wrap("testIn".getBytes()));

        TestLogger testLogger = TestLoggerFactory.getTestLogger(settings.getLoggerNameForFixSession().apply(fixSessionId));

        assertThat(testLogger).hasLogged(loggingEvent -> {
            Assertions.assertThat(loggingEvent.getMdc()).containsEntry("fixSessionId", fixSessionId.getId());
            Assertions.assertThat(loggingEvent.getFormattedMessage()).isEqualTo("IN:testIn");
            return true;
        });
        testLogger.clear();

        logger.logOutgoing(UTCTime.of(Instant.now()), messageType, ByteBuffer.wrap("testOut".getBytes()));

        assertThat(testLogger).hasLogged(loggingEvent -> {
            Assertions.assertThat(loggingEvent.getMdc()).containsEntry("fixSessionId", fixSessionId.getId());
            Assertions.assertThat(loggingEvent.getFormattedMessage()).isEqualTo("OUT:testOut");
            return true;
        });

    }
}
