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
package org.lolaf.staffix.stores.core.async.pretoucher;

/**
 * A queue's recent write rate, measured from its write position: it rises at once to a faster sample, so a burst does
 * not outrun the pages touched ahead, and decays over {@link #DECAY_NANOS}.
 */
final class WriteRate {

    /**
     * Shorter samples are merged into the next one: over a few microseconds, a single append reads as a burst.
     */
    static final long MIN_SAMPLE_NANOS = 1_000_000;
    /**
     * Slow, since a pause between bursts must not shrink the distance the next burst needs, and pages touched ahead
     * of a slower writer cost nothing more than memory provided early.
     */
    static final long DECAY_NANOS = 1_000_000_000;

    private boolean sampling;
    private long sampleStartPosition;
    private long sampleStartNanos;
    private double bytesPerNano;

    /**
     * Starts a new sample at {@code position}, keeping the rate: for a new file, whose positions start over.
     */
    void restartAt(long position, long nowNanos) {
        sampling = true;
        sampleStartPosition = position;
        sampleStartNanos = nowNanos;
    }

    void update(long position, long nowNanos) {
        if (!sampling || position < sampleStartPosition) {
            restartAt(position, nowNanos);
            return;
        }
        long elapsedNanos = nowNanos - sampleStartNanos;
        if (elapsedNanos < MIN_SAMPLE_NANOS) {
            return;
        }
        double sample = (double) (position - sampleStartPosition) / elapsedNanos;
        double decayed = bytesPerNano + Math.min(1, (double) elapsedNanos / DECAY_NANOS) * (sample - bytesPerNano);
        bytesPerNano = Math.max(sample, decayed);
        restartAt(position, nowNanos);
    }

    long bytesIn(long nanos) {
        return (long) (bytesPerNano * nanos);
    }
}
