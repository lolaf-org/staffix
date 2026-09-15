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
package org.lolaf.staffix.api.time;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Where the engine reads time, so a test can move it.
 *
 * <p>Wall clock and {@link #nanoTime()} are separate because they answer different questions: the first stamps
 * SendingTime(52), the second measures a duration and must not jump when the machine's clock is corrected.
 */
public interface Clock {

    /**
     * Get the current time, WARNING the implementation will return a thread local object that will be erased
     * next time the current thread calls this method, if you want to pass the UTCTime to another thread call the {@link UTCTime#asImmutable()}
     */
    UTCTime now();

    /**
     * Get the current time in millis since epoch
     */
    long nowEpochMillis();

    /**
     * Get the current time in nanos since epoch
     */
    long nowEpochNanos();

    /**
     * Get the current time in nanos
     */
    long nanoTime();


    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    class VoidClock implements Clock {

        private static final VoidClock INSTANCE = new VoidClock();
        private static final UTCTime VOID_UTC_TIME = new UTCTime.TimeImpl().from(0, 0);

        public static VoidClock getInstance() {
            return INSTANCE;
        }

        @Override
        public UTCTime now() {
            return VOID_UTC_TIME;
        }

        @Override
        public long nowEpochMillis() {
            return 0;
        }

        @Override
        public long nowEpochNanos() {
            return 0;
        }

        @Override
        public long nanoTime() {
            return 0;
        }
    }
}