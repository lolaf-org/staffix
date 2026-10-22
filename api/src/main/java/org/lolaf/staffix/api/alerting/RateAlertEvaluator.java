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
import java.util.Map;
import java.util.Optional;
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

    private final SlidingWindowCounter events;
    private final AlertLevel[] levels;
    private final int[] thresholds;

    /**
     * @param window     how far back events are counted, at least a second
     * @param thresholds the count from which each level is raised, at least one
     */
    @Builder
    private RateAlertEvaluator(@NonNull Duration window, @Singular Map<AlertLevel, Integer> thresholds) {
        this(window, thresholds, System::nanoTime);
    }

    RateAlertEvaluator(Duration window, Map<AlertLevel, Integer> thresholds, LongSupplier nanoClock) {
        if (thresholds.isEmpty()) {
            throw new IllegalArgumentException("Rate evaluator needs at least one threshold");
        }
        thresholds.forEach((level, threshold) -> {
            if (threshold <= 0) {
                throw new IllegalArgumentException("Rate threshold of " + level + " must be positive, got " + threshold);
            }
        });
        this.events = new SlidingWindowCounter(window, nanoClock);
        List<Map.Entry<AlertLevel, Integer>> highestFirst = thresholds.entrySet().stream()
                .sorted(Map.Entry.<AlertLevel, Integer>comparingByKey().reversed())
                .collect(Collectors.toList());
        this.levels = highestFirst.stream().map(Map.Entry::getKey).toArray(AlertLevel[]::new);
        this.thresholds = highestFirst.stream().mapToInt(Map.Entry::getValue).toArray();
    }

    /**
     * Counts one event now.
     */
    public void record() {
        events.add(1);
    }

    /**
     * Counts {@code count} events now, as {@link #record()} that many times would.
     *
     * @throws IllegalArgumentException if {@code count} is negative
     */
    public void record(long count) {
        if (count < 0) {
            throw new IllegalArgumentException("Event count must not be negative, got " + count);
        }
        events.add(count);
    }

    @Override
    public Optional<Alert> evaluate() {
        long count = events.countInWindow();
        for (int i = 0; i < levels.length; i++) {
            if (count >= thresholds[i]) {
                return Optional.of(new Alert(levels[i], count + " in the last " + events.windowText() + ", " + levels[i] + " from " + thresholds[i]));
            }
        }
        return Optional.empty();
    }

    /**
     * Forgets every event counted so far.
     */
    @Override
    public boolean clear() {
        events.clear();
        return true;
    }
}
