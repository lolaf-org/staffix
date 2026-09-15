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

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import net.openhft.chronicle.queue.RollCycle;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.ringos.idling.TimerSlackAwareBackoffIdleStrategy;
import org.lolaf.ringos.threading.FastThreadLocalThread;
import org.lolaf.staffix.api.session.FixSessionId;

import java.time.Duration;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The queue's location and size, the consumer threads, and the thresholds at which queue depth is reported.
 */
@Getter
@SuperBuilder(toBuilder = true)
public class AsyncStoreSettings {

    /**
     * Thread factory for the reader threads, if you provide one please try to return a {@link FastThreadLocalThread} to improve perfs
     */
    @Builder.Default
    private final ThreadFactory threadFactory = FastThreadLocalThread::new;
    /**
     * Directory where the async queues for the FIX sessions are stored
     */
    private String asyncQueueDirectory;
    /**
     * A configurable {@link Function} that determines the {@link RollCycle} to be used
     * for a given {@link FixSessionId}. The roll cycle defines how data should roll over
     * (e.g., based on time or size) in the underlying queue or store.
     * <p>
     * This variable is initialized with a default implementation that maps each session ID
     * to {@link RollCycles#FAST_HOURLY}, which rolls data hourly for high-throughput scenarios.
     * <p>
     * Can be customized to provide different roll cycle behavior per session as needed.
     */
    @Builder.Default
    private Function<FixSessionId, RollCycle> rollCycleProvider = sid -> RollCycles.FAST_HOURLY;
    /**
     * Consumer to allow custom chronicle queue builder tuning for a given fix session, called when the async store is started
     */
    @Builder.Default
    private BiConsumer<FixSessionId, SingleChronicleQueueBuilder> builderConfigurer = (sid, builder) -> {
        // nothing to do by default
    };
    /**
     * Maximum of events process by the async store in case of batching API usage, set to greater than zero to enable batching usage
     */
    @Builder.Default
    private int eventsBatching = 0;
    /**
     * Batching events flush interval, will trigger batching events processing even if the defined {@link #eventsBatching} count is not reached.
     * This defines your maximal latency before processing a set of batched events
     */
    @Builder.Default
    private Duration batchingFlushInterval = Duration.ofMillis(100);
    /**
     * Number of thread to read store events from persistent queues, depends on your number of active FIX sessions and the throughput of your wrapped store
     */
    @Builder.Default
    private int readerThreadsCount = 1;
    /**
     * Idle strategy when reader thread has no events to process
     */
    @Builder.Default
    private Supplier<IdleStrategy> readerThreadsIdleStrategy = () -> new TimerSlackAwareBackoffIdleStrategy(
            BackoffIdleStrategy.DEFAULT_MAX_SPINS, BackoffIdleStrategy.DEFAULT_MAX_YIELDS, TimeUnit.MICROSECONDS.toNanos(100), TimeUnit.MILLISECONDS.toNanos(5));

    /**
     * Max events processing per store for a reader thread on each loop in non batching mode
     */
    @Builder.Default
    private int readerThreadMaxEventsProcessingPerStore = 1024;

    /**
     * Queue size thresholds settings
     */
    @Builder.Default
    private QueueSizeThresholdsSettings queueSizeThresholdsSettings = QueueSizeThresholdsSettings.builder().build();

    /**
     * Queue size thresholds changes listener
     */
    @Builder.Default
    private QueueSizeThresholdsListener queueSizeThresholdsListener = QueueSizeThresholdsListener.VoidQueueSizeThresholdsListener.getInstance();

    /**
     * Underlying resource state changes listener
     */
    @Builder.Default
    private StoreUnderlyingResourceStateListener storeUnderlyingResourceStateListener = StoreUnderlyingResourceStateListener.VoidStoreUnderlyingResourceStateListener.getInstance();

    @Getter
    @Builder(toBuilder = true)
    public static class QueueSizeThresholdsSettings {

        /**
         * The depth considered healthy.
         */
        @Builder.Default
        private int normal = 10;
        /**
         * The depth at which the queue is reported as growing.
         */
        @Builder.Default
        private int warn = 100;
        /**
         * The depth at which it is reported as not keeping up.
         */
        @Builder.Default
        private int critical = 1000;

        /**
         * How often the depth is measured.
         */
        @Builder.Default
        private Duration evaluationInterval = Duration.ofSeconds(10);

    }
}