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

    static UTCTime of(Instant instant) {
        return new ImmutableTimeImpl(instant.getEpochSecond(), instant.getNano());
    }

    static UTCTime of(long epochNanoTime) {
        long epochSeconds = epochNanoTime / NANOS_IN_A_SEC;
        int nanos = (int) (epochNanoTime - epochSeconds * NANOS_IN_A_SEC);
        return new ImmutableTimeImpl(epochSeconds, nanos);
    }

    default int getEpochDays() {
        return (int) getEpochSeconds() / SECONDS_PER_DAY;
    }

    long getEpochSeconds();

    int getNanosOfSecond();

    default Instant asInstant() {
        return Instant.ofEpochSecond(getEpochSeconds(), getNanosOfSecond());
    }

    default long toNanoOfDay() {
        long secondsOfDay = getEpochSeconds() % SECONDS_PER_DAY;
        return secondsOfDay * NANOS_IN_A_SEC + getNanosOfSecond();
    }

    /**
     * Indicates is the object is immutable or not. If not immutable, the object cannot be assigned to any vars outside
     * of its current API call or passed to any other threads as the time provider implementation that returned you this object instance may be reusing it
     */
    boolean isImmutable();

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

    @ToString
    @Getter
    @EqualsAndHashCode
    class TimeImpl implements UTCTime {

        private long epochSeconds;
        private int nanosOfSecond;

        public UTCTime from(long epochSeconds, int nanosOfSecond) {
            this.epochSeconds = epochSeconds;
            this.nanosOfSecond = nanosOfSecond;
            return this;
        }

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