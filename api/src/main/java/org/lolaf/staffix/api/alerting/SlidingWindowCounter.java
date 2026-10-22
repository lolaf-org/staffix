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
package org.lolaf.staffix.api.alerting;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongSupplier;

/**
 * Counts over a sliding window in about 60 buckets of at least a second, so the window is precise to one bucket.
 * {@link #add(long)} may be called from any thread and allocates nothing.
 */
final class SlidingWindowCounter {

    private static final int TARGET_BUCKETS = 60;
    private static final int COUNT_SHIFT = 32;
    private static final long STAMP_MASK = 0xFFFFFFFFL;

    private final LongSupplier nanoClock;
    private final long bucketNanos;
    private final int bucketCount;
    /**
     * Per bucket, the count in the high 32 bits and the low 32 bits of the bucket's index in time, so a stale bucket
     * is recognised and restarted by a single compare-and-set.
     */
    private final AtomicLongArray buckets;
    private final String windowText;

    SlidingWindowCounter(Duration window, LongSupplier nanoClock) {
        if (window.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("Window must be at least a second, got " + window);
        }
        this.nanoClock = nanoClock;
        this.bucketNanos = Math.max(Duration.ofSeconds(1).toNanos(), window.toNanos() / TARGET_BUCKETS);
        this.bucketCount = (int) ((window.toNanos() + bucketNanos - 1) / bucketNanos);
        this.buckets = new AtomicLongArray(bucketCount);
        this.windowText = window.toString().substring(2).toLowerCase(Locale.ROOT);
    }

    /**
     * @param amount at most 2^32 - 1 per bucket, above which the count wraps
     */
    void add(long amount) {
        long bucketIndex = Math.floorDiv(nanoClock.getAsLong(), bucketNanos);
        int slot = (int) Math.floorMod(bucketIndex, (long) bucketCount);
        long stamp = bucketIndex & STAMP_MASK;
        long increment = amount << COUNT_SHIFT;
        while (true) {
            long current = buckets.get(slot);
            long updated = (current & STAMP_MASK) == stamp ? current + increment : increment | stamp;
            if (buckets.compareAndSet(slot, current, updated)) {
                return;
            }
        }
    }

    long countInWindow() {
        long currentStamp = Math.floorDiv(nanoClock.getAsLong(), bucketNanos) & STAMP_MASK;
        long count = 0;
        for (int i = 0; i < bucketCount; i++) {
            long bucket = buckets.get(i);
            long age = (currentStamp - (bucket & STAMP_MASK)) & STAMP_MASK;
            if (age < bucketCount) {
                count += bucket >>> COUNT_SHIFT;
            }
        }
        return count;
    }

    void clear() {
        for (int i = 0; i < bucketCount; i++) {
            buckets.set(i, 0L);
        }
    }

    /**
     * The window as an alert message says it, such as {@code 1m} or {@code 1h30m}.
     */
    String windowText() {
        return windowText;
    }
}
