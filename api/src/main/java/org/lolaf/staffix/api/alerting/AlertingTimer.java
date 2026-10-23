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
import lombok.Value;
import org.lolaf.staffix.api.monitoring.Timer;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * A {@link Timer} that is also the evaluator of a probe on its latency, so what the application times is published and
 * alerted on from one call. A level is raised when its percentile of the recordings over the window is above its
 * threshold, that is when more than {@code 1 - percentile} of them were slower: a short spike stays under that share,
 * a lasting slowdown does not. Exact, with no histogram.
 *
 * <p>{@link #start()} and {@link #stop()} belong to a single thread, as on any timer; {@link #record} may be called
 * from any thread and allocates nothing.
 */
public final class AlertingTimer implements Timer, ProbeEvaluator {

    private static final double DEFAULT_PERCENTILE = 0.95;
    private static final int DEFAULT_MIN_RECORDINGS = 20;

    private final Timer timer;
    private final LongSupplier nanoClock;
    private final SlidingWindowCounter recordings;
    private final Threshold[] highestFirst;
    private final double allowedSlowShare;
    private final int minRecordings;
    private final ProbeEvaluator evaluator;
    private long startNanos;

    /**
     * @param timer         the published timer, such as one from the session's monitoring; without one the timer only
     *                      alerts
     * @param window        how far back recordings are counted, at least a second
     * @param percentile    between 0 and 1 excluded; 0.95 by default
     * @param minRecordings below which nothing is raised, so one slow call out of one is not 100%; 20 by default
     * @param thresholds    the latency the percentile must stay within for each level, at least one
     * @param raiseDelay    see {@link ProbeEvaluator#withRaiseDelay(Duration)}; none by default
     * @param clearDelay    see {@link ProbeEvaluator#withClearDelay(Duration)}; none by default
     */
    @Builder
    private AlertingTimer(Timer timer, @NonNull Duration window, Double percentile, Integer minRecordings,
                          @Singular Map<AlertLevel, Duration> thresholds, Duration raiseDelay, Duration clearDelay) {
        this(timer, window, percentile, minRecordings, thresholds, raiseDelay, clearDelay, System::nanoTime);
    }

    AlertingTimer(Timer timer, Duration window, Double percentile, Integer minRecordings, Map<AlertLevel, Duration> thresholds,
                  Duration raiseDelay, Duration clearDelay, LongSupplier nanoClock) {
        double effectivePercentile = percentile == null ? DEFAULT_PERCENTILE : percentile;
        if (!(effectivePercentile > 0 && effectivePercentile < 1)) {
            throw new IllegalArgumentException("Percentile must be between 0 and 1 excluded, got " + percentile);
        }
        if (minRecordings != null && minRecordings < 1) {
            throw new IllegalArgumentException("Minimum recordings must be positive, got " + minRecordings);
        }
        if (thresholds.isEmpty()) {
            throw new IllegalArgumentException("Alerting timer needs at least one threshold");
        }
        thresholds.forEach((level, threshold) -> {
            if (threshold.isNegative() || threshold.isZero()) {
                throw new IllegalArgumentException("Latency threshold of " + level + " must be positive, got " + threshold);
            }
        });
        this.timer = timer == null ? Timer.VoidTimer.getInstance() : timer;
        this.nanoClock = nanoClock;
        this.recordings = new SlidingWindowCounter(window, nanoClock);
        this.highestFirst = thresholds.entrySet().stream()
                .map(threshold -> new Threshold(threshold.getKey(), threshold.getValue().toNanos(), new SlidingWindowCounter(window, nanoClock)))
                .sorted(Comparator.comparing(Threshold::getLevel).reversed())
                .toArray(Threshold[]::new);
        this.allowedSlowShare = 1 - effectivePercentile;
        this.minRecordings = minRecordings == null ? DEFAULT_MIN_RECORDINGS : minRecordings;
        this.evaluator = DelayedEvaluators.withDelays(new SlowShareEvaluator(effectivePercentile), raiseDelay, clearDelay, nanoClock);
    }

    @Override
    public void start() {
        startNanos = nanoClock.getAsLong();
    }

    @Override
    public void stop() {
        record(nanoClock.getAsLong() - startNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void record(long duration, TimeUnit unit) {
        long nanos = unit.toNanos(duration);
        recordings.add(1);
        for (Threshold threshold : highestFirst) {
            if (nanos > threshold.getNanos()) {
                threshold.getSlowRecordings().add(1);
            }
        }
        timer.record(duration, unit);
    }

    @Override
    public Optional<Alert> evaluate() {
        return evaluator.evaluate();
    }

    /**
     * Forgets the recordings counted for alerting; the published timer keeps them.
     */
    @Override
    public boolean clear() {
        return evaluator.clear();
    }

    /**
     * Forgets the recordings counted for alerting; the published timer keeps them.
     */
    @Override
    public void reset() {
        evaluator.reset();
    }

    private static String formatNanos(long nanos) {
        if (nanos % 1_000_000_000L == 0) {
            return nanos / 1_000_000_000L + "s";
        }
        if (nanos % 1_000_000L == 0) {
            return nanos / 1_000_000L + "ms";
        }
        if (nanos % 1_000L == 0) {
            return nanos / 1_000L + "us";
        }
        return nanos + "ns";
    }

    private static String formatPercent(double share) {
        double tenths = Math.round(share * 1000) / 10.0;
        return (tenths == Math.rint(tenths) ? Long.toString((long) tenths) : Double.toString(tenths)) + "%";
    }

    @Value
    private static class Threshold {
        AlertLevel level;
        long nanos;
        SlidingWindowCounter slowRecordings;
    }

    private final class SlowShareEvaluator implements ProbeEvaluator {

        private final String percentileText;

        private SlowShareEvaluator(double percentile) {
            this.percentileText = "p" + formatPercent(percentile).replace("%", "");
        }

        @Override
        public Optional<Alert> evaluate() {
            long total = recordings.countInWindow();
            if (total < minRecordings) {
                return Optional.empty();
            }
            for (Threshold threshold : highestFirst) {
                long slow = threshold.getSlowRecordings().countInWindow();
                if (slow > allowedSlowShare * total) {
                    return Optional.of(new Alert(threshold.getLevel(), percentileText + " above " + formatNanos(threshold.getNanos())
                            + ": " + formatPercent((double) slow / total) + " of " + total + " slower in the last "
                            + recordings.windowText() + ", " + threshold.getLevel()));
                }
            }
            return Optional.empty();
        }

        @Override
        public boolean clear() {
            reset();
            return true;
        }

        @Override
        public void reset() {
            recordings.clear();
            for (Threshold threshold : highestFirst) {
                threshold.getSlowRecordings().clear();
            }
        }
    }
}
