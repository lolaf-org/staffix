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

import java.util.Optional;

/**
 * Tells whether something a probe watches is currently wrong.
 *
 * <p>Called on the engine's probes thread, never on the session's, and never concurrently with itself: it may keep
 * state of its own, such as a hysteresis or a grace period, but must read what it watches in a thread-safe way. An
 * exception is reported as a {@link AlertLevel#MAJOR} alert on the probe, so a broken evaluator stays visible.
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
}
