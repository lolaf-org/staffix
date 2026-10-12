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

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * An evaluator the application drives itself, for a problem it learns about as it happens (a caught exception, a
 * rejected request) rather than one it can poll.
 *
 * <p>{@link #raise} and {@link #clear} may be called from any thread, the session's included: each is a few volatile
 * writes and allocates nothing. Raising again replaces the alert, even with a lower level; the probe keeps its
 * {@code since}. A raise is reported at least once, even when it is cleared or expires before the next evaluation.
 */
public final class RaisedAlertEvaluator implements ProbeEvaluator {

    private static final long NO_EXPIRY = Long.MAX_VALUE;

    private final LongSupplier nanoClock;
    private final AtomicReference<Alert> unreported = new AtomicReference<>();
    private volatile Alert raised;
    private volatile long expiresAtNanos = NO_EXPIRY;
    private Optional<Alert> lastResult = Optional.empty();

    public RaisedAlertEvaluator() {
        this(System::nanoTime);
    }

    RaisedAlertEvaluator(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /**
     * Raises an alert that lasts until {@link #clear() cleared} or replaced.
     */
    public void raise(Alert alert) {
        raise(alert, NO_EXPIRY);
    }

    /**
     * Raises an alert that ends on its own after {@code expiry} unless raised again, for a problem the application
     * cannot tell has gone, such as errors that simply stop.
     */
    public void raise(Alert alert, Duration expiry) {
        raise(alert, nanoClock.getAsLong() + expiry.toNanos());
    }

    private void raise(Alert alert, long expiresAt) {
        expiresAtNanos = expiresAt;
        raised = alert;
        unreported.set(alert);
    }

    @Override
    public boolean clear() {
        raised = null;
        return true;
    }

    @Override
    public Optional<Alert> evaluate() {
        Alert sinceLastEvaluation = unreported.getAndSet(null);
        Alert current = raised;
        if (current != null && hasExpired()) {
            current = null;
        }
        return result(current != null ? current : sinceLastEvaluation);
    }

    private boolean hasExpired() {
        long expiresAt = expiresAtNanos;
        return expiresAt != NO_EXPIRY && expiresAt - nanoClock.getAsLong() <= 0;
    }

    private Optional<Alert> result(Alert alert) {
        if (alert == null) {
            return Optional.empty();
        }
        if (lastResult.orElse(null) != alert) {
            lastResult = Optional.of(alert);
        }
        return lastResult;
    }
}
