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

/**
 * A single-threaded fixed-window rate limiter. At most {@code max} calls are admitted per
 * {@code windowNanos}-long window; the window rolls over whenever a call arrives in a later window.
 *
 * <p><b>Not thread-safe.</b> This variant is used for the inbound and {@code onMessageSent} streams,
 * which are driven exclusively by a session's single I/O thread. For the concurrently-invoked encoding
 * stream see {@link AtomicFixedWindowLimiter}.
 *
 * <p>The caller passes the timestamp (a {@code System.nanoTime()}-based value already provided by the
 * engine callback), so admission costs no clock read of its own.
 */
final class FixedWindowLimiter {

    private final long windowNanos;
    private final int max;
    private long currentWindow = Long.MIN_VALUE;
    private int count;

    FixedWindowLimiter(int max, long windowNanos) {
        this.max = max;
        this.windowNanos = windowNanos;
    }

    /**
     * @param nowNanos a {@code System.nanoTime()}-based timestamp
     * @return {@code true} if the call is admitted, {@code false} if the window's budget is exhausted
     */
    boolean tryAcquire(long nowNanos) {
        long window = Math.floorDiv(nowNanos, windowNanos);
        if (window != currentWindow) {
            currentWindow = window;
            count = 0;
        }
        if (count < max) {
            count++;
            return true;
        }
        return false;
    }
}
