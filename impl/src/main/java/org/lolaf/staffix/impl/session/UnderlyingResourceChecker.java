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
package org.lolaf.staffix.impl.session;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The engine's one thread checking whether the stores and loggers found failing are back, so a check, which can
 * block for the resource's own timeout, never runs on a session thread. Created on the first check, since most
 * engines never need it.
 */
@RequiredArgsConstructor
public class UnderlyingResourceChecker {

    private final String engineId;
    private ScheduledExecutorService scheduler;
    private boolean stopped;

    /**
     * Null once stopped.
     */
    synchronized ScheduledFuture<?> schedule(Runnable check, Duration delay) {
        if (stopped) {
            return null;
        }
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "Staffix-resource-checker-" + engineId);
                thread.setDaemon(true);
                return thread;
            });
        }
        return scheduler.schedule(check, delay.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * Interrupts a check in progress rather than wait for it: it may be blocked on a resource that is down.
     */
    public synchronized void stop() {
        stopped = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
