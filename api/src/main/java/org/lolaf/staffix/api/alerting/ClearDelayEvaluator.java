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
package org.lolaf.staffix.api.alerting;

import lombok.NonNull;

import java.time.Duration;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Holds back the clear of another evaluator until it has reported nothing for a delay, reporting its last alert
 * meanwhile; see {@link ProbeEvaluator#withClearDelay(Duration)}, the usual way to build one.
 */
public final class ClearDelayEvaluator implements ProbeEvaluator {

    private static final long NOT_PENDING = Long.MIN_VALUE;

    private final ProbeEvaluator delegate;
    private final long delayNanos;
    private final LongSupplier nanoClock;
    private Optional<Alert> lastAlert = Optional.empty();
    private long clearPendingSinceNanos = NOT_PENDING;
    private volatile boolean holding;
    private volatile boolean clearRequested;

    /**
     * @param nanoClock a {@link System#nanoTime()} source, replaceable so a test controls time
     */
    public ClearDelayEvaluator(@NonNull ProbeEvaluator delegate, @NonNull Duration delay, @NonNull LongSupplier nanoClock) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("Clear delay must not be negative, got " + delay);
        }
        this.delegate = delegate;
        this.delayNanos = delay.toNanos();
        this.nanoClock = nanoClock;
    }

    @Override
    public Optional<Alert> evaluate() {
        Optional<Alert> result = delegate.evaluate();
        boolean clearNow = clearRequested;
        clearRequested = false;
        if (result != null && result.isPresent()) {
            lastAlert = result;
            clearPendingSinceNanos = NOT_PENDING;
            holding = false;
            return result;
        }
        if (lastAlert.isEmpty() || clearNow || clearDelayElapsed()) {
            lastAlert = Optional.empty();
            clearPendingSinceNanos = NOT_PENDING;
            holding = false;
            return result;
        }
        holding = true;
        return lastAlert;
    }

    private boolean clearDelayElapsed() {
        long now = nanoClock.getAsLong();
        if (clearPendingSinceNanos == NOT_PENDING) {
            clearPendingSinceNanos = now;
        }
        return now - clearPendingSinceNanos >= delayNanos;
    }

    /**
     * Skips the delay. Also accepted when the wrapped evaluator refuses but its condition is already normal, since
     * only this delay holds the alert then.
     */
    @Override
    public boolean clear() {
        if (delegate.clear() || holding) {
            clearRequested = true;
            return true;
        }
        return false;
    }
}
