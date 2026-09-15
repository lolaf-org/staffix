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
package org.lolaf.staffix.stores.loggers.async;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.stores.core.async.AsyncStoreSettings;
import org.lolaf.staffix.stores.core.async.StoreUnderlyingResourceStateListener;
import org.lolaf.staffix.tests.TestingFixMessagesLoggerSettings;
import org.mockito.Mockito;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class TestAsyncMessagesLogger {

    private static final int DEFAULT_LOG_BYTE_BUFFER_SIZE = 8;
    private static final String TEST_LOGGER_STORE_DESCRIPTION = "test logger store description";

    AsyncMessagesLogger asyncMessagesLogger;
    FixMessagesLogger.Logger logger;
    AsyncLogger asyncLogger;
    FixSessionId fixSessionId;
    MessageType messageType;
    UTCTime logTime;
    AsyncMessagesLoggerSettings asyncMessagesLoggerSettings;
    MessageTypeRegistry messageTypeRegistry;
    StoreUnderlyingResourceStateListener storeUnderlyingResourceStateListener;

    private static ByteBuffer assertLogEquals(String log) {
        return argThat(byteBuffer -> {
            int initialPosition = byteBuffer.position();
            int initialLimit = byteBuffer.limit();
            byte[] content = new byte[byteBuffer.remaining()];
            byteBuffer.get(content);
            byteBuffer.position(initialPosition);
            byteBuffer.limit(initialLimit);
            return new String(content).equals(log);
        });
    }

    static MessageType getMessageType(String code) {
        return MessageType.of(code, false);
    }

    /**
     * Live threads of the underlying resource state watchdog, named by
     * {@code UnderlyingResourceWatchContext.startMe()}.
     */
    private static List<String> watchdogThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.endsWith("-underlying-resource-state-watchdog"))
                .collect(Collectors.toList());
    }

    private static ByteBuffer serializeLog(String testLog) {
        ByteBuffer message = ByteBuffer.allocate(testLog.length());
        message.put(testLog.getBytes()).flip();
        return message;
    }

    @BeforeEach
    void setup() {
        storeUnderlyingResourceStateListener = mock(StoreUnderlyingResourceStateListener.class);
        fixSessionId = FixSessionId.of("test", FixRegularVersion.VERSION_44, "sender", "target");
        logger = mock(FixMessagesLogger.Logger.class);
        when(logger.isUnderlyingStorageResourceAvailable()).thenReturn(true);
        when(logger.getUnderlyingStorageResourceDescription()).thenReturn(TEST_LOGGER_STORE_DESCRIPTION);

        logTime = new UTCTime.TimeImpl().from(1234, 4321);
        asyncMessagesLoggerSettings = AsyncMessagesLoggerSettings.builder()
                .asyncStoreSettings(AsyncStoreSettings.builder()
                        .asyncQueueDirectory("./target/" + System.currentTimeMillis())
                        .storeUnderlyingResourceStateListener(storeUnderlyingResourceStateListener)
                        .build())
                .wrappedLoggerSettings(TestingFixMessagesLoggerSettings.builder()
                        .testingLogger(logger)
                        .messageFilter((mt, direction) -> !mt.code().equals("A"))
                        .build())
                .logByteBufferSize(DEFAULT_LOG_BYTE_BUFFER_SIZE)
                .useDirectByteBuffer(true)
                .instanceId("testId")
                .build();
        asyncMessagesLogger = new AsyncMessagesLogger(asyncMessagesLoggerSettings);
        asyncMessagesLogger.start();

        messageType = getMessageType("A");
        messageTypeRegistry = mock(MessageTypeRegistry.class);
        when(messageTypeRegistry.find(anyInt())).thenReturn(messageType);

        asyncLogger = (AsyncLogger) asyncMessagesLogger.getLogger("test", fixSessionId, messageTypeRegistry);
    }

    @AfterEach
    void shutdown() {
        if (asyncLogger != null) {
            asyncLogger.stop(Deadline.immediate());
        }
        asyncMessagesLogger.stop(Deadline.immediate());
    }

    @Test
    void testStartWithUnavailableUnderlyingResource() throws FixMessagesLogger.LoggingException {
        when(logger.isUnderlyingStorageResourceAvailable()).thenReturn(false);

        asyncLogger.start();

        verify(logger).isUnderlyingStorageResourceAvailable();

        String testLog = "test message log";

        asyncLogger.logIncoming(logTime, messageType, serializeLog(testLog));

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));

        verify(logger, never()).logIncoming(any(), any(), any());

        // the resource being down is what the watchdog exists for, so its thread must be running at this point. Not an
        // exact count: the other tests of this class start watchdogs too and shutdownNow() only asks their thread to
        // die, so one of them may still be winding down here
        assertThat(watchdogThreads()).isNotEmpty();

        when(logger.isUnderlyingStorageResourceAvailable()).thenReturn(true);

        await().untilAsserted(() -> verify(logger).logIncoming(eq(logTime), eq(messageType), assertLogEquals(testLog)));

        // and gone once the resource is back. The watchdog shuts its own executor down from inside its scheduled task
        // (UnderlyingResourceWatchContext.checkUnderlyingResourceState), so the thread dies shortly after the replayed
        // log lands rather than before it: this has to be awaited. Tracking the thread by name also keeps the
        // assertion out of the way of every other thread the JVM happens to start or stop meanwhile, which is what
        // made comparing Thread.activeCount() fail under load.
        await().untilAsserted(() -> assertThat(watchdogThreads()).isEmpty());
    }

    @Test
    void testLogWithFailureDueToUnderlyingResourceDown() throws FixMessagesLogger.LoggingException {
        asyncLogger.start();

        verify(logger).isUnderlyingStorageResourceAvailable();
        verify(storeUnderlyingResourceStateListener).onStoreStateUp(fixSessionId, TEST_LOGGER_STORE_DESCRIPTION);

        String testLog = "test message log";

        asyncLogger.logIncoming(logTime, messageType, serializeLog(testLog));

        await().untilAsserted(() -> verify(logger).logIncoming(eq(logTime), eq(messageType), assertLogEquals(testLog)));

        when(logger.isUnderlyingStorageResourceAvailable()).thenReturn(false);
        doThrow(new IllegalStateException("test exception")).when(logger).logIncoming(any(), any(), any());

        String testLog2 = "test message log2";
        asyncLogger.logIncoming(logTime, messageType, serializeLog(testLog2));

        await().untilAsserted(() -> verify(logger).logIncoming(eq(logTime), eq(messageType), assertLogEquals(testLog2)));
        // the watchdog is started from the catch around the failing logIncoming call, so seeing that call does not mean
        // it is up yet
        await().untilAsserted(() -> assertThat(asyncLogger.hasInactiveUnderlyingResourceWatchDog()).isFalse());
        await().untilAsserted(() -> verify(storeUnderlyingResourceStateListener).onStoreStateDown(fixSessionId, TEST_LOGGER_STORE_DESCRIPTION));

        reset(logger);
        reset(storeUnderlyingResourceStateListener);
        when(logger.isUnderlyingStorageResourceAvailable()).thenReturn(true);
        await().untilAsserted(() -> verify(logger).logIncoming(eq(logTime), eq(messageType), assertLogEquals(testLog2)));
        // and symmetrically it stops itself only after replaying, in UnderlyingResourceWatchContext
        // .checkUnderlyingResourceState, so the replay just observed does not mean it is down yet
        await().untilAsserted(() -> assertThat(asyncLogger.hasInactiveUnderlyingResourceWatchDog()).isTrue());
        await().untilAsserted(() -> verify(storeUnderlyingResourceStateListener).onStoreStateUp(fixSessionId, TEST_LOGGER_STORE_DESCRIPTION));
    }

    @Test
    void testLogWithNonZeroBufferPosition() {
        asyncLogger.start();
        String testLog = "test message log";

        ByteBuffer message = ByteBuffer.allocate(1024);
        message.position(DEFAULT_LOG_BYTE_BUFFER_SIZE);
        message.put(testLog.getBytes()).flip().position(DEFAULT_LOG_BYTE_BUFFER_SIZE);

        asyncLogger.logIncoming(logTime, messageType, message);

        assertThat(asyncLogger.getQueueOffersCount()).isEqualTo(1L);
        await().untilAsserted(() -> verify(logger).logIncoming(eq(logTime), eq(messageType), assertLogEquals(testLog)));
        assertThat(asyncLogger.getQueuePollsCount()).isEqualTo(1L);
    }

    @Test
    void testBatchingLogWithFailureDueToUnderlyingResourceDown() throws FixMessagesLogger.LoggingException {
        asyncMessagesLogger.stop(Deadline.immediate());

        FixMessagesLogger.BatchingLogger batchingLogger = mock(FixMessagesLogger.BatchingLogger.class);
        when(batchingLogger.isUnderlyingStorageResourceAvailable()).thenReturn(true);

        asyncMessagesLoggerSettings = asyncMessagesLoggerSettings.toBuilder()
                .asyncStoreSettings(asyncMessagesLoggerSettings.getAsyncStoreSettings().toBuilder()
                        .eventsBatching(2)
                        .batchingFlushInterval(Duration.ofSeconds(1))
                        .build())
                .wrappedLoggerSettings(TestingFixMessagesLoggerSettings.builder()
                        .testingLogger(batchingLogger)
                        .build())
                .build();
        asyncMessagesLogger = new AsyncMessagesLogger(asyncMessagesLoggerSettings);
        asyncMessagesLogger.start();
        asyncLogger = (AsyncLogger) asyncMessagesLogger.getLogger("test", fixSessionId, messageTypeRegistry);
        asyncLogger.start();

        verify(batchingLogger).isUnderlyingStorageResourceAvailable();

        String testLog = "test message log";

        asyncLogger.logIncoming(logTime, messageType, serializeLog(testLog));

        assertBatchingLogProcessed(1, 2, batchingLogger, testLog);

        reset(batchingLogger);
        when(batchingLogger.isUnderlyingStorageResourceAvailable()).thenReturn(false);
        Mockito.doThrow(new IllegalStateException("test exception")).when(batchingLogger).logEvents(any(), anyInt());

        String testLog2 = "test message log2";
        asyncLogger.logIncoming(logTime, messageType, serializeLog(testLog2));

        assertBatchingLogProcessed(1, 2, batchingLogger, testLog2);
        // the watchdog is started from the catch around the failing logEvents call, so seeing that call does not mean
        // it is up yet
        await().untilAsserted(() -> assertThat(asyncLogger.hasInactiveUnderlyingResourceWatchDog()).isFalse());

        when(batchingLogger.isUnderlyingStorageResourceAvailable()).thenReturn(true);
        assertBatchingLogProcessed(2, 2, batchingLogger, testLog2);
        // and symmetrically it stops itself only after replaying, in UnderlyingResourceWatchContext
        // .checkUnderlyingResourceState, so the replay just observed does not mean it is down yet
        await().untilAsserted(() -> assertThat(asyncLogger.hasInactiveUnderlyingResourceWatchDog()).isTrue());
    }

    @Test
    void testBatchingLogIncoming() {
        asyncMessagesLogger.stop(Deadline.immediate());

        FixMessagesLogger.BatchingLogger batchingLogger = mock(FixMessagesLogger.BatchingLogger.class);
        when(batchingLogger.isUnderlyingStorageResourceAvailable()).thenReturn(true);

        asyncMessagesLoggerSettings = asyncMessagesLoggerSettings.toBuilder()
                .asyncStoreSettings(asyncMessagesLoggerSettings.getAsyncStoreSettings().toBuilder()
                        .eventsBatching(10)
                        .batchingFlushInterval(Duration.ofSeconds(1))
                        .build())
                .wrappedLoggerSettings(TestingFixMessagesLoggerSettings.builder()
                        .testingLogger(batchingLogger)
                        .build())
                .build();
        asyncMessagesLogger = new AsyncMessagesLogger(asyncMessagesLoggerSettings);
        asyncMessagesLogger.start();
        asyncLogger = (AsyncLogger) asyncMessagesLogger.getLogger("test", fixSessionId, messageTypeRegistry);
        asyncLogger.start();

        String logMessage = "test";

        asyncLogger.logIncoming(logTime, messageType, serializeLog(logMessage));

        assertBatchingLogProcessed(1, 10, batchingLogger, logMessage);

        reset(batchingLogger);
        for (int i = 0; i < 5; i++) {
            asyncLogger.logIncoming(logTime, messageType, serializeLog("test message log " + i));
        }
        await().untilAsserted(() -> verify(batchingLogger).logEvents(any(), eq(5)));

        reset(batchingLogger);
        for (int i = 0; i < 14; i++) {
            asyncLogger.logIncoming(logTime, messageType, serializeLog("test message log part 2 " + i));
        }

        await().untilAsserted(() -> verify(batchingLogger).logEvents(any(), eq(10)));
        await().untilAsserted(() -> verify(batchingLogger).logEvents(any(), eq(4)));
    }

    private void assertBatchingLogProcessed(int times, int logEventsCount, FixMessagesLogger.BatchingLogger batchingLogger, String logMessage) {
        await().untilAsserted(() -> verify(batchingLogger, times(times))
                .logEvents(assertArg((Consumer<FixMessagesLogger.BatchingLogger.LogEvent[]>) logEvents -> {
                    assertThat(logEvents).hasSize(logEventsCount);
                    FixMessagesLogger.BatchingLogger.LogEvent logEvent = logEvents[0];
                    assertThat(logEvent).isNotNull();
                    assertThat(logEvent.getLogEventType()).isEqualTo(FixMessagesLogger.LogEventType.INCOMING_MSG);
                    assertThat(logEvent.getMessageType()).isEqualTo(messageType);
                    assertThat(logEvent.getMessage()).isEqualTo(ByteBuffer.wrap(logMessage.getBytes()));
                    assertThat(logEvent.getMessage().position()).isZero();
                    assertThat(logEvent.getMessage().limit()).isEqualTo(logMessage.length());
                    assertThat(logEvent.getMessage().capacity()).isEqualTo(Math.max(logMessage.length(), DEFAULT_LOG_BYTE_BUFFER_SIZE));
                    assertThat(logEvents[1]).isNull();
                }), eq(1)));
    }

    @Test
    void testRestartWithPendingMessageWillReprocessThem() {
        asyncLogger.start();
        String testLog = "test message log";
        for (int i = 0; i < 32 * 1024; i++) {
            asyncLogger.logIncoming(logTime, messageType, serializeLog(testLog));
        }

        asyncLogger.stop(Deadline.immediate());

        assertThat(asyncLogger.hasEmptyQueue()).isFalse();

        asyncLogger.start();

        assertThat(asyncLogger.hasEmptyQueue()).isTrue();
    }

    @Test
    void testStopWithImmediateFlushDeadline() {
        asyncLogger.start();
        String testLog = "test message log";
        for (int i = 0; i < 32 * 1024; i++) {
            asyncLogger.logIncoming(logTime, messageType, serializeLog(testLog));
        }

        asyncLogger.stop(Deadline.immediate());

        assertThat(asyncLogger.hasEmptyQueue()).isFalse();
    }

    @Test
    void testStopWithNonFlushDeadline() {
        asyncLogger.start();
        String testLog = "test message log";
        for (int i = 0; i < 32 * 1024; i++) {
            asyncLogger.logIncoming(logTime, messageType, serializeLog(testLog));
        }

        asyncLogger.stop(Deadline.unlimited());

        assertThat(asyncLogger.hasEmptyQueue()).isTrue();
        assertThat(asyncLogger.getQueuePollsCount()).isEqualTo(32 * 1024);

        asyncLogger.start();
        for (int i = 0; i < 32 * 1024; i++) {
            asyncLogger.logIncoming(logTime, messageType, serializeLog(testLog));
        }

        asyncLogger.stop(Deadline.of(Duration.ofSeconds(10)));

        assertThat(asyncLogger.hasEmptyQueue()).isTrue();
        assertThat(asyncLogger.getQueuePollsCount()).isEqualTo(32 * 1024);

        asyncLogger.start();
        for (int i = 0; i < 32 * 1024; i++) {
            asyncLogger.logIncoming(logTime, messageType, serializeLog(testLog));
        }

        asyncLogger.stop(Deadline.of(Duration.ofMillis(2)));

        assertThat(asyncLogger.hasEmptyQueue()).isFalse();
        assertThat(asyncLogger.getQueuePollsCount()).isGreaterThan(128);
        // unless we have a blazing fast test env it should work
        assertThat(asyncLogger.getQueuePollsCount()).isLessThan(8 * 1024);
    }

    @Test
    void testLogIncoming() {
        asyncLogger.start();
        String testLog = "test message log";

        asyncLogger.logIncoming(logTime, messageType, serializeLog(testLog));

        assertThat(asyncLogger.getQueueOffersCount()).isEqualTo(1L);
        await().untilAsserted(() -> verify(logger).logIncoming(eq(logTime), eq(messageType), assertLogEquals(testLog)));

        String testLog2 = "test2 message log";
        ByteBuffer message2 = ByteBuffer.wrap(testLog2.getBytes());
        UTCTime logTime2 = new UTCTime.TimeImpl().from(12345, 54321);

        reset(logger);
        asyncLogger.logIncoming(logTime2, messageType, message2);

        assertThat(asyncLogger.getQueueOffersCount()).isEqualTo(2L);
        await().untilAsserted(() -> verify(logger).logIncoming(eq(logTime2), eq(messageType), assertLogEquals(testLog2)));
        assertThat(asyncLogger.getQueuePollsCount()).isEqualTo(2L);
    }

    @Test
    void testLogIncomingAreFiltered() throws FixMessagesLogger.LoggingException {
        asyncLogger.start();
        asyncLogger.logIncoming(logTime, getMessageType("UNKNOWN"), ByteBuffer.wrap("test message log".getBytes()));

        LockSupport.parkNanos(asyncMessagesLoggerSettings.getAsyncStoreSettings().getBatchingFlushInterval().toNanos());

        assertThat(asyncLogger.getQueueOffersCount()).isZero();
        verify(logger, never()).logIncoming(any(), any(), any());
        assertThat(asyncLogger.getQueuePollsCount()).isZero();
    }

    @Test
    void testLogOutgoing() {
        asyncLogger.start();

        String testLog = "test message log";

        asyncLogger.logOutgoing(logTime, messageType, serializeLog(testLog));

        assertThat(asyncLogger.getQueueOffersCount()).isEqualTo(1L);
        await().untilAsserted(() -> verify(logger).logOutgoing(eq(logTime), eq(messageType), assertLogEquals(testLog)));
        assertThat(asyncLogger.getQueuePollsCount()).isEqualTo(1L);
    }

    @Test
    void testLogEvent() {
        asyncLogger.start();

        String testEvent = "test event";

        asyncLogger.logEvent(logTime, testEvent);

        assertThat(asyncLogger.getQueueOffersCount()).isEqualTo(1L);
        await().untilAsserted(() -> verify(logger).logEvent(logTime, testEvent));
        assertThat(asyncLogger.getQueuePollsCount()).isEqualTo(1L);
    }

    @Test
    void testLogEventWithParams() {
        asyncLogger.start();

        String testEvent = "test event with params";

        asyncLogger.logEvent(logTime, testEvent, "param1", "param2");

        assertThat(asyncLogger.getQueueOffersCount()).isEqualTo(1L);
        await().untilAsserted(() -> verify(logger).logEvent(logTime, testEvent, "param1", "param2"));
        assertThat(asyncLogger.getQueuePollsCount()).isEqualTo(1L);
    }
}