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
import java.util.concurrent.CountDownLatch;
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

class TestGuardedFixSessionMessagesStore {

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

    private GuardedFixSessionMessagesStore guarded(Duration checkInterval) {
        FixMessagesStoreSettings settings = mock(FixMessagesStoreSettings.class);
        when(settings.getResourceCheckInterval()).thenReturn(checkInterval);
        when(settings.getAlertClearDelay()).thenReturn(Duration.ZERO);
        GuardedFixSessionMessagesStore guarded = new GuardedFixSessionMessagesStore(store,
                FixSessionId.of("main", FixRegularVersion.VERSION_44, "SENDER", "TARGET"), settings, checker);
        guarded.start();
        return guarded;
    }

    private GuardedFixSessionMessagesStore notStarted() {
        FixMessagesStoreSettings settings = mock(FixMessagesStoreSettings.class);
        when(settings.getResourceCheckInterval()).thenReturn(CHECK_INTERVAL);
        when(settings.getAlertClearDelay()).thenReturn(Duration.ZERO);
        return new GuardedFixSessionMessagesStore(store, FixSessionId.of("main", FixRegularVersion.VERSION_44, "SENDER", "TARGET"),
                settings, checker);
    }

    private void storeBlocksThenFails() {
        doAnswer(invocation -> {
            Thread.sleep(BLOCKED_CALL.toMillis());
            throw new FixSessionMessagesStore.StoreException("connection timeout");
        }).when(store).storeMessageSent(anyLong(), any());
    }

    private static Optional<AlertLevel> alertLevel(GuardedFixSessionMessagesStore guarded) {
        return guarded.getProbe().getEvaluator().evaluate().map(Alert::getLevel);
    }

    @Test
    void messageWritesAreSkippedAtOnceWhileTheResourceIsDown() {
        GuardedFixSessionMessagesStore guarded = guarded(CHECK_INTERVAL);
        storeBlocksThenFails();
        available.set(false);

        guarded.storeMessageSent(1, MESSAGE);
        await().until(() -> alertLevel(guarded).equals(Optional.of(AlertLevel.MAJOR)));

        long start = System.nanoTime();
        guarded.storeMessageSent(2, MESSAGE);
        guarded.storeMessageSent(3, MESSAGE);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(BLOCKED_CALL.dividedBy(10));
        verify(store, times(1)).storeMessageSent(anyLong(), any());
    }

    @Test
    void sequenceNumberWritesReachTheStoreWhileTheResourceIsDown() {
        GuardedFixSessionMessagesStore guarded = guarded(CHECK_INTERVAL);
        storeBlocksThenFails();
        available.set(false);
        guarded.storeMessageSent(1, MESSAGE);
        await().until(() -> alertLevel(guarded).equals(Optional.of(AlertLevel.MAJOR)));

        guarded.storeNextIncomingSeqNum(3);
        guarded.storeNextOutgoingSeqNum(3);
        guarded.resetSequenceNumbers();

        verify(store).storeNextIncomingSeqNum(3);
        verify(store).storeNextOutgoingSeqNum(3);
        verify(store).resetSequenceNumbers();
    }

    @Test
    void findFailsAtOnceWhileTheResourceIsDown() {
        GuardedFixSessionMessagesStore guarded = guarded(CHECK_INTERVAL);
        storeBlocksThenFails();
        available.set(false);
        guarded.storeMessageSent(1, MESSAGE);
        await().until(() -> alertLevel(guarded).isPresent() && alertLevel(guarded).get() == AlertLevel.MAJOR);

        assertThatThrownBy(() -> guarded.find(1, 2, (seqNum, message) -> true))
                .isInstanceOf(FixSessionMessagesStore.StoreException.class)
                .hasMessageContaining("is down");
        verify(store, never()).find(anyLong(), anyLong(), any());
    }

    @Test
    void theResourceComingBackLetsWritesThroughAndClearsTheAlert() {
        GuardedFixSessionMessagesStore guarded = guarded(CHECK_INTERVAL);
        storeBlocksThenFails();
        available.set(false);
        guarded.storeMessageSent(1, MESSAGE);
        await().until(() -> alertLevel(guarded).equals(Optional.of(AlertLevel.MAJOR)));

        reset(store);
        doNothing().when(store).storeMessageSent(anyLong(), any());
        when(store.isUnderlyingStorageResourceAvailable()).thenReturn(true);
        await().untilAsserted(() -> {
            guarded.storeMessageSent(2, MESSAGE);
            verify(store).storeMessageSent(2, MESSAGE);
        });

        assertThat(alertLevel(guarded)).isEmpty();
    }

    @Test
    void aFailureOnAnAvailableResourceRaisesAWarningAndSkipsNothing() {
        GuardedFixSessionMessagesStore guarded = guarded(CHECK_INTERVAL);
        doThrow(new FixSessionMessagesStore.StoreException("constraint violation")).when(store).storeNextIncomingSeqNum(2);

        guarded.storeNextIncomingSeqNum(2);

        assertThat(alertLevel(guarded)).contains(AlertLevel.WARNING);
        await().pollDelay(CHECK_INTERVAL.multipliedBy(3)).until(() -> true);
        guarded.storeNextIncomingSeqNum(3);
        verify(store).storeNextIncomingSeqNum(3);
        assertThat(alertLevel(guarded)).isEmpty();
    }

    /**
     * A busy session: a call succeeds between the failure and the check finding the resource down, which clears the
     * warning before the breaker raises its major alert. That alert must still clear once the resource is back.
     */
    @Test
    void theAlertRaisedWhenTheResourceIsFoundDownClearsOnceItIsBack() throws Exception {
        GuardedFixSessionMessagesStore guarded = guarded(CHECK_INTERVAL);
        CountDownLatch checkStarted = new CountDownLatch(1);
        CountDownLatch checkAnswers = new CountDownLatch(1);
        available.set(false);
        when(store.isUnderlyingStorageResourceAvailable()).thenAnswer(invocation -> {
            checkStarted.countDown();
            checkAnswers.await();
            return available.get();
        });
        doThrow(new FixSessionMessagesStore.StoreException("connection timeout")).doNothing()
                .when(store).storeMessageSent(anyLong(), any());

        guarded.storeMessageSent(1, MESSAGE);
        checkStarted.await();
        guarded.storeMessageSent(2, MESSAGE);
        checkAnswers.countDown();
        await().until(() -> alertLevel(guarded).equals(Optional.of(AlertLevel.MAJOR)));

        available.set(true);
        await().untilAsserted(() -> {
            guarded.storeMessageSent(3, MESSAGE);
            verify(store).storeMessageSent(3, MESSAGE);
        });
        assertThat(alertLevel(guarded)).isEmpty();
    }

    @Test
    void aZeroCheckIntervalSendsEveryCallToTheStore() {
        GuardedFixSessionMessagesStore guarded = guarded(Duration.ZERO);
        doThrow(new FixSessionMessagesStore.StoreException("down")).when(store).storeMessageSent(anyLong(), any());
        available.set(false);

        guarded.storeMessageSent(1, MESSAGE);
        await().pollDelay(CHECK_INTERVAL.multipliedBy(3)).until(() -> true);
        guarded.storeMessageSent(2, MESSAGE);

        verify(store, times(2)).storeMessageSent(anyLong(), any());
        verify(store, never()).isUnderlyingStorageResourceAvailable();
        assertThat(alertLevel(guarded)).contains(AlertLevel.WARNING);
    }

    @Test
    void stoppingEndsTheChecks() {
        GuardedFixSessionMessagesStore guarded = guarded(CHECK_INTERVAL);
        storeBlocksThenFails();
        available.set(false);
        guarded.storeMessageSent(1, MESSAGE);
        await().until(() -> alertLevel(guarded).equals(Optional.of(AlertLevel.MAJOR)));

        guarded.stop(Deadline.immediate());
        reset(store);
        await().pollDelay(CHECK_INTERVAL.multipliedBy(3)).until(() -> true);

        verify(store, never()).isUnderlyingStorageResourceAvailable();
    }

    @Test
    void aFindIsRefusedBeforeTheStoreStartsAndAfterItStopsWithoutReachingIt() {
        GuardedFixSessionMessagesStore guarded = notStarted();

        assertThatThrownBy(() -> guarded.find(1, 2, (seqNum, message) -> true))
                .isInstanceOf(FixSessionMessagesStore.StoreException.class)
                .hasMessageContaining("is not started");
        guarded.start();
        guarded.find(1, 2, (seqNum, message) -> true);
        guarded.stop(Deadline.unlimited());
        assertThatThrownBy(() -> guarded.find(1, 2, (seqNum, message) -> true))
                .isInstanceOf(FixSessionMessagesStore.StoreException.class)
                .hasMessageContaining("is not started");

        verify(store, times(1)).find(anyLong(), anyLong(), any());
        assertThat(alertLevel(guarded)).as("not a store failure").isEmpty();
    }

    @Test
    void aStopWaitsForTheFindInProgressBeforeStoppingTheStoreEvenPastItsDeadline() throws Exception {
        GuardedFixSessionMessagesStore guarded = guarded(CHECK_INTERVAL);
        CountDownLatch finding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            finding.countDown();
            release.await();
            return null;
        }).when(store).find(anyLong(), anyLong(), any());
        Thread reader = new Thread(() -> guarded.find(1, 2, (seqNum, message) -> true));
        reader.start();
        finding.await();

        Thread stopper = new Thread(() -> guarded.stop(Deadline.immediate()));
        stopper.start();
        Thread.sleep(BLOCKED_CALL.toMillis());
        verify(store, never()).stop(any());
        assertThatThrownBy(() -> guarded.find(1, 2, (seqNum, message) -> true))
                .as("no new find once stopping").hasMessageContaining("is not started");

        release.countDown();
        stopper.join();
        reader.join();
        verify(store).stop(any());
    }

    @Test
    void theStorageIdentityIsTheWrappedStores() {
        when(store.getStorageIdentity()).thenReturn(Optional.of("shared-database"));

        assertThat(guarded(Duration.ofSeconds(1)).getStorageIdentity()).contains("shared-database");
    }
}
