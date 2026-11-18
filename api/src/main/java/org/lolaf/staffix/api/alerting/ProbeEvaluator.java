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

/**
 * Tells whether something a probe watches is currently wrong.
 *
 * <p>Called on the probes thread, never on the one doing the work it watches, and never concurrently with itself: it
 * may keep state of its own, such as a hysteresis or a grace period, but must read what it watches in a thread-safe
 * way. An exception is reported as a {@link AlertLevel#MAJOR} alert on the probe, so a broken evaluator stays visible.
 */
@FunctionalInterface
public interface ProbeEvaluator {

    /**
     * Returns the ongoing problem, or empty when everything is fine.
     */
    Optional<Alert> evaluate();

    /**
     * Ends the ongoing alert on request, for an operator clearing it from the admin. Refused by default: a polled
     * condition would only raise it again at the next evaluation.
     *
     * @return whether the request was accepted
     */
    default boolean clear() {
        return false;
    }

    /**
     * Forgets what was measured so far, so a probe resuming does not alert on what happened before its suspension.
     * Called on the probes thread when a {@link Probe#isOnlyWhileActive()} probe resumes; does nothing by default, as
     * suits an evaluator polling a current value.
     */
    default void reset() {
    }

    /**
     * This evaluator, raising its alert only once it has been reported for {@code delay} without a break, so a
     * condition that comes and goes faster than that never alerts. Only the first raise waits: a level change while
     * already in alert, and the clear, are reported at once. The alert's {@code since} is when it was raised, after the
     * delay, so say the delay in the message.
     *
     * @param delay zero returns this evaluator unchanged
     */
    default ProbeEvaluator withRaiseDelay(Duration delay) {
        return delay.isZero() ? this : new RaiseDelayEvaluator(this, delay, System::nanoTime);
    }

    /**
     * This evaluator, clearing its alert only once it has reported nothing for {@code delay}, so a condition that comes
     * and goes keeps one alert, with its {@code since}, rather than raising a new one each time. The last alert is
     * reported while the clear waits; a level change is reported at once and a manual {@link #clear()} skips the delay.
     * Combines with {@link #withRaiseDelay(Duration)} in either order.
     *
     * @param delay zero returns this evaluator unchanged
     */
    default ProbeEvaluator withClearDelay(Duration delay) {
        return delay.isZero() ? this : new ClearDelayEvaluator(this, delay, System::nanoTime);
    }
}
