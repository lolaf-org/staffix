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
package org.lolaf.staffix.ha.spi;

import java.time.Instant;

/**
 * The replication's time, replaceable so tests control it.
 */
public interface ReplicationClock {

    ReplicationClock SYSTEM = new ReplicationClock() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public Instant now() {
            return Instant.now();
        }
    };

    /**
     * Monotonic, for timeouts and intervals.
     */
    long nanoTime();

    /**
     * Wall time, for what a status reports.
     */
    Instant now();
}
