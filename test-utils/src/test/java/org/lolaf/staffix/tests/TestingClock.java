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
package org.lolaf.staffix.tests;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public class TestingClock implements Clock {

    private Clock clock = ClockImpl.INSTANCE;

    public void toFixedTime(Instant instant) {
        clock = new FixedClock(instant);
    }

    /**
     * Switch to a manually controlled wall-clock starting at {@code instant}. Only the wall-clock ({@link #now()} /
     * {@link #nowEpochMillis()}) is controlled; {@link #nanoTime()} still tracks real time so monotonic scheduling keeps
     * working. Move time forward with {@link #advance(Duration)}. Useful for deterministically driving session-schedule
     * transitions instead of sleeping until the real wall-clock crosses a window boundary.
     */
    public void toAdvanceableTime(Instant instant) {
        clock = new AdvanceableClock(instant, System::nanoTime);
    }

    /**
     * As {@link #toAdvanceableTime(Instant)}, {@link #nanoTime()} included: it starts at {@code nanoTime} and moves
     * with {@link #advance(Duration)} only, to drive a timeout measured on it without waiting it out.
     */
    public void toAdvanceableTime(Instant instant, long nanoTime) {
        clock = new AdvanceableClock(instant, nanoTime);
    }

    /**
     * Move the manually controlled wall-clock forward. Requires {@link #toAdvanceableTime(Instant)} to have been called.
     */
    public void advance(Duration duration) {
        if (!(clock instanceof AdvanceableClock)) {
            throw new IllegalStateException("clock is not advanceable, call toAdvanceableTime(Instant) first");
        }
        ((AdvanceableClock) clock).advance(duration);
    }

    public void toNonFixedTime() {
        clock = ClockImpl.INSTANCE;
    }

    @Override
    public UTCTime now() {
        return clock.now();
    }

    @Override
    public long nowEpochMillis() {
        return clock.nowEpochMillis();
    }

    @Override
    public long nowEpochNanos() {
        return clock.nowEpochNanos();
    }

    @Override
    public long nanoTime() {
        return clock.nanoTime();
    }

    public static class FixedClock implements Clock {

        private final Instant fixedInstant;

        public FixedClock(Instant instant) {
            this.fixedInstant = instant;
        }

        @Override
        public UTCTime now() {
            return UTCTime.of(fixedInstant);
        }

        @Override
        public long nowEpochMillis() {
            return fixedInstant.toEpochMilli();
        }

        @Override
        public long nowEpochNanos() {
            return UTCTime.of(fixedInstant).toEpochNanos();
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    }

    public static class AdvanceableClock implements Clock {

        private final AtomicReference<Instant> current;
        private final LongSupplier nanoTime;
        /**
         * Null when {@link #nanoTime()} is not advanced.
         */
        private final AtomicLong advancedNanoTime;

        public AdvanceableClock(Instant instant, LongSupplier nanoTime) {
            this.current = new AtomicReference<>(instant);
            this.nanoTime = nanoTime;
            this.advancedNanoTime = null;
        }

        public AdvanceableClock(Instant instant, long nanoTime) {
            this.current = new AtomicReference<>(instant);
            this.advancedNanoTime = new AtomicLong(nanoTime);
            this.nanoTime = advancedNanoTime::get;
        }

        public void advance(Duration duration) {
            current.updateAndGet(instant -> instant.plus(duration));
            if (advancedNanoTime != null) {
                advancedNanoTime.addAndGet(duration.toNanos());
            }
        }

        @Override
        public UTCTime now() {
            return UTCTime.of(current.get());
        }

        @Override
        public long nowEpochMillis() {
            return current.get().toEpochMilli();
        }

        @Override
        public long nowEpochNanos() {
            return UTCTime.of(current.get()).toEpochNanos();
        }

        @Override
        public long nanoTime() {
            return nanoTime.getAsLong();
        }
    }

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class ClockImpl implements Clock {

        public static final ClockImpl INSTANCE = new ClockImpl();


        @Override
        public UTCTime now() {
            return UTCTime.of(Instant.now());
        }

        @Override
        public long nowEpochMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public long nowEpochNanos() {
            return UTCTime.of(Instant.now()).toEpochNanos();
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    }
}
