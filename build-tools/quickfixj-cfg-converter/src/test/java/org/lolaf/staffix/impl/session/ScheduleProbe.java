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

import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;

import java.time.Instant;

/**
 * Asks the engine's own schedule whether a session is up at an instant; it lives in the engine's package because
 * that question is package private.
 */
public final class ScheduleProbe {

    private ScheduleProbe() {
    }

    public static boolean isWithinSessionTime(FixSessionSettings settings, Instant instant) {
        FixSessionScheduleManager manager = new FixSessionScheduleManager(settings, new FixedClock(instant));
        return !manager.isEnabled() || manager.isWithinSessionTime();
    }

    private static final class FixedClock implements Clock {
        private final Instant instant;

        FixedClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public UTCTime now() {
            return UTCTime.of(instant);
        }

        @Override
        public long nowEpochMillis() {
            return instant.toEpochMilli();
        }

        @Override
        public long nowEpochNanos() {
            return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
        }

        @Override
        public long nanoTime() {
            return nowEpochNanos();
        }
    }
}
