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

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowLimiterTest {

    private static final long WINDOW = 1_000L;

    @Test
    void admitsUpToMaxWithinAWindowThenDenies() {
        FixedWindowLimiter limiter = new FixedWindowLimiter(3, WINDOW);

        // All timestamps fall in window 0 (floorDiv(ts, 1000) == 0 for ts in [0, 999]).
        assertThat(limiter.tryAcquire(0)).isTrue();
        assertThat(limiter.tryAcquire(100)).isTrue();
        assertThat(limiter.tryAcquire(999)).isTrue();
        assertThat(limiter.tryAcquire(500)).isFalse();
        assertThat(limiter.tryAcquire(999)).isFalse();
    }

    @Test
    void budgetResetsWhenTheWindowRollsOver() {
        FixedWindowLimiter limiter = new FixedWindowLimiter(2, WINDOW);

        assertThat(limiter.tryAcquire(0)).isTrue();
        assertThat(limiter.tryAcquire(10)).isTrue();
        assertThat(limiter.tryAcquire(20)).isFalse(); // window 0 exhausted

        assertThat(limiter.tryAcquire(WINDOW)).isTrue();       // window 1 -> fresh budget
        assertThat(limiter.tryAcquire(WINDOW + 1)).isTrue();
        assertThat(limiter.tryAcquire(WINDOW + 2)).isFalse();  // window 1 exhausted

        assertThat(limiter.tryAcquire(5 * WINDOW)).isTrue();   // skipping windows still resets
    }

    @Test
    void anyWindowChangeResetsIncludingABackwardJump() {
        FixedWindowLimiter limiter = new FixedWindowLimiter(1, WINDOW);

        assertThat(limiter.tryAcquire(2 * WINDOW)).isTrue();  // window 2
        assertThat(limiter.tryAcquire(2 * WINDOW + 1)).isFalse();
        // A timestamp in an earlier window is a different window, so the counter resets and admits again.
        assertThat(limiter.tryAcquire(0)).isTrue();           // window 0
    }

    @Test
    void handlesNegativeTimestamps() {
        FixedWindowLimiter limiter = new FixedWindowLimiter(1, WINDOW);

        // floorDiv(-1000, 1000) == -1 and floorDiv(-1, 1000) == -1 -> same window.
        assertThat(limiter.tryAcquire(-1000)).isTrue();
        assertThat(limiter.tryAcquire(-1)).isFalse();
        // floorDiv(0, 1000) == 0 -> next window.
        assertThat(limiter.tryAcquire(0)).isTrue();
    }

    @Test
    void maxOfZeroAlwaysDenies() {
        FixedWindowLimiter limiter = new FixedWindowLimiter(0, WINDOW);

        assertThat(limiter.tryAcquire(0)).isFalse();
        assertThat(limiter.tryAcquire(WINDOW)).isFalse();
    }
}
