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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TestUnderlyingResourceBreaker {

    private static final Duration CHECK_INTERVAL = Duration.ofMillis(50);

    private final UnderlyingResourceChecker checker = new UnderlyingResourceChecker("test-engine");
    private final AtomicBoolean available = new AtomicBoolean(true);
    private final AtomicInteger checks = new AtomicInteger();
    private final AtomicInteger opened = new AtomicInteger();

    @AfterEach
    void stopChecker() {
        checker.stop();
    }

    private UnderlyingResourceBreaker breaker(Duration checkInterval) {
        return new UnderlyingResourceBreaker("Test store", () -> {
            checks.incrementAndGet();
            return available.get();
        }, () -> "test resource", checkInterval, checker, opened::incrementAndGet);
    }

    @Test
    void aFailureOnAnAvailableResourceLeavesItClosed() {
        UnderlyingResourceBreaker breaker = breaker(CHECK_INTERVAL);

        breaker.callFailed();

        await().until(() -> checks.get() == 1);
        await().pollDelay(CHECK_INTERVAL.multipliedBy(3)).until(() -> true);
        assertThat(breaker.isOpen()).isFalse();
        assertThat(checks).hasValue(1);
        assertThat(opened).hasValue(0);
    }

    @Test
    void aFailureOnADownResourceOpensItUntilItIsBack() {
        UnderlyingResourceBreaker breaker = breaker(CHECK_INTERVAL);
        available.set(false);

        breaker.callFailed();

        await().until(breaker::isOpen);
        assertThat(opened).hasValue(1);
        await().until(() -> checks.get() >= 3);
        assertThat(breaker.isOpen()).isTrue();

        available.set(true);

        await().until(() -> !breaker.isOpen());
        int checksWhenClosed = checks.get();
        await().pollDelay(CHECK_INTERVAL.multipliedBy(3)).until(() -> true);
        assertThat(checks).hasValue(checksWhenClosed);
        assertThat(opened).hasValue(1);
    }

    @Test
    void failuresWhileACheckIsPendingScheduleNoOtherCheck() throws InterruptedException {
        CountDownLatch checkStarted = new CountDownLatch(1);
        CountDownLatch releaseCheck = new CountDownLatch(1);
        UnderlyingResourceBreaker breaker = new UnderlyingResourceBreaker("Test store", () -> {
            checks.incrementAndGet();
            checkStarted.countDown();
            awaitQuietly(releaseCheck);
            return true;
        }, () -> "test resource", CHECK_INTERVAL, checker, opened::incrementAndGet);

        breaker.callFailed();
        checkStarted.await();
        breaker.callFailed();
        breaker.callFailed();
        releaseCheck.countDown();

        await().pollDelay(CHECK_INTERVAL.multipliedBy(3)).until(() -> true);
        assertThat(checks).hasValue(1);
    }

    @Test
    void aZeroIntervalNeverChecksNorOpens() {
        UnderlyingResourceBreaker breaker = breaker(Duration.ZERO);
        available.set(false);

        breaker.callFailed();

        await().pollDelay(CHECK_INTERVAL.multipliedBy(3)).until(() -> true);
        assertThat(checks).hasValue(0);
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void aThrowingAvailabilityCheckCountsAsDown() {
        UnderlyingResourceBreaker breaker = new UnderlyingResourceBreaker("Test store", () -> {
            throw new IllegalStateException("check failed");
        }, () -> "test resource", CHECK_INTERVAL, checker, opened::incrementAndGet);

        breaker.callFailed();

        await().until(breaker::isOpen);
    }

    @Test
    void stoppingClosesItAndEndsTheChecks() {
        UnderlyingResourceBreaker breaker = breaker(CHECK_INTERVAL);
        available.set(false);
        breaker.callFailed();
        await().until(breaker::isOpen);

        breaker.stop();

        assertThat(breaker.isOpen()).isFalse();
        int checksWhenStopped = checks.get();
        await().pollDelay(CHECK_INTERVAL.multipliedBy(3)).until(() -> true);
        assertThat(checks.get()).isLessThanOrEqualTo(checksWhenStopped + 1);
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void aStoppedCheckerSchedulesNothing() {
        UnderlyingResourceBreaker breaker = breaker(CHECK_INTERVAL);
        available.set(false);
        checker.stop();

        breaker.callFailed();

        await().pollDelay(CHECK_INTERVAL.multipliedBy(3)).until(() -> true);
        assertThat(checks).hasValue(0);
        assertThat(breaker.isOpen()).isFalse();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
