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
package org.lolaf.staffix.stores.loggers.otlp;

import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.msg.FixtMessageTypeRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.mockito.Mockito;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@Slf4j
class TestOtlpMessagesLogger {

    private static final List<String> receivedOutputFromCollector = new CopyOnWriteArrayList<>();
    private static final AtomicInteger totalLogs = new AtomicInteger();
    private static GenericContainer<?> otelExporter;
    private static Predicate<String> logFilter = l -> true;
    private FixMessagesLogger.BatchingLogger logger;
    private OtlpMessagesLogger otlpMessagesLogger;
    private MessageType messageTypeOut;
    private MessageType messageTypeIn;

    @BeforeAll
    static void setupOtlp() {
        otelExporter = new GenericContainer<>(DockerImageName.parse("otel/opentelemetry-collector:0.158.0"))
                .withExposedPorts(4317) // GRPC
                .withExposedPorts(4318) // HTTP
                .withLogConsumer(outputFrame -> {
                    String message = outputFrame.getUtf8StringWithoutLineEnding();
                    if (logFilter.test(message)) {
                        totalLogs.getAndIncrement();
                        receivedOutputFromCollector.add(message);
                    }
                    log.debug(message);
                })
                .withCommand("--config=/etc/otel/config.yaml")
                .withCopyFileToContainer(MountableFile.forClasspathResource("config.yaml"), "/etc/otel/config.yaml")
                .waitingFor(new LogMessageWaitStrategy()
                        .withRegEx(".*Everything is ready. Begin running and processing data.*")
                        .withStartupTimeout(Duration.of(10L, ChronoUnit.SECONDS)));
        otelExporter.start();
    }

    @AfterAll
    static void shutdownOtlp() {
        otelExporter.stop();
    }

    private static void assertLogMessageReceived(String log) {
        assertThat(receivedOutputFromCollector).anyMatch(logLine -> logLine.contains(log));
    }

    private static FixMessagesLogger.BatchingLogger.LogEvent getLogEvent(UTCTime utcTime, MessageType messageType, String message, FixMessagesLogger.LogEventType logEventType) {
        return new FixMessagesLogger.BatchingLogger.LogEvent() {
            @Override
            public UTCTime getLogTime() {
                return utcTime;
            }

            @Override
            public MessageType getMessageType() {
                return messageType;
            }

            @Override
            public ByteBuffer getMessage() {
                return ByteBuffer.wrap(message.getBytes());
            }

            @Override
            public FixMessagesLogger.LogEventType getLogEventType() {
                return logEventType;
            }

            @Override
            public FixMessagesLogger.BatchingLogger.LogEvent asImmutable() {
                return null;
            }
        };
    }

    @BeforeEach
    void setup() {
        setup(OtlpMessagesLoggerSettings.builder().logsBufferSize(2));
    }

    private void setup(OtlpMessagesLoggerSettings.OtlpMessagesLoggerSettingsBuilder settingsBuilder) {
        logFilter = l -> true;
        otlpMessagesLogger = new OtlpMessagesLogger(settingsBuilder
                .logsFlushDelay(Duration.ofMillis(250))
                .otlpEndpointUrl("http://" + otelExporter.getHost() + ":" + otelExporter.getMappedPort(4318))
                .build());
        otlpMessagesLogger.start();

        FixtMessageTypeRegistry registry = Mockito.mock(FixtMessageTypeRegistry.class);
        messageTypeOut = mockMsgType("OUT_MSG", 0);
        messageTypeIn = mockMsgType("IN_MSG", 1);

        when(registry.getMessageTypes()).thenReturn(List.of(messageTypeIn, messageTypeOut));

        FixSessionId fixSessionId = FixSessionId.of("testSid", FixRegularVersion.VERSION_44, "SENDER", "TARGET");

        logger = otlpMessagesLogger.instanciateLogger("test", fixSessionId, registry);
        logger.start();
        receivedOutputFromCollector.clear();
    }

    private MessageType mockMsgType(String code, int t) {
        MessageType messageType = Mockito.mock(MessageType.class);
        when(messageType.code()).thenReturn(code);
        when(messageType.getAsInt()).thenReturn(t);
        return messageType;
    }

    @AfterEach
    void shutdown() {
        logger.stop(Deadline.immediate());
        otlpMessagesLogger.stop(Deadline.immediate());
        receivedOutputFromCollector.clear();
        totalLogs.set(0);
    }

    @Test
    void testIsUnderlyingStorageResourceAvailable() {

        assertThat(logger.isUnderlyingStorageResourceAvailable()).isTrue();

        Awaitility.await().untilAsserted(() ->
                assertLogMessageReceived("Body: Str(" + OtlpMessagesLogger.OTLP_CONNECTOR_STATE_TEST_LOG + ")"));

        otelExporter.stop();

        assertThat(logger.isUnderlyingStorageResourceAvailable()).isFalse();

        otelExporter.start();
    }

    @Test
    void testBatchingEvents() throws FixMessagesLogger.LoggingException {

        FixMessagesLogger.BatchingLogger.LogEvent[] events = new FixMessagesLogger.BatchingLogger.LogEvent[4];
        events[0] = getLogEvent(UTCTime.of(Instant.ofEpochMilli(1000000)), messageTypeOut, "test outgoing", FixMessagesLogger.LogEventType.OUTGOING_MSG);
        events[1] = getLogEvent(UTCTime.of(Instant.ofEpochMilli(1000000)), messageTypeIn, "test incoming", FixMessagesLogger.LogEventType.INCOMING_MSG);
        events[2] = getLogEvent(UTCTime.of(Instant.ofEpochMilli(1000000)), null, "test event", FixMessagesLogger.LogEventType.EVENT);
        events[3] = getLogEvent(UTCTime.of(Instant.ofEpochMilli(1000000)), null, "test event with params", FixMessagesLogger.LogEventType.EVENT_WITH_PARAMS);

        logger.logEvents(events, events.length);

        Awaitility.await().untilAsserted(() -> {
            assertLogMessageReceived("Body: Str(test outgoing)");
            assertLogMessageReceived("fix.log.type: Str(out)");
            assertLogMessageReceived("fix.msg.type: Str(OUT_MSG)");
            assertLogMessageReceived("service.name: Str(test)");
            assertLogMessageReceived("fix.sid: Str(testSid)");
            assertLogMessageReceived("fix.sgid: Str(default)");
            assertLogMessageReceived("Timestamp: 1970-01-01 00:16:40 +0000 UTC");
        });
        Awaitility.await().untilAsserted(() -> {
            assertLogMessageReceived("Body: Str(test incoming)");
            assertLogMessageReceived("fix.log.type: Str(in)");
            assertLogMessageReceived("fix.msg.type: Str(IN_MSG)");
            assertLogMessageReceived("service.name: Str(test)");
            assertLogMessageReceived("fix.sid: Str(testSid)");
            assertLogMessageReceived("fix.sgid: Str(default)");
            assertLogMessageReceived("Timestamp: 1970-01-01 00:16:40 +0000 UTC");
        });
        Awaitility.await().untilAsserted(() -> {
            assertLogMessageReceived("Body: Str(test event)");
            assertLogMessageReceived("fix.log.type: Str(event)");
            assertLogMessageReceived("service.name: Str(test)");
            assertLogMessageReceived("fix.sid: Str(testSid)");
            assertLogMessageReceived("fix.sgid: Str(default)");
            assertLogMessageReceived("Timestamp: 1970-01-01 00:16:40 +0000 UTC");
        });
        Awaitility.await().untilAsserted(() -> {
            assertLogMessageReceived("Body: Str(test event with params)");
            assertLogMessageReceived("fix.log.type: Str(event)");
            assertLogMessageReceived("service.name: Str(test)");
            assertLogMessageReceived("fix.sid: Str(testSid)");
            assertLogMessageReceived("fix.sgid: Str(default)");
            assertLogMessageReceived("Timestamp: 1970-01-01 00:16:40 +0000 UTC");
        });
    }

    @Test
    void testLogWithContention() throws FixMessagesLogger.LoggingException {
        // this test is something not stable, this is however not on our side but otel logs process that seems to have troubles
        final int logsCount = 64 * 1024;
        for (int j = 0; j < 10; j++) {
            try {
                shutdown();
                setup(OtlpMessagesLoggerSettings.builder().logsBufferSize(128));
                logFilter = l -> l.contains("Body: Str(");
                for (int i = 0; i < logsCount; i++) {
                    logger.logIncoming(UTCTime.of(Instant.ofEpochMilli(1000000)), messageTypeIn, ByteBuffer.wrap((i + "").getBytes()));
                }
                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(totalLogs.get()).isEqualTo(logsCount));
                break;
            } catch (Exception ex) {
                System.out.println("Retrying received only " + totalLogs.get() + " logs");
            }
        }

        for (int i = 0; i < 300; i++) {
            assertThat(receivedOutputFromCollector.get(i)).contains("Body: Str(" + i + ")");
        }
        int lastLogIndex = logsCount - 1;
        assertThat(receivedOutputFromCollector.get(lastLogIndex)).contains("Body: Str(" + lastLogIndex + ")");
    }

    @Test
    void testKeepAliveClient() throws FixMessagesLogger.LoggingException {

        int logsCount = 10;
        for (int i = 0; i < logsCount; i++) {
            logger.logIncoming(UTCTime.of(Instant.ofEpochMilli(1000000)), messageTypeIn, ByteBuffer.wrap((i + " test message").getBytes()));
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(500));
        }

        for (int i = 0; i < logsCount; i++) {
            int finalI = i;
            Awaitility.await().with().pollDelay(Duration.ofMillis(2)).untilAsserted(() ->
                    assertLogMessageReceived("Body: Str(" + finalI + " test message)"));
        }
    }

    @Test
    void testLogIncoming() throws FixMessagesLogger.LoggingException {
        logger.logIncoming(UTCTime.of(Instant.ofEpochMilli(1000000)), messageTypeIn, ByteBuffer.wrap("test incoming message".getBytes()));
        Awaitility.await().untilAsserted(() -> {
            assertLogMessageReceived("Body: Str(test incoming message)");
            assertLogMessageReceived("fix.log.type: Str(in)");
            assertLogMessageReceived("fix.msg.type: Str(IN_MSG)");
            assertLogMessageReceived("service.name: Str(test)");
            assertLogMessageReceived("fix.sid: Str(testSid)");
            assertLogMessageReceived("fix.sgid: Str(default)");
            assertLogMessageReceived("Timestamp: 1970-01-01 00:16:40 +0000 UTC");
        });

        logger.logIncoming(UTCTime.of(Instant.ofEpochMilli(1000001)), messageTypeIn, ByteBuffer.wrap("test incoming message2".getBytes()));
        Awaitility.await().untilAsserted(() -> {
            assertLogMessageReceived("Body: Str(test incoming message2)");
            assertLogMessageReceived("fix.log.type: Str(in)");
            assertLogMessageReceived("fix.msg.type: Str(IN_MSG)");
            assertLogMessageReceived("service.name: Str(test)");
            assertLogMessageReceived("fix.sid: Str(testSid)");
            assertLogMessageReceived("fix.sgid: Str(default)");
            assertLogMessageReceived("Timestamp: 1970-01-01 00:16:40.001 +0000 UTC");
        });
    }

    @Test
    void testLogOutgoing() throws FixMessagesLogger.LoggingException {
        logger.logOutgoing(UTCTime.of(Instant.ofEpochMilli(1000000)), messageTypeOut, ByteBuffer.wrap("test outgoing message".getBytes()));

        Awaitility.await().untilAsserted(() -> {
            assertLogMessageReceived("Body: Str(test outgoing message)");
            assertLogMessageReceived("fix.log.type: Str(out)");
            assertLogMessageReceived("fix.msg.type: Str(OUT_MSG)");
            assertLogMessageReceived("service.name: Str(test)");
            assertLogMessageReceived("fix.sid: Str(testSid)");
            assertLogMessageReceived("fix.sgid: Str(default)");
            assertLogMessageReceived("Timestamp: 1970-01-01 00:16:40 +0000 UTC");
        });
    }

    @Test
    void testLogEvent() throws FixMessagesLogger.LoggingException {
        logger.logEvent(UTCTime.of(Instant.ofEpochMilli(1000000)), "test event");

        Awaitility.await().untilAsserted(() -> {
            assertLogMessageReceived("Body: Str(test event)");
            assertLogMessageReceived("fix.log.type: Str(event)");
            assertLogMessageReceived("service.name: Str(test)");
            assertLogMessageReceived("Timestamp: 1970-01-01 00:16:40 +0000 UTC");
        });
    }

    @Test
    void testLogEventWithFixDelimiterFieldReplaced() throws FixMessagesLogger.LoggingException {
        shutdown();
        setup(OtlpMessagesLoggerSettings.builder().fixMessageFieldsDelimiter('|'));

        logger.logEvent(UTCTime.of(Instant.ofEpochMilli(1000000)), "test\001event\001replaced\001");

        Awaitility.await().untilAsserted(() -> {
            assertLogMessageReceived("Body: Str(test|event|replaced|)");
            assertLogMessageReceived("fix.log.type: Str(event)");
            assertLogMessageReceived("service.name: Str(test)");
            assertLogMessageReceived("Timestamp: 1970-01-01 00:16:40 +0000 UTC");
        });
    }

    @Test
    void testLogEventWithParams() throws FixMessagesLogger.LoggingException {
        logger.logEvent(UTCTime.of(Instant.ofEpochMilli(1000000)), "test event %s %s", "param1", "param2");

        Awaitility.await().untilAsserted(() -> {
            assertLogMessageReceived("Body: Str(test event param1 param2)");
            assertLogMessageReceived("fix.log.type: Str(event)");
            assertLogMessageReceived("service.name: Str(test)");
            assertLogMessageReceived("fix.sid: Str(testSid)");
            assertLogMessageReceived("fix.sgid: Str(default)");
            assertLogMessageReceived("Timestamp: 1970-01-01 00:16:40 +0000 UTC");
        });
    }
}