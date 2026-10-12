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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.msg.FixtMessageTypeRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sending runs on the logger's own flushing thread, so a collector that is down has to be reported by the session's
 * next log call, or the session never learns of it. The collector answers 503 while it is down.
 */
class TestOtlpMessagesLoggerFailures {

    private static final UTCTime LOG_TIME = UTCTime.of(Instant.ofEpochMilli(1_000_000));

    private final AtomicInteger acceptedRequests = new AtomicInteger();
    private volatile boolean collectorUp;
    private HttpServer collector;
    private int port;
    private OtlpMessagesLogger otlpMessagesLogger;
    private FixMessagesLogger.BatchingLogger logger;
    private MessageType messageType;

    @BeforeEach
    void setup() throws IOException {
        collector = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        collector.createContext("/v1/logs", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (collectorUp) {
                acceptedRequests.incrementAndGet();
            }
            exchange.sendResponseHeaders(collectorUp ? 200 : 503, -1);
            exchange.close();
        });
        collector.start();
        port = collector.getAddress().getPort();
        otlpMessagesLogger = new OtlpMessagesLogger(OtlpMessagesLoggerSettings.builder()
                .logsFlushDelay(Duration.ofMillis(50))
                .otlpEndpointUrl("http://localhost:" + port)
                .httpSenderSettings(HttpSenderSettings.builder()
                        .connectTimeout(Duration.ofMillis(500))
                        .requestTimeout(Duration.ofMillis(500))
                        .build())
                .build());
        otlpMessagesLogger.start();
        FixtMessageTypeRegistry registry = mock(FixtMessageTypeRegistry.class);
        messageType = mock(MessageType.class);
        when(messageType.code()).thenReturn("IN_MSG");
        when(registry.getMessageTypes()).thenReturn(List.of(messageType));
        logger = otlpMessagesLogger.instanciateLogger("engine", "test",
                FixSessionId.of("testSid", FixRegularVersion.VERSION_44, "SENDER", "TARGET"), registry);
        logger.start();
    }

    @AfterEach
    void shutdown() {
        logger.stop(Deadline.immediate());
        otlpMessagesLogger.stop(Deadline.immediate());
        collector.stop(0);
    }

    private void logIncoming(String body) throws FixMessagesLogger.LoggingException {
        logger.logIncoming(LOG_TIME, messageType, ByteBuffer.wrap(body.getBytes()));
    }

    @Test
    void aFailedFlushIsReportedByTheNextLogCall() throws Exception {
        logIncoming("sent while the collector is down");

        await().untilAsserted(() -> assertThatThrownBy(() -> logIncoming("after the failed flush"))
                .isInstanceOf(FixMessagesLogger.LoggingException.class)
                .hasMessageContaining("http://localhost:" + port)
                .hasCauseInstanceOf(IOException.class));
    }

    @Test
    void aFailedFlushIsReportedByTheNextBatch() throws Exception {
        logIncoming("sent while the collector is down");

        await().untilAsserted(() -> assertThatThrownBy(() -> logger.logEvents(new FixMessagesLogger.BatchingLogger.LogEvent[0], 0))
                .isInstanceOf(FixMessagesLogger.LoggingException.class));
    }

    @Test
    void aFailureIsReportedOnce() throws Exception {
        logIncoming("sent while the collector is down");
        await().untilAsserted(() -> assertThatThrownBy(() -> logIncoming("after the failed flush"))
                .isInstanceOf(FixMessagesLogger.LoggingException.class));

        collectorUp = true;

        assertThatCode(() -> logIncoming("right after the report")).doesNotThrowAnyException();
    }

    /**
     * A session whose log calls are skipped while the collector is down sends nothing, so no flush succeeds: the
     * resource check that finds the collector back is what clears the failure.
     */
    @Test
    void theCheckFindingTheCollectorBackClearsAnUnreportedFailure() throws Exception {
        logIncoming("sent while the collector is down");
        await().until(() -> !logger.isUnderlyingStorageResourceAvailable());
        collectorUp = true;

        assertThat(logger.isUnderlyingStorageResourceAvailable()).isTrue();
        assertThatCode(() -> logIncoming("once the collector is back")).doesNotThrowAnyException();
        await().until(() -> acceptedRequests.get() >= 2);
    }
}
