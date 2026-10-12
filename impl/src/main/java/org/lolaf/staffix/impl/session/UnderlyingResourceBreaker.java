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

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Tells a fail-safe wrapper to skip the calls of a store or logger whose resource is down, rather than block the
 * session on each one.
 */
interface UnderlyingResourceBreaker {

    /**
     * Never opens, so a disabled breaker costs nothing on the calls it guards.
     */
    UnderlyingResourceBreaker DISABLED = new UnderlyingResourceBreaker() {
        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public void callSkipped() {
        }

        @Override
        public void callFailed() {
        }

        @Override
        public void stop() {
        }
    };

    /**
     * @param checkInterval zero disables the breaker, so every call reaches the resource
     * @param onOpen        run on the checker thread when the breaker opens
     */
    static UnderlyingResourceBreaker of(String name, BooleanSupplier resourceAvailable, Supplier<String> resourceDescription,
                                        Duration checkInterval, UnderlyingResourceChecker checker, Runnable onOpen) {
        return checkInterval.isZero() ? DISABLED
                : new CheckingUnderlyingResourceBreaker(name, resourceAvailable, resourceDescription, checkInterval, checker, onOpen);
    }

    boolean isOpen();

    void callSkipped();

    /**
     * Calls keep reaching the resource until the check this triggers confirms it is down.
     */
    void callFailed();

    void stop();
}
