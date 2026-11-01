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
package org.lolaf.staffix.ha.log;

import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.ringos.idling.RetryStrategy;

import java.time.Duration;

/**
 * How long an append waits for a follower a whole log behind before the log drops it: backing off, since the wait
 * holds a FIX IO thread. Carries one thread's state, so each appender has its own.
 */
public final class LogFullWait implements RetryStrategy {

    private final long timeoutNanos;
    private final IdleStrategy idle = new BackoffIdleStrategy();
    private long startNanos;

    public LogFullWait(Duration timeout) {
        this.timeoutNanos = timeout.toNanos();
    }

    @Override
    public void reset() {
        idle.reset();
        startNanos = System.nanoTime();
    }

    @Override
    public boolean awaitRetry() {
        idle.idle();
        return System.nanoTime() - startNanos < timeoutNanos;
    }
}
