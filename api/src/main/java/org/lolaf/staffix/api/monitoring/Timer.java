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

import java.util.concurrent.TimeUnit;

/**
 * A simple timer to measure time
 */
public interface Timer {

    /**
     * Starts the time measurement
     */
    void start();

    /**
     * Stops the time measurement and records the duration
     */
    void stop();

    /**
     * Manually records a time measurement
     *
     * @param duration the duration of time measurement
     * @param unit     the TimeUnit of the duration
     */
    void record(long duration, TimeUnit unit);

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    class VoidTimer implements Timer {

        private static final VoidTimer INSTANCE = new VoidTimer();

        public static VoidTimer getInstance() {
            return INSTANCE;
        }

        @Override
        public void start() {
            // nothing to do
        }

        @Override
        public void stop() {
            // nothing to do
        }

        @Override
        public void record(long duration, TimeUnit unit) {
            // nothing to do
        }
    }

}