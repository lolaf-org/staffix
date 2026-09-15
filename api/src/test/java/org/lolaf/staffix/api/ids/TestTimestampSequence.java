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

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestTimestampSequence {

    private static final int COUNTER_BITS = 12;

    @Test
    void testCounterRestartsOnEveryNewMillisecond() {
        AtomicLong now = new AtomicLong(1_000_000);
        TimestampSequence sequence = new TimestampSequence(now::get, 0L, COUNTER_BITS);

        for (int millisecond = 0; millisecond < 1000; millisecond++) {
            for (int expectedCounter = 0; expectedCounter < 10; expectedCounter++) {
                long packed = sequence.next();
                assertThat(sequence.timestampOf(packed)).isEqualTo(now.get());
                assertThat(sequence.counterOf(packed)).isEqualTo(expectedCounter);
            }
            now.incrementAndGet();
        }
    }

    @Test
    void testCounterOverflowBorrowsIntoTheTimestamp() {
        long fixed = 5_000_000;
        TimestampSequence sequence = new TimestampSequence(() -> fixed, 0L, COUNTER_BITS);

        // the whole counter space of that millisecond
        for (int expectedCounter = 0; expectedCounter <= 4095; expectedCounter++) {
            long packed = sequence.next();
            assertThat(sequence.counterOf(packed)).isEqualTo(expectedCounter);
            assertThat(sequence.timestampOf(packed)).isEqualTo(fixed);
        }

        // the clock has not moved, so the next one is minted against the following millisecond
        long overflowed = sequence.next();
        assertThat(sequence.counterOf(overflowed)).isZero();
        assertThat(sequence.timestampOf(overflowed)).isEqualTo(fixed + 1);
    }

    @Test
    void testClockGoingBackwardsNeverBreaksOrdering() {
        AtomicLong now = new AtomicLong(9_000_000);
        TimestampSequence sequence = new TimestampSequence(now::get, 0L, COUNTER_BITS);

        long prev = sequence.next();
        now.addAndGet(-1_000_000);
        for (int i = 0; i < 100_000; i++) {
            long next = sequence.next();
            assertThat(next).as("value #%d after the clock stepped back", i).isGreaterThan(prev);
            prev = next;
        }
    }

    @Test
    void testEpochShiftsTheTimestamp() {
        long epoch = 4_000_000;
        long fixed = epoch + 1234;
        TimestampSequence sequence = new TimestampSequence(() -> fixed, epoch, COUNTER_BITS);

        assertThat(sequence.timestampOf(sequence.next())).isEqualTo(1234);
    }

    @Test
    void testCounterWidthIsHonoured() {
        long fixed = 7_000_000;
        TimestampSequence narrow = new TimestampSequence(() -> fixed, 0L, 3); // 8 values per millisecond

        for (int expectedCounter = 0; expectedCounter <= 7; expectedCounter++) {
            assertThat(narrow.counterOf(narrow.next())).isEqualTo(expectedCounter);
        }
        long overflowed = narrow.next();
        assertThat(narrow.counterOf(overflowed)).isZero();
        assertThat(narrow.timestampOf(overflowed)).isEqualTo(fixed + 1);
    }

    @Test
    void testStrictlyIncreasingUnderConcurrency() throws InterruptedException {
        int threads = 8;
        int perThread = 200_000;
        TimestampSequence sequence = new TimestampSequence(System::currentTimeMillis, 0L, COUNTER_BITS);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentHashMap<Long, Boolean> seen = new ConcurrentHashMap<>(threads * perThread);
        AtomicReferenceArray<Throwable> failures = new AtomicReferenceArray<>(threads);

        for (int t = 0; t < threads; t++) {
            int threadIdx = t;
            pool.execute(() -> {
                try {
                    start.await();
                    long prev = 0;
                    for (int i = 0; i < perThread; i++) {
                        long packed = sequence.next();
                        assertThat(packed).isGreaterThan(prev);
                        assertThat(seen.putIfAbsent(packed, Boolean.TRUE)).isNull();
                        prev = packed;
                    }
                } catch (Throwable e) {
                    failures.set(threadIdx, e);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        for (int t = 0; t < threads; t++) {
            assertThat(failures.get(t)).isNull();
        }
        assertThat(seen).hasSize(threads * perThread);
    }

    @Test
    void testInstancesAreIndependent() {
        long fixed = 3_000_000;
        TimestampSequence one = new TimestampSequence(() -> fixed, 0L, COUNTER_BITS);
        TimestampSequence two = new TimestampSequence(() -> fixed, 0L, COUNTER_BITS);

        // two sequences share no ordering: both start their own counter at zero
        assertThat(one.next()).isEqualTo(two.next());
        assertThat(one.next()).isEqualTo(two.next());
    }

    @Test
    void testConstructorRejectsNullClock() {
        assertThatThrownBy(() -> new TimestampSequence(null, 0L, COUNTER_BITS))
                .isInstanceOf(NullPointerException.class);
    }
}
