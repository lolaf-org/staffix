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
package org.lolaf.staffix.stores.core.async;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.Probe;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Tracks whether the wrapped resource is up, so a consumer can retry rather than discard what it could not
 * write. Its probe raises a {@code MAJOR} alert while the resource is down, until what was waiting is replayed.
 */
@Slf4j
public class UnderlyingResourceWatchContext<T extends AsyncEvent> extends Startable.SimpleStartable<UnderlyingResourceWatchContext<T>> {

    private final Consumer<T[]> asyncLogEventsToReplayConsumer;
    private final Consumer<T> asyncLogEventToReplayConsumer;
    private final BooleanSupplier underlyingStorageResourceAvailable;
    private final String name;
    private final Duration underlyingResourceWatchTaskCheckDelay;
    private final Runnable startCallback;
    private final Runnable resourceBackUpCallback;
    @Getter
    private final Probe resourceDownProbe;

    private volatile Optional<Alert> resourceDownAlert = Optional.empty();
    private ScheduledExecutorService underlyingResourceStateExecutorService;
    private ScheduledFuture<?> watchDogTask;
    private T[] asyncLogEventsToReplay;
    private T asyncLogEventToReplay;
    private String failedUnderlyingStoreDescription;

    public UnderlyingResourceWatchContext(Consumer<T[]> asyncLogEventsToReplayConsumer, Consumer<T> asyncLogEventToReplayConsumer,
                                          BooleanSupplier underlyingStorageResourceAvailable, String name,
                                          Duration underlyingResourceWatchTaskCheckDelay, Runnable startCallback,
                                          Runnable resourceBackUpCallback, String probeName, String probeDescription) {
        this.asyncLogEventsToReplayConsumer = asyncLogEventsToReplayConsumer;
        this.asyncLogEventToReplayConsumer = asyncLogEventToReplayConsumer;
        this.underlyingStorageResourceAvailable = underlyingStorageResourceAvailable;
        this.name = name;
        this.underlyingResourceWatchTaskCheckDelay = underlyingResourceWatchTaskCheckDelay;
        this.startCallback = startCallback;
        this.resourceBackUpCallback = resourceBackUpCallback;
        this.resourceDownProbe = Probe.builder()
                .name(probeName)
                .description(probeDescription)
                .evaluator(() -> resourceDownAlert)
                .build();
    }

    public void startWatchDog(T[] asyncLogEventsToReplay, T asyncLogEventToReplay, String failedUnderlyingStoreDescription) {
        this.asyncLogEventsToReplay = asyncLogEventsToReplay;
        this.asyncLogEventToReplay = asyncLogEventToReplay;
        this.failedUnderlyingStoreDescription = failedUnderlyingStoreDescription;
        start();
    }

    @Override
    protected void startMe() throws StartStopException {
        resourceDownAlert = Optional.of(Alert.major(name + " underlying resource '" + failedUnderlyingStoreDescription + "' is unavailable"));
        startCallback.run();
        underlyingResourceStateExecutorService = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, name + "-underlying-resource-state-watchdog"));
        long delay = underlyingResourceWatchTaskCheckDelay.toMillis();
        watchDogTask = underlyingResourceStateExecutorService.scheduleAtFixedRate(this::checkUnderlyingResourceState, delay, delay, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        watchDogTask.cancel(false);
        watchDogTask = null;
        underlyingResourceStateExecutorService.shutdownNow();
        underlyingResourceStateExecutorService = null;
        asyncLogEventsToReplay = null;
        asyncLogEventToReplay = null;
        failedUnderlyingStoreDescription = null;
        resourceDownAlert = Optional.empty();
    }

    private void checkUnderlyingResourceState() {
        if (underlyingStorageResourceAvailable.getAsBoolean()) {
            log.info("{} underlying resource is back up", name);
            if (asyncLogEventToReplay != null) {
                asyncLogEventToReplayConsumer.accept(asyncLogEventToReplay);
            }
            if (asyncLogEventsToReplay != null) {
                asyncLogEventsToReplayConsumer.accept(asyncLogEventsToReplay);
            }
            stop(Deadline.immediate());
            resourceBackUpCallback.run();
        }
    }
}