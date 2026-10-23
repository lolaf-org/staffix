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
package org.lolaf.staffix.api.monitoring;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * A count an application publishes with its session's metrics that only goes up, such as the orders it received; the
 * metrics backend turns it into a rate. A value that goes back to 0, such as orders since the logon, is a
 * {@link Gauge}. Safe to increment from any thread, and allocation free.
 */
public interface Counter {

    void increment();

    /**
     * @param amount not negative, a counter never goes down
     */
    void increment(long amount);

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    class VoidCounter implements Counter {
        private static final VoidCounter INSTANCE = new VoidCounter();

        public static VoidCounter getInstance() {
            return INSTANCE;
        }

        @Override
        public void increment() {
            // nothing to do
        }

        @Override
        public void increment(long amount) {
            // nothing to do
        }
    }
}
