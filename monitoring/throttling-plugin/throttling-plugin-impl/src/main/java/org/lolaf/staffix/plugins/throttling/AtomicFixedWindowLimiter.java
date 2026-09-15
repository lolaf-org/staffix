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

import java.util.concurrent.atomic.AtomicLong;

/**
 * A lock-free fixed-window rate limiter for the encoding stream, whose callbacks may be invoked
 * concurrently from multiple application threads (see the threading contract on
 * {@code FixSessionPlugin}).
 *
 * <p>The window index and the in-window count are packed into a single {@link AtomicLong} and advanced
 * with a CAS loop, so no lock is taken. The window index occupies the high {@value #COUNT_BITS}+ bits and
 * the count the low {@value #COUNT_BITS} bits (counts beyond {@link #COUNT_MASK} per window are treated as
 * exhausted, which is far above any realistic per-window budget).
 */
final class AtomicFixedWindowLimiter {

    private static final int COUNT_BITS = 24;
    private static final long COUNT_MASK = (1L << COUNT_BITS) - 1;

    private final long windowNanos;
    private final int max;
    private final AtomicLong state = new AtomicLong(Long.MIN_VALUE);

    AtomicFixedWindowLimiter(int max, long windowNanos) {
        // Never admit more than the count field can hold within a single window.
        this.max = (int) Math.min(max, COUNT_MASK);
        this.windowNanos = windowNanos;
    }

    /**
     * @param nowNanos a {@code System.nanoTime()}-based timestamp
     * @return {@code true} if the call is admitted, {@code false} if the window's budget is exhausted
     */
    boolean tryAcquire(long nowNanos) {
        long window = Math.floorDiv(nowNanos, windowNanos) & (~0L >>> COUNT_BITS);
        while (true) {
            long s = state.get();
            long w = s >>> COUNT_BITS;
            long newState;
            if (w != window) {
                if (max < 1) {
                    return false;                            // fresh window but no budget at all
                }
                newState = (window << COUNT_BITS) | 1L;      // first call of a fresh window
            } else if ((s & COUNT_MASK) < max) {
                newState = s + 1L;                            // still within budget (count is the low bits)
            } else {
                return false;                                // budget exhausted, leave state untouched
            }
            if (state.compareAndSet(s, newState)) {
                return true;
            }
        }
    }
}
