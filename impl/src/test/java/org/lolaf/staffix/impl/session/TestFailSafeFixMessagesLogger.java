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
package org.lolaf.staffix.impl.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.AlertLevel;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestFailSafeFixMessagesLogger {

    private static final Duration CHECK_INTERVAL = Duration.ofMillis(50);
    private static final Duration BLOCKED_CALL = Duration.ofMillis(300);
    private static final ByteBuffer MESSAGE = ByteBuffer.allocate(8);

    private final UnderlyingResourceChecker checker = new UnderlyingResourceChecker("test-engine");
    private final FixMessagesLogger.Logger logger = mock(FixMessagesLogger.Logger.class);
    private final AtomicBoolean available = new AtomicBoolean(true);

    @BeforeEach
    void stubTheLogger() {
        when(logger.isUnderlyingStorageResourceAvailable()).thenAnswer(invocation -> available.get());
        when(logger.getUnderlyingStorageResourceDescription()).thenReturn("test resource");
    }

    @AfterEach
    void stopChecker() {
        checker.stop();
    }

    private FailSafeFixMessagesLogger failSafe(Duration checkInterval) {
        FixMessagesLoggerSettings settings = mock(FixMessagesLoggerSettings.class);
        when(settings.getResourceCheckInterval()).thenReturn(checkInterval);
        when(settings.getAlertClearDelay()).thenReturn(Duration.ZERO);
        return new FailSafeFixMessagesLogger(logger, FixSessionId.of("main", FixRegularVersion.VERSION_44, "SENDER", "TARGET"), settings, checker);
    }

    private void loggerBlocksThenFails() throws FixMessagesLogger.LoggingException {
        doAnswer(invocation -> {
            Thread.sleep(BLOCKED_CALL.toMillis());
            throw new FixMessagesLogger.LoggingException();
        }).when(logger).logIncoming(any(), any(), any());
    }

    private void loggerIsDown(FailSafeFixMessagesLogger failSafe) throws FixMessagesLogger.LoggingException {
        loggerBlocksThenFails();
        available.set(false);
        failSafe.logIncoming(null, null, MESSAGE);
        await().until(() -> alertLevel(failSafe).equals(Optional.of(AlertLevel.MAJOR)));
    }

    private static Optional<AlertLevel> alertLevel(FailSafeFixMessagesLogger failSafe) {
        return failSafe.getProbe().getEvaluator().evaluate().map(Alert::getLevel);
    }

    @Test
    void callsAreSkippedAtOnceWhileTheResourceIsDown() throws Exception {
        FailSafeFixMessagesLogger failSafe = failSafe(CHECK_INTERVAL);
        loggerIsDown(failSafe);

        long start = System.nanoTime();
        failSafe.logIncoming(null, null, MESSAGE);
        failSafe.logOutgoing(null, null, MESSAGE);
        failSafe.logEvent(null, "event");
        failSafe.logEvent(null, "event %s", 1);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(BLOCKED_CALL.dividedBy(10));
        verify(logger, times(1)).logIncoming(any(), any(), any());
        verify(logger, never()).logOutgoing(any(), any(), any());
        verify(logger, never()).logEvent(any(), anyString());
        verify(logger, never()).logEvent(any(), anyString(), any(Object[].class));
    }

    @Test
    void noFailureReachesTheSession() throws Exception {
        FailSafeFixMessagesLogger failSafe = failSafe(CHECK_INTERVAL);
        doThrow(new FixMessagesLogger.LoggingException()).when(logger).logOutgoing(any(), any(), any());
        doThrow(new IllegalStateException("bug")).when(logger).logEvent(any(), anyString());

        assertThatCode(() -> failSafe.logOutgoing(null, null, MESSAGE)).doesNotThrowAnyException();
        assertThatCode(() -> failSafe.logEvent(null, "event")).doesNotThrowAnyException();

        assertThat(alertLevel(failSafe)).contains(AlertLevel.WARNING);
    }

    @Test
    void theResourceComingBackLetsCallsThroughAndClearsTheAlert() throws Exception {
        FailSafeFixMessagesLogger failSafe = failSafe(CHECK_INTERVAL);
        loggerIsDown(failSafe);

        reset(logger);
        when(logger.isUnderlyingStorageResourceAvailable()).thenReturn(true);
        await().untilAsserted(() -> {
            failSafe.logOutgoing(null, null, MESSAGE);
            verify(logger).logOutgoing(null, null, MESSAGE);
        });

        assertThat(alertLevel(failSafe)).isEmpty();
    }

    /**
     * A busy session: a call succeeds between the failure and the check finding the resource down, which clears the
     * warning before the breaker raises its major alert. That alert must still clear once the resource is back.
     */
    @Test
    void theAlertRaisedWhenTheResourceIsFoundDownClearsOnceItIsBack() throws Exception {
        FailSafeFixMessagesLogger failSafe = failSafe(CHECK_INTERVAL);
        CountDownLatch checkStarted = new CountDownLatch(1);
        CountDownLatch checkAnswers = new CountDownLatch(1);
        available.set(false);
        when(logger.isUnderlyingStorageResourceAvailable()).thenAnswer(invocation -> {
            checkStarted.countDown();
            checkAnswers.await();
            return available.get();
        });
        doThrow(new FixMessagesLogger.LoggingException()).doNothing().when(logger).logIncoming(any(), any(), any());

        failSafe.logIncoming(null, null, MESSAGE);
        checkStarted.await();
        failSafe.logIncoming(null, null, MESSAGE);
        checkAnswers.countDown();
        await().until(() -> alertLevel(failSafe).equals(Optional.of(AlertLevel.MAJOR)));

        available.set(true);
        await().untilAsserted(() -> {
            failSafe.logIncoming(null, null, MESSAGE);
            verify(logger, times(3)).logIncoming(any(), any(), any());
        });
        assertThat(alertLevel(failSafe)).isEmpty();
    }

    @Test
    void aZeroCheckIntervalSendsEveryCallToTheLogger() throws Exception {
        FailSafeFixMessagesLogger failSafe = failSafe(Duration.ZERO);
        doThrow(new FixMessagesLogger.LoggingException()).when(logger).logIncoming(any(), any(), any());
        available.set(false);

        failSafe.logIncoming(null, null, MESSAGE);
        await().pollDelay(CHECK_INTERVAL.multipliedBy(3)).until(() -> true);
        failSafe.logIncoming(null, null, MESSAGE);

        verify(logger, times(2)).logIncoming(any(), any(), any());
        verify(logger, never()).isUnderlyingStorageResourceAvailable();
        assertThat(alertLevel(failSafe)).contains(AlertLevel.WARNING);
    }

    @Test
    void stoppingEndsTheChecks() throws Exception {
        FailSafeFixMessagesLogger failSafe = failSafe(CHECK_INTERVAL);
        loggerIsDown(failSafe);

        failSafe.stop(Deadline.immediate());
        reset(logger);
        await().pollDelay(CHECK_INTERVAL.multipliedBy(3)).until(() -> true);

        verify(logger, never()).isUnderlyingStorageResourceAvailable();
    }
}
