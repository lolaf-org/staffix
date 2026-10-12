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
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestFailSafeFixSessionMessagesStore {

    private static final Duration CHECK_INTERVAL = Duration.ofMillis(50);
    private static final Duration BLOCKED_CALL = Duration.ofMillis(300);
    private static final ByteBuffer MESSAGE = ByteBuffer.allocate(8);

    private final UnderlyingResourceChecker checker = new UnderlyingResourceChecker("test-engine");
    private final FixSessionMessagesStore store = mock(FixSessionMessagesStore.class);
    private final AtomicBoolean available = new AtomicBoolean(true);

    @BeforeEach
    void stubTheStore() {
        when(store.isUnderlyingStorageResourceAvailable()).thenAnswer(invocation -> available.get());
        when(store.getUnderlyingStorageResourceDescription()).thenReturn("test resource");
    }

    @AfterEach
    void stopChecker() {
        checker.stop();
    }

    private FailSafeFixSessionMessagesStore failSafe(Duration checkInterval) {
        FixMessagesStoreSettings settings = mock(FixMessagesStoreSettings.class);
        when(settings.getResourceCheckInterval()).thenReturn(checkInterval);
        when(settings.getAlertClearDelay()).thenReturn(Duration.ZERO);
        return new FailSafeFixSessionMessagesStore(store, FixSessionId.of("main", FixRegularVersion.VERSION_44, "SENDER", "TARGET"), settings, checker);
    }

    private void storeBlocksThenFails() {
        doAnswer(invocation -> {
            Thread.sleep(BLOCKED_CALL.toMillis());
            throw new FixSessionMessagesStore.StoreException("connection timeout");
        }).when(store).storeMessageSent(anyLong(), any());
    }

    private static Optional<AlertLevel> alertLevel(FailSafeFixSessionMessagesStore failSafe) {
        return failSafe.getProbe().getEvaluator().evaluate().map(Alert::getLevel);
    }

    @Test
    void messageWritesAreSkippedAtOnceWhileTheResourceIsDown() {
        FailSafeFixSessionMessagesStore failSafe = failSafe(CHECK_INTERVAL);
        storeBlocksThenFails();
        available.set(false);

        failSafe.storeMessageSent(1, MESSAGE);
        await().until(() -> alertLevel(failSafe).equals(Optional.of(AlertLevel.MAJOR)));

        long start = System.nanoTime();
        failSafe.storeMessageSent(2, MESSAGE);
        failSafe.storeMessageSent(3, MESSAGE);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(BLOCKED_CALL.dividedBy(10));
        verify(store, times(1)).storeMessageSent(anyLong(), any());
    }

    @Test
    void sequenceNumberWritesReachTheStoreWhileTheResourceIsDown() {
        FailSafeFixSessionMessagesStore failSafe = failSafe(CHECK_INTERVAL);
        storeBlocksThenFails();
        available.set(false);
        failSafe.storeMessageSent(1, MESSAGE);
        await().until(() -> alertLevel(failSafe).equals(Optional.of(AlertLevel.MAJOR)));

        failSafe.storeNextIncomingSeqNum(3);
        failSafe.storeNextOutgoingSeqNum(3);
        failSafe.resetSequenceNumbers();

        verify(store).storeNextIncomingSeqNum(3);
        verify(store).storeNextOutgoingSeqNum(3);
        verify(store).resetSequenceNumbers();
    }

    @Test
    void findFailsAtOnceWhileTheResourceIsDown() {
        FailSafeFixSessionMessagesStore failSafe = failSafe(CHECK_INTERVAL);
        storeBlocksThenFails();
        available.set(false);
        failSafe.storeMessageSent(1, MESSAGE);
        await().until(() -> alertLevel(failSafe).isPresent() && alertLevel(failSafe).get() == AlertLevel.MAJOR);

        assertThatThrownBy(() -> failSafe.find(1, 2, (seqNum, message) -> true))
                .isInstanceOf(FixSessionMessagesStore.StoreException.class)
                .hasMessageContaining("is down");
        verify(store, never()).find(anyLong(), anyLong(), any());
    }

    @Test
    void theResourceComingBackLetsWritesThroughAndClearsTheAlert() {
        FailSafeFixSessionMessagesStore failSafe = failSafe(CHECK_INTERVAL);
        storeBlocksThenFails();
        available.set(false);
        failSafe.storeMessageSent(1, MESSAGE);
        await().until(() -> alertLevel(failSafe).equals(Optional.of(AlertLevel.MAJOR)));

        reset(store);
        doNothing().when(store).storeMessageSent(anyLong(), any());
        when(store.isUnderlyingStorageResourceAvailable()).thenReturn(true);
        await().untilAsserted(() -> {
            failSafe.storeMessageSent(2, MESSAGE);
            verify(store).storeMessageSent(2, MESSAGE);
        });

        assertThat(alertLevel(failSafe)).isEmpty();
    }

    @Test
    void aFailureOnAnAvailableResourceRaisesAWarningAndSkipsNothing() {
        FailSafeFixSessionMessagesStore failSafe = failSafe(CHECK_INTERVAL);
        doThrow(new FixSessionMessagesStore.StoreException("constraint violation")).when(store).storeNextIncomingSeqNum(2);

        failSafe.storeNextIncomingSeqNum(2);

        assertThat(alertLevel(failSafe)).contains(AlertLevel.WARNING);
        await().pollDelay(CHECK_INTERVAL.multipliedBy(3)).until(() -> true);
        failSafe.storeNextIncomingSeqNum(3);
        verify(store).storeNextIncomingSeqNum(3);
        assertThat(alertLevel(failSafe)).isEmpty();
    }

    @Test
    void aZeroCheckIntervalSendsEveryCallToTheStore() {
        FailSafeFixSessionMessagesStore failSafe = failSafe(Duration.ZERO);
        doThrow(new FixSessionMessagesStore.StoreException("down")).when(store).storeMessageSent(anyLong(), any());
        available.set(false);

        failSafe.storeMessageSent(1, MESSAGE);
        await().pollDelay(CHECK_INTERVAL.multipliedBy(3)).until(() -> true);
        failSafe.storeMessageSent(2, MESSAGE);

        verify(store, times(2)).storeMessageSent(anyLong(), any());
        verify(store, never()).isUnderlyingStorageResourceAvailable();
        assertThat(alertLevel(failSafe)).contains(AlertLevel.WARNING);
    }

    @Test
    void stoppingEndsTheChecks() {
        FailSafeFixSessionMessagesStore failSafe = failSafe(CHECK_INTERVAL);
        storeBlocksThenFails();
        available.set(false);
        failSafe.storeMessageSent(1, MESSAGE);
        await().until(() -> alertLevel(failSafe).equals(Optional.of(AlertLevel.MAJOR)));

        failSafe.stop(Deadline.immediate());
        reset(store);
        await().pollDelay(CHECK_INTERVAL.multipliedBy(3)).until(() -> true);

        verify(store, never()).isUnderlyingStorageResourceAvailable();
    }
}
