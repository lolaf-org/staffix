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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Pretouches every added queue on a fixed interval.
 *
 * <p>A thread of its own rather than a reader's: a reader can spend a long time inside a slow wrapped store, and the
 * pages ahead of a busy writer would run out meanwhile.
 */
@Slf4j
public final class PretouchThread implements Runnable {

    private final String threadName;
    private final ThreadFactory threadFactory;
    private final long intervalNanos;
    private final AtomicBoolean running;
    private final Map<Object, Pretouchable> queuesByOwner;
    private volatile Pretouchable[] queuesArray;
    private volatile Thread thread;

    /**
     * An {@code interval} of zero turns it off: {@link #start()} then starts no thread.
     */
    public PretouchThread(String instanceId, ThreadFactory threadFactory, Duration interval) {
        this.threadName = "Staffix-async-queue-pretoucher-" + instanceId;
        this.threadFactory = threadFactory;
        this.intervalNanos = interval.toNanos();
        this.running = new AtomicBoolean();
        this.queuesByOwner = new IdentityHashMap<>();
        this.queuesArray = new Pretouchable[0];
    }

    /**
     * Pretouches {@code queue} until {@code owner}, compared by identity, is removed. An owner added again keeps its
     * first queue; a queue added by two owners is pretouched once per interval, until both are removed.
     */
    public synchronized void add(Object owner, Pretouchable queue) {
        queuesByOwner.putIfAbsent(owner, queue);
        publishQueues();
    }

    public synchronized void remove(Object owner) {
        queuesByOwner.remove(owner);
        publishQueues();
    }

    private void publishQueues() {
        queuesArray = queuesByOwner.values().stream().distinct().toArray(Pretouchable[]::new);
    }

    public void start() {
        if (intervalNanos > 0 && running.compareAndSet(false, true)) {
            thread = threadFactory.newThread(this);
            thread.setName(threadName);
            thread.setUncaughtExceptionHandler((t, e) -> log.error("Uncaught exception occurred in thread {}", t, e));
            thread.start();
        }
    }

    public void stop(Deadline stopDeadline) {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            // not an interrupt: it would close the queue file's channel, shared with the writer, if it lands while
            // a new chunk is mapped
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

    @Override
    public void run() {
        while (running.get()) {
            for (Pretouchable queue : queuesArray) {
                queue.pretouch();
            }
            LockSupport.parkNanos(intervalNanos);
        }
    }
}
