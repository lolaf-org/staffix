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
package org.lolaf.staffix.ha.spi;

import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;

/**
 * The system's time until a test takes control of it with {@link #toAdvanceableTime}.
 */
public class TestingReplicationClock implements ReplicationClock {

    private Instant wall;
    private LongSupplier nanos;
    private long advancedNanos;
    private boolean advanceable;

    public TestingReplicationClock() {
        this.wall = Instant.EPOCH;
        this.nanos = System::nanoTime;
    }

    /**
     * Wall time from {@code instant}, moved by {@link #advance} only; {@link #nanoTime()} still the system's.
     */
    public void toAdvanceableTime(Instant instant) {
        wall = instant;
        advanceable = true;
    }

    /**
     * As {@link #toAdvanceableTime(Instant)}, {@link #nanoTime()} starting at {@code nanoTime} and moved by
     * {@link #advance} only, to drive a timeout without waiting it out.
     */
    public void toAdvanceableTime(Instant instant, long nanoTime) {
        toAdvanceableTime(instant);
        nanos = () -> nanoTime + advancedNanos;
    }

    public void advance(Duration duration) {
        if (!advanceable) {
            throw new IllegalStateException("clock is not advanceable, call toAdvanceableTime(Instant) first");
        }
        wall = wall.plus(duration);
        advancedNanos += duration.toNanos();
    }

    @Override
    public long nanoTime() {
        return nanos.getAsLong();
    }

    @Override
    public Instant now() {
        return advanceable ? wall : Instant.now();
    }
}
