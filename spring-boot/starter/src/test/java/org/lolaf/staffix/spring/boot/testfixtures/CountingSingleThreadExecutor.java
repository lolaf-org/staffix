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
package org.lolaf.staffix.spring.boot.testfixtures;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An application's own executor for the sessions that have no connection, counting what the engine hands it so a
 * test can tell the supplied one from the daemon thread the engine makes when none is named.
 */
public class CountingSingleThreadExecutor extends ThreadPoolExecutor {

    private final AtomicInteger executedTasksCount = new AtomicInteger();

    public CountingSingleThreadExecutor() {
        super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "test-offline-sessions");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void execute(Runnable command) {
        executedTasksCount.incrementAndGet();
        super.execute(command);
    }

    public int getExecutedTasksCount() {
        return executedTasksCount.get();
    }
}
