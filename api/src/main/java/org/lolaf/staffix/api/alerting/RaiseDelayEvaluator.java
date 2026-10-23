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
 * Holds back the first alert of another evaluator until it has been reported for a delay; see
 * {@link ProbeEvaluator#withRaiseDelay(Duration)}, the usual way to build one.
 */
public final class RaiseDelayEvaluator implements ProbeEvaluator {

    private static final long NOT_PENDING = Long.MIN_VALUE;

    private final ProbeEvaluator delegate;
    private final long delayNanos;
    private final LongSupplier nanoClock;
    private long pendingSinceNanos = NOT_PENDING;
    private boolean raised;

    /**
     * @param nanoClock a {@link System#nanoTime()} source, replaceable so a test controls time
     */
    public RaiseDelayEvaluator(@NonNull ProbeEvaluator delegate, @NonNull Duration delay, @NonNull LongSupplier nanoClock) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("Raise delay must not be negative, got " + delay);
        }
        this.delegate = delegate;
        this.delayNanos = delay.toNanos();
        this.nanoClock = nanoClock;
    }

    @Override
    public Optional<Alert> evaluate() {
        Optional<Alert> result = delegate.evaluate();
        if (result == null || result.isEmpty()) {
            pendingSinceNanos = NOT_PENDING;
            raised = false;
            return result;
        }
        if (raised) {
            return result;
        }
        long now = nanoClock.getAsLong();
        if (pendingSinceNanos == NOT_PENDING) {
            pendingSinceNanos = now;
        }
        if (now - pendingSinceNanos < delayNanos) {
            return Optional.empty();
        }
        raised = true;
        return result;
    }

    @Override
    public boolean clear() {
        return delegate.clear();
    }

    @Override
    public void reset() {
        pendingSinceNanos = NOT_PENDING;
        raised = false;
        delegate.reset();
    }
}
