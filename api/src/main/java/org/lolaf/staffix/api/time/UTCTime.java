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

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.Value;

import java.time.Instant;

/**
 * A point in time as FIX writes it, to nanosecond resolution.
 *
 * <p>Not {@link java.time.Instant} because the message path cannot allocate one per timestamp field. An instance
 * is mutable and reused by default; {@link #asImmutable()} is what makes one safe to keep past the callback it
 * arrived in, and {@link #isImmutable()} says which kind you are holding.
 */
public interface UTCTime extends Comparable<UTCTime> {

    int SECONDS_PER_DAY = 60 * 60 * 24;
    int MILLIS_IN_A_SEC = 1000;
    int NANOS_IN_A_MILLIS = 1000_000;
    long NANOS_IN_A_SEC = 1_000_000_000L;

    /**
     * An immutable time, safe to keep.
     */
    static UTCTime of(Instant instant) {
        return new ImmutableTimeImpl(instant.getEpochSecond(), instant.getNano());
    }

    /**
     * An immutable time from nanoseconds since the epoch.
     */
    static UTCTime of(long epochNanoTime) {
        long epochSeconds = epochNanoTime / NANOS_IN_A_SEC;
        int nanos = (int) (epochNanoTime - epochSeconds * NANOS_IN_A_SEC);
        return new ImmutableTimeImpl(epochSeconds, nanos);
    }

    /**
     * Whole days since the epoch, the date part of the time.
     */
    default int getEpochDays() {
        return (int) (getEpochSeconds() / SECONDS_PER_DAY);
    }

    /**
     * Whole seconds since the epoch.
     */
    long getEpochSeconds();

    /**
     * The fraction of the second, in nanoseconds.
     */
    int getNanosOfSecond();

    /**
     * The same time as an {@link Instant}, which allocates; keep it off the message path.
     */
    default Instant asInstant() {
        return Instant.ofEpochSecond(getEpochSeconds(), getNanosOfSecond());
    }

    /**
     * Nanoseconds since midnight UTC, the time part of the time.
     */
    default long toNanoOfDay() {
        long secondsOfDay = getEpochSeconds() % SECONDS_PER_DAY;
        return secondsOfDay * NANOS_IN_A_SEC + getNanosOfSecond();
    }

    /**
     * Whether this instance is immutable. A mutable one may be reused by whoever handed it over, so it must not be
     * kept past the current call nor passed to another thread.
     */
    boolean isImmutable();

    /**
     * An immutable copy, or this instance if it already is one.
     */
    UTCTime asImmutable();

    default long toEpochMillis() {
        long millis = getEpochSeconds() * MILLIS_IN_A_SEC;
        return millis + (getNanosOfSecond() / NANOS_IN_A_MILLIS);
    }

    default long toEpochNanos() {
        return getEpochSeconds() * NANOS_IN_A_SEC + getNanosOfSecond();
    }

    @Override
    default int compareTo(UTCTime other) {
        int timeCompare = Long.compare(getEpochSeconds(), other.getEpochSeconds());
        if (timeCompare == 0) {
            return Integer.compare(getNanosOfSecond(), other.getNanosOfSecond());
        }
        return timeCompare;
    }

    /**
     * The mutable time, reused through {@code from} so the message path allocates nothing.
     */
    @ToString
    @Getter
    @EqualsAndHashCode
    class TimeImpl implements UTCTime {

        private long epochSeconds;
        private int nanosOfSecond;

        /**
         * Sets this instance to the given time and returns it.
         */
        public UTCTime from(long epochSeconds, int nanosOfSecond) {
            this.epochSeconds = epochSeconds;
            this.nanosOfSecond = nanosOfSecond;
            return this;
        }

        /**
         * Sets this instance to the given time and returns it.
         */
        public UTCTime from(Instant now) {
            return from(now.getEpochSecond(), now.getNano());
        }

        @Override
        public UTCTime asImmutable() {
            return new ImmutableTimeImpl(epochSeconds, nanosOfSecond);
        }

        @Override
        public boolean isImmutable() {
            return false;
        }

    }

    /**
     * The immutable time, safe to keep and share.
     */
    @Value
    class ImmutableTimeImpl implements UTCTime {

        long epochSeconds;
        int nanosOfSecond;

        public static UTCTime from(long epochSeconds, int nanosOfSecond) {
            return new ImmutableTimeImpl(epochSeconds, nanosOfSecond);
        }

        public static UTCTime from(Instant now) {
            return from(now.getEpochSecond(), now.getNano());
        }

        @Override
        public UTCTime asImmutable() {
            return this;
        }

        @Override
        public boolean isImmutable() {
            return true;
        }
    }
}