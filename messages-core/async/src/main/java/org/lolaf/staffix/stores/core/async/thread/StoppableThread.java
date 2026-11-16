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
package org.lolaf.staffix.stores.core.async.thread;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * A thread running {@code loop} until stopped, which {@code loop} checks with {@link #isRunning()}.
 *
 * <p>Stopping unparks it rather than interrupting it: an interrupt landing while a queue file's chunk is mapped closes
 * the file's channel, shared with the writer.
 */
@Slf4j
@RequiredArgsConstructor
public final class StoppableThread {

    private final String name;
    private final ThreadFactory threadFactory;
    private final Runnable loop;
    private final AtomicBoolean running = new AtomicBoolean();
    @Getter
    private volatile Thread thread;

    public boolean isRunning() {
        return running.get();
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = threadFactory.newThread(loop);
            thread.setName(name);
            thread.setUncaughtExceptionHandler((t, e) -> log.error("Uncaught exception occurred in thread {}", t, e));
            thread.start();
        }
    }

    public void stop(Deadline stopDeadline) {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            LockSupport.unpark(thread);
            // Thread.join(0) waits forever, an immediate or already expired deadline must not do that
            thread.join(Math.max(stopDeadline.getRemainingTime().toMillis(), 1));
            if (thread.isAlive()) {
                log.warn("Unable to stop thread {} within given deadline {}", thread.getName(), stopDeadline);
            }
            thread = null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
