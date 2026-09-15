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
package org.lolaf.staffix.api.ids;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The monotonic clock behind every time-ordered id in this package: a millisecond timestamp and a counter of the ids
 * already minted within that millisecond, packed into one {@code long} as {@code (millis << counterBits) | counter}
 * and advanced with a single CAS.
 *
 * <p>Packing both halves into one word is what makes the whole thing lock-free and monotonic at once: a caller cannot
 * observe a timestamp and a counter from two different states, and {@code state + 1} is a legal advance whatever the
 * clock is doing. That single expression covers all three interesting cases:
 *
 * <ul>
 *     <li><b>a new millisecond</b> - the timestamp moves up and the counter restarts at zero;</li>
 *     <li><b>the counter exhausted within a millisecond</b> - the increment overflows out of the counter bits and
 *     borrows into the timestamp, so generation never blocks and never repeats; the embedded timestamp simply runs
 *     ahead of the wall clock until the burst ends;</li>
 *     <li><b>a clock that went backwards</b> (NTP correction, leap-second smearing, a VM pause) - the same increment
 *     applies, so ordering, uniqueness and availability all hold and only timestamp accuracy suffers.</li>
 * </ul>
 *
 * <p>Every value returned by one instance is strictly greater than the one before it, across threads. Instances are
 * independent: two of them share no ordering.
 *
 * @see UUIDv7
 * @see SnowflakeId
 */
final class TimestampSequence {

    // packs (millis << counterBits) | counter for lock-free monotonic generation; owned by this instance
    private final AtomicLong state = new AtomicLong();
    private final LongSupplier clock;
    private final long epochMillis;
    private final int counterBits;
    private final long counterMask;

    /**
     * @param clock       the source of the current Unix time in milliseconds
     * @param epochMillis the Unix millisecond the timestamps count from, {@code 0} to count from the Unix epoch
     * @param counterBits the width of the counter, i.e. {@code 2^counterBits} ids per millisecond before the
     *                    overflow borrows into the timestamp
     */
    TimestampSequence(LongSupplier clock, long epochMillis, int counterBits) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.epochMillis = epochMillis;
        this.counterBits = counterBits;
        this.counterMask = (1L << counterBits) - 1;
    }

    /**
     * Returns the next packed timestamp and counter, strictly greater than the previous one. Never blocks.
     */
    long next() {
        while (true) {
            long now = clock.getAsLong() - epochMillis;
            long prev = state.get();
            long prevTs = prev >>> counterBits;
            // a new millisecond resets the counter, otherwise bump it - see the class javadoc for what that single increment covers
            long candidate = now > prevTs ? now << counterBits : prev + 1;
            if (state.compareAndSet(prev, candidate)) {
                return candidate;
            }
        }
    }

    /**
     * Returns the timestamp half of a value returned by {@link #next()}, in milliseconds since this sequence's epoch.
     */
    long timestampOf(long timestampAndCounter) {
        return timestampAndCounter >>> counterBits;
    }

    /**
     * Returns the counter half of a value returned by {@link #next()}.
     */
    long counterOf(long timestampAndCounter) {
        return timestampAndCounter & counterMask;
    }
}
