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
package org.lolaf.staffix.stores.messages.jdbc;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Persists messages and sequence numbers to a relational database, for state that must outlive the engine's
 * host or be visible to something other than the engine.
 */
@Slf4j
public class JdbcMessagesStore extends Startable.SimpleStartable<FixMessagesStore> implements FixMessagesStore {

    private final JdbcMessageStoreSettings settings;
    private final Map<FixSessionId, FixSessionMessagesStore> sessionStores;
    private ScheduledExecutorService scheduledExecutorService;
    private boolean embeddedScheduledExecutorService;

    public JdbcMessagesStore(JdbcMessageStoreSettings settings) {
        this.settings = settings;
        this.sessionStores = new ConcurrentHashMap<>();
    }

    @Override
    public FixSessionMessagesStore getStore(FixSessionId fixSessionId) {
        return sessionStores.computeIfAbsent(fixSessionId,
                sid -> new JdbcFixSessionMessagesStore(settings, fixSessionId, scheduledExecutorService));
    }

    @Override
    protected void startMe() throws StartStopException {
        log.info("Starting JDBC messages store with instance id: {}", settings.getInstanceId());
        scheduledExecutorService = settings.getScheduledExecutorService();
        if (scheduledExecutorService == null && settings.getMaxMessagesPerSession() > 0) {
            scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "staffix-jdbc-store-messages-pruning");
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((t1, e) -> log.error("Uncaught exception occurred in thread {}", t1, e));
                return t;
            });
            embeddedScheduledExecutorService = true;
        }
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        log.info("Stopping JDBC messages store");
        sessionStores.clear();
        // Shutdown embedded executor if created
        if (embeddedScheduledExecutorService && scheduledExecutorService != null) {
            scheduledExecutorService.shutdown();
            try {
                if (!scheduledExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduledExecutorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduledExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

    }

    @Override
    public String getInstanceId() {
        return settings.getInstanceId();
    }
}
