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
package org.lolaf.staffix.plugins.throttling;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AtomicFixedWindowLimiterTest {

    private static final long WINDOW = 1_000L;

    @Test
    void admitsUpToMaxWithinAWindowThenDenies() {
        AtomicFixedWindowLimiter limiter = new AtomicFixedWindowLimiter(3, WINDOW);

        assertThat(limiter.tryAcquire(0)).isTrue();
        assertThat(limiter.tryAcquire(100)).isTrue();
        assertThat(limiter.tryAcquire(999)).isTrue();
        assertThat(limiter.tryAcquire(500)).isFalse();
        assertThat(limiter.tryAcquire(999)).isFalse();
    }

    @Test
    void budgetResetsWhenTheWindowRollsOver() {
        AtomicFixedWindowLimiter limiter = new AtomicFixedWindowLimiter(2, WINDOW);

        assertThat(limiter.tryAcquire(0)).isTrue();
        assertThat(limiter.tryAcquire(10)).isTrue();
        assertThat(limiter.tryAcquire(20)).isFalse();

        assertThat(limiter.tryAcquire(WINDOW)).isTrue();
        assertThat(limiter.tryAcquire(WINDOW + 1)).isTrue();
        assertThat(limiter.tryAcquire(WINDOW + 2)).isFalse();

        assertThat(limiter.tryAcquire(5 * WINDOW)).isTrue();
    }

    @Test
    void handlesNegativeTimestamps() {
        AtomicFixedWindowLimiter limiter = new AtomicFixedWindowLimiter(1, WINDOW);

        assertThat(limiter.tryAcquire(-1000)).isTrue();
        assertThat(limiter.tryAcquire(-1)).isFalse(); // same window (-1) as -1000
        assertThat(limiter.tryAcquire(0)).isTrue();   // window 0
    }

    @Test
    void maxOfZeroAlwaysDenies() {
        AtomicFixedWindowLimiter limiter = new AtomicFixedWindowLimiter(0, WINDOW);

        assertThat(limiter.tryAcquire(0)).isFalse();
        assertThat(limiter.tryAcquire(WINDOW)).isFalse();
    }

    @Test
    void neverAdmitsMoreThanMaxWithinAWindowUnderConcurrency() throws Exception {
        int threads = 8;
        int attemptsPerThread = 50_000;
        int max = 1000;
        AtomicFixedWindowLimiter limiter = new AtomicFixedWindowLimiter(max, WINDOW);
        AtomicInteger admitted = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < attemptsPerThread; i++) {
                        if (limiter.tryAcquire(0)) { // all timestamps map to window 0
                            admitted.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown(); // release all threads at once to maximise contention
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // Far more attempts than the budget, all in the same window: exactly max must be admitted, no more.
        assertThat(admitted.get()).isEqualTo(max);
    }
}
