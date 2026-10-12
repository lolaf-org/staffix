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

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Tells a fail-safe wrapper to skip the calls of a store or logger whose resource is down, rather than block the
 * session on each one. A failure only opens it once the resource's own availability check confirms the outage, so a
 * failing statement on a healthy resource never skips the calls that follow.
 */
@Slf4j
final class UnderlyingResourceBreaker {

    private static final int CLOSED = 0;
    private static final int CHECKING = 1;
    private static final int OPEN = 2;

    private final String name;
    private final BooleanSupplier resourceAvailable;
    private final Supplier<String> resourceDescription;
    private final Duration checkInterval;
    private final UnderlyingResourceChecker checker;
    private final Runnable onOpen;
    private final AtomicInteger state = new AtomicInteger(CLOSED);
    private final AtomicLong skippedCalls = new AtomicLong();
    private volatile ScheduledFuture<?> pendingCheck;

    /**
     * @param checkInterval zero disables the breaker, so every call reaches the resource
     * @param onOpen        run on the checker thread when the breaker opens
     */
    UnderlyingResourceBreaker(String name, BooleanSupplier resourceAvailable, Supplier<String> resourceDescription,
                              Duration checkInterval, UnderlyingResourceChecker checker, Runnable onOpen) {
        this.name = name;
        this.resourceAvailable = resourceAvailable;
        this.resourceDescription = resourceDescription;
        this.checkInterval = checkInterval;
        this.checker = checker;
        this.onOpen = onOpen;
    }

    boolean isOpen() {
        return state.get() == OPEN;
    }

    void callSkipped() {
        skippedCalls.incrementAndGet();
    }

    /**
     * Calls keep reaching the resource until the check this schedules confirms it is down.
     */
    void callFailed() {
        if (!checkInterval.isZero() && state.compareAndSet(CLOSED, CHECKING)) {
            scheduleCheck(Duration.ZERO);
        }
    }

    void stop() {
        state.set(CLOSED);
        ScheduledFuture<?> check = pendingCheck;
        if (check != null) {
            check.cancel(false);
        }
    }

    private void scheduleCheck(Duration delay) {
        pendingCheck = checker.schedule(this::check, delay);
    }

    private void check() {
        boolean available = isResourceAvailable();
        if (available && state.compareAndSet(OPEN, CLOSED)) {
            log.info("{}: '{}' is back, {} calls were skipped while it was down", name, resourceDescription.get(),
                    skippedCalls.getAndSet(0));
        } else if (available) {
            state.compareAndSet(CHECKING, CLOSED);
        } else if (state.compareAndSet(CHECKING, OPEN)) {
            log.warn("{}: '{}' is down, its calls are skipped until a check every {} finds it back", name,
                    resourceDescription.get(), checkInterval);
            onOpen.run();
            scheduleCheck(checkInterval);
        } else if (state.get() == OPEN) {
            scheduleCheck(checkInterval);
        }
    }

    private boolean isResourceAvailable() {
        try {
            return resourceAvailable.getAsBoolean();
        } catch (RuntimeException e) {
            log.warn("{}: availability check of '{}' failed, taken as down", name, resourceDescription.get(), e);
            return false;
        }
    }
}
