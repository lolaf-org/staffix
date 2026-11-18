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

import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.stores.core.async.thread.StoppableThread;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.LockSupport;

/**
 * Pretouches every added queue on a fixed interval.
 *
 * <p>A thread of its own rather than a reader's: a reader can spend a long time inside a slow wrapped store, and the
 * pages ahead of a busy writer would run out meanwhile.
 */
public final class PretouchThread implements Runnable {

    private final long intervalNanos;
    private final StoppableThread thread;
    private final Map<Object, Pretouchable> queuesByOwner;
    private volatile Pretouchable[] queuesArray;

    public PretouchThread(String instanceId, ThreadFactory threadFactory, Duration interval) {
        this.intervalNanos = interval.toNanos();
        this.thread = new StoppableThread("Staffix-async-queue-pretoucher-" + instanceId, threadFactory, this);
        this.queuesByOwner = new IdentityHashMap<>();
        this.queuesArray = new Pretouchable[0];
    }

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
        thread.start();
    }

    public void stop(Deadline stopDeadline) {
        thread.stop(stopDeadline);
    }

    @Override
    public void run() {
        while (thread.isRunning()) {
            for (Pretouchable queue : queuesArray) {
                queue.pretouch();
            }
            LockSupport.parkNanos(intervalNanos);
        }
    }
}
