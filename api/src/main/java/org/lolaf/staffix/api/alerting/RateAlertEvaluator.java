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

import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Counts events the application {@link #record() records} over a sliding window, and raises the highest level whose
 * threshold the count has reached: one failure is rarely an alert, a burst is. The alert clears once the burst has
 * aged out of the window.
 *
 * <p>Events are counted in about 60 buckets of at least a second, so the window is precise to one bucket.
 * {@link #record()} may be called from any thread, the session's included, and allocates nothing.
 */
public final class RateAlertEvaluator implements ProbeEvaluator {

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
    private final AlertLevel[] levels;
    private final int[] thresholds;
    private final String windowText;

    /**
     * @param window     how far back events are counted, at least a second
     * @param thresholds the count from which each level is raised, at least one
     */
    @Builder
    private RateAlertEvaluator(@NonNull Duration window, @Singular Map<AlertLevel, Integer> thresholds) {
        this(window, thresholds, System::nanoTime);
    }

    RateAlertEvaluator(Duration window, Map<AlertLevel, Integer> thresholds, LongSupplier nanoClock) {
        if (window.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("Rate window must be at least a second, got " + window);
        }
        if (thresholds.isEmpty()) {
            throw new IllegalArgumentException("Rate evaluator needs at least one threshold");
        }
        thresholds.forEach((level, threshold) -> {
            if (threshold <= 0) {
                throw new IllegalArgumentException("Rate threshold of " + level + " must be positive, got " + threshold);
            }
        });
        this.nanoClock = nanoClock;
        this.bucketNanos = Math.max(Duration.ofSeconds(1).toNanos(), window.toNanos() / TARGET_BUCKETS);
        this.bucketCount = (int) ((window.toNanos() + bucketNanos - 1) / bucketNanos);
        this.buckets = new AtomicLongArray(bucketCount);
        List<Map.Entry<AlertLevel, Integer>> highestFirst = thresholds.entrySet().stream()
                .sorted(Map.Entry.<AlertLevel, Integer>comparingByKey().reversed())
                .collect(Collectors.toList());
        this.levels = highestFirst.stream().map(Map.Entry::getKey).toArray(AlertLevel[]::new);
        this.thresholds = highestFirst.stream().mapToInt(Map.Entry::getValue).toArray();
        this.windowText = window.toString().substring(2).toLowerCase(Locale.ROOT);
    }

    /**
     * Counts one event now.
     */
    public void record() {
        long bucketIndex = Math.floorDiv(nanoClock.getAsLong(), bucketNanos);
        int slot = (int) Math.floorMod(bucketIndex, (long) bucketCount);
        long stamp = bucketIndex & STAMP_MASK;
        while (true) {
            long current = buckets.get(slot);
            long updated = (current & STAMP_MASK) == stamp ? current + (1L << COUNT_SHIFT) : (1L << COUNT_SHIFT) | stamp;
            if (buckets.compareAndSet(slot, current, updated)) {
                return;
            }
        }
    }

    @Override
    public Optional<Alert> evaluate() {
        long count = countInWindow();
        for (int i = 0; i < levels.length; i++) {
            if (count >= thresholds[i]) {
                return Optional.of(new Alert(levels[i], count + " in the last " + windowText + ", " + levels[i] + " from " + thresholds[i]));
            }
        }
        return Optional.empty();
    }

    /**
     * Forgets every event counted so far.
     */
    @Override
    public boolean clear() {
        for (int i = 0; i < bucketCount; i++) {
            buckets.set(i, 0L);
        }
        return true;
    }

    private long countInWindow() {
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
}
