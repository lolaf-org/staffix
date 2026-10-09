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
 * A value an application publishes with its session's metrics, read at each export, such as the orders it has in
 * flight. Safe to update from any thread, and allocation free.
 */
public interface Gauge {

    /**
     * Sets the value the next export reads.
     */
    void set(double value);

    /**
     * Adds to the value, for a level that goes up and down.
     */
    void add(double delta);

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    class VoidGauge implements Gauge {
        private static final VoidGauge INSTANCE = new VoidGauge();

        public static VoidGauge getInstance() {
            return INSTANCE;
        }

        @Override
        public void set(double value) {
            // nothing to do
        }

        @Override
        public void add(double delta) {
            // nothing to do
        }
    }
}
