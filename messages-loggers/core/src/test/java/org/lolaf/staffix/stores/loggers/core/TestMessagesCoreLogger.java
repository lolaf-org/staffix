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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.logging.LogObfuscator;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.mockito.Mockito;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.BiPredicate;

class TestMessagesCoreLogger {

    FixMessagesLogger.Logger testLogger;
    FixMessagesLogger.Logger coreLogger;
    UTCTime time;
    MessageType messageType;
    BiPredicate<MessageType, FixMessagesLogger.LogEventType> testFilter;

    @BeforeEach
    void setup() {
        messageType = MessageType.of("A", true);
        time = new UTCTime() {
            @Override
            public long getEpochSeconds() {
                return 1234;
            }

            @Override
            public int getNanosOfSecond() {
                return 12345;
            }

            @Override
            public boolean isImmutable() {
                return true;
            }

            @Override
            public UTCTime asImmutable() {
                return this;
            }
        };
        testLogger = Mockito.mock(FixMessagesLogger.Logger.class);
        testFilter = Mockito.mock(BiPredicate.class);
        Mockito.when(testFilter.test(messageType, FixMessagesLogger.LogEventType.INCOMING_MSG)).thenReturn(false);
        Mockito.when(testFilter.test(messageType, FixMessagesLogger.LogEventType.OUTGOING_MSG)).thenReturn(false);
        FixMessagesLoggerSettings settings = new FixMessagesLoggerSettings() {

            @Override
            public String getInstanceId() {
                return "test";
            }

            @Override
            public List<LogObfuscator> getLogObfuscators() {
                return List.of(PasswordObfuscator.getInstance(), UsernameObfuscator.getInstance());
            }

            @Override
            public BiPredicate<MessageType, FixMessagesLogger.LogEventType> getMessageFilter() {
                return testFilter;
            }
        };
        MessagesCoreLogger logger = new MessagesCoreLogger(settings) {
            @Override
            public Logger instanciateLogger(String fixInstanceId, FixSessionId fixSessionId, MessageTypeRegistry messageTypeRegistry) {
                return testLogger;
            }

            @Override
            protected void startMe() throws StartStopException {
                // nothing to do
            }

            @Override
            protected void stopMe(Deadline stopDeadline) throws StartStopException {
                // nothing to do
            }
        };

        FixSessionId sessionId = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
        coreLogger = logger.getLogger("test", sessionId, Mockito.mock(MessageTypeRegistry.class));
    }

    @Test
    void testObfuscation() throws FixMessagesLogger.LoggingException {
        String message = "8=FIX.4.49=8335=A34=198=0108=30141=Y554=PASSWORD553=USERNAME10=249";
        String messageExpected = "8=FIX.4.49=8335=A34=198=0108=30141=Y554=********553=********10=249";

        coreLogger.logIncoming(time, messageType, ByteBuffer.wrap(message.getBytes()));

        Mockito.verify(testLogger).logIncoming(time, messageType, ByteBuffer.wrap(messageExpected.getBytes()));

        coreLogger.logOutgoing(time, messageType, ByteBuffer.wrap(message.getBytes()));

        Mockito.verify(testLogger).logOutgoing(time, messageType, ByteBuffer.wrap(messageExpected.getBytes()));
    }

    @Test
    void testFiltering() throws FixMessagesLogger.LoggingException {
        String message = "8=FIX.4.49=8335=A34=198=0108=30141=Y554=PASSWORD553=USERNAME10=249";

        MessageType rejected = MessageType.of("A", true);
        Mockito.when(testFilter.test(rejected, FixMessagesLogger.LogEventType.INCOMING_MSG)).thenReturn(true);
        Mockito.when(testFilter.test(rejected, FixMessagesLogger.LogEventType.OUTGOING_MSG)).thenReturn(true);

        coreLogger.logIncoming(time, rejected, ByteBuffer.wrap(message.getBytes()));

        Mockito.verify(testLogger, Mockito.never()).logIncoming(Mockito.any(), Mockito.any(), Mockito.any());

        coreLogger.logOutgoing(time, rejected, ByteBuffer.wrap(message.getBytes()));

        Mockito.verify(testLogger, Mockito.never()).logOutgoing(Mockito.any(), Mockito.any(), Mockito.any());

        MessageType nonRejected = MessageType.of("B", true);

        coreLogger.logIncoming(time, nonRejected, ByteBuffer.wrap(message.getBytes()));
        Mockito.verify(testLogger).logIncoming(Mockito.any(), Mockito.any(), Mockito.any());

        coreLogger.logOutgoing(time, nonRejected, ByteBuffer.wrap(message.getBytes()));
        Mockito.verify(testLogger).logOutgoing(Mockito.any(), Mockito.any(), Mockito.any());
    }

}