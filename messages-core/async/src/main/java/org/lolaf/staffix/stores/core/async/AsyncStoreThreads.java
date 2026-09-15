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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.session.FixSessionId;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The consumer threads draining the queue into the wrapped store or logger.
 */
@Slf4j
public class AsyncStoreThreads<T extends AsyncEvent> extends Startable.SimpleStartable<AsyncStoreThreads<T>> {

    private final AsyncStoreThread<T>[] asyncStoreThreads;
    private final AsyncStoreSettings asyncStoreSettings;
    private int nextRegisterIndex;

    public AsyncStoreThreads(AsyncStoreSettings asyncStoreSettings, String instanceId,
                             AsyncEventInstanceProvider<T> asyncEventInstanceProvider) {
        this.asyncStoreSettings = asyncStoreSettings;
        this.asyncStoreThreads = new AsyncStoreThread[asyncStoreSettings.getReaderThreadsCount()];
        for (int i = 0; i < asyncStoreSettings.getReaderThreadsCount(); i++) {
            asyncStoreThreads[i] = new AsyncStoreThread<>(instanceId, i, asyncStoreSettings.getReaderThreadsIdleStrategy().get(),
                    asyncStoreSettings.getReaderThreadMaxEventsProcessingPerStore(), asyncEventInstanceProvider, asyncStoreSettings.getThreadFactory());
        }
    }

    @Override
    protected void startMe() throws StartStopException {
        for (AsyncStoreThread<T> asyncStoreThread : asyncStoreThreads) {
            asyncStoreThread.start();
        }
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        for (AsyncStoreThread<T> asyncStoreThread : asyncStoreThreads) {
            asyncStoreThread.stop(stopDeadline);
        }
    }

    public synchronized void registerBatching(FixSessionId fixSessionId, PersistentQueue<T> messagesQueue, Consumer<T[]> eventsConsumer,
                                              AsyncEventSerde<T> asyncEventSerde, Class<T> eventClass) {
        if (nextRegisterIndex >= asyncStoreThreads.length) {
            nextRegisterIndex = 0;
        }
        asyncStoreThreads[nextRegisterIndex++].registerBatching(asyncStoreSettings, fixSessionId, messagesQueue, eventsConsumer, asyncEventSerde, eventClass);
    }

    public synchronized void unregisterBatching(Consumer<T[]> eventsConsumer) {
        for (AsyncStoreThread<T> asyncStoreThread : asyncStoreThreads) {
            if (asyncStoreThread.unRegisterBatching(eventsConsumer)) {
                break;
            }
        }
    }

    public synchronized void register(FixSessionId fixSessionId, PersistentQueue<T> messagesQueue, Consumer<T> eventsConsumer,
                                      AsyncEventSerde<T> asyncEventSerde) {
        if (nextRegisterIndex >= asyncStoreThreads.length) {
            nextRegisterIndex = 0;
        }
        asyncStoreThreads[nextRegisterIndex++].register(asyncStoreSettings, fixSessionId, messagesQueue, eventsConsumer, asyncEventSerde);
    }

    public synchronized void unregister(Consumer<T> eventsConsumer) {
        for (AsyncStoreThread<T> asyncStoreThread : asyncStoreThreads) {
            if (asyncStoreThread.unRegister(eventsConsumer)) {
                break;
            }
        }
    }

    private static class AsyncStoreThread<T extends AsyncEvent> implements Runnable {

        private final Map<Consumer<T>, Registration<T>> registrations;
        private final Map<Consumer<T[]>, BatchingRegistration<T>> batchingRegistrations;
        private final AtomicBoolean running;
        private final int maxEventsProcessingPerStore;
        private final AsyncEventInstanceProvider<T> asyncEventInstanceProvider;
        private final AtomicLong threadOperationsCounter;
        private final IdleStrategy idleStrategy;
        private final ThreadFactory threadFactory;
        private final String threadName;
        private Registration<T>[] registrationsArray;
        private BatchingRegistration<T>[] batchingRegistrationsArray;
        private Thread thread;

        public AsyncStoreThread(String instanceId, int instance, IdleStrategy idleStrategy,
                                int maxEventsProcessingPerStore, AsyncEventInstanceProvider<T> asyncEventInstanceProvider,
                                ThreadFactory threadFactory) {
            this.running = new AtomicBoolean();
            this.registrations = new HashMap<>();
            this.batchingRegistrations = new HashMap<>();
            this.batchingRegistrationsArray = new BatchingRegistration[0];
            this.registrationsArray = new Registration[0];
            this.maxEventsProcessingPerStore = maxEventsProcessingPerStore;
            this.asyncEventInstanceProvider = asyncEventInstanceProvider;
            this.idleStrategy = idleStrategy;
            this.threadFactory = threadFactory;
            this.threadName = "Staffix-async-store-reader-" + instanceId + "-" + instance;
            this.threadOperationsCounter = new AtomicLong();
        }

        private void register(AsyncStoreSettings asyncStoreSettings, FixSessionId fixSessionId, PersistentQueue<T> messagesQueue, Consumer<T> eventsConsumer,
                              AsyncEventSerde<T> asyncEventSerde) {
            registrations.computeIfAbsent(eventsConsumer,
                    c -> new Registration<>(asyncStoreSettings, fixSessionId, messagesQueue, c,
                            asyncEventInstanceProvider.instanciateAsyncEvent(null), asyncEventSerde));
            registrationsArray = registrations.values().toArray(new Registration[0]);
        }

        boolean unRegister(Consumer<T> eventsConsumer) {
            Registration<T> registration = registrations.remove(eventsConsumer);
            if (registration != null) {
                long lastThreadOperationsCount = threadOperationsCounter.get();
                registration.onUnregistered();
                registrationsArray = registrations.values().toArray(new Registration[0]);
                waitForNextOperationByThread(lastThreadOperationsCount);
                return true;
            }
            return false;
        }

        private void waitForNextOperationByThread(long lastThreadOperationsCount) {
            Duration maxWaitTime = Duration.ofSeconds(1);
            if (!Deadline.of(maxWaitTime).waitAsLongAs(() -> threadOperationsCounter.get() == lastThreadOperationsCount)) {
                log.warn("Abnormal AysncStoreThread unregistration state, next operation done by thread is not seen within {}", maxWaitTime);
            }
        }

        private void registerBatching(AsyncStoreSettings asyncStoreSettings, FixSessionId fixSessionId, PersistentQueue<T> messagesQueue, Consumer<T[]> eventsConsumer,
                                      AsyncEventSerde<T> asyncEventSerde, Class<T> eventClass) {
            batchingRegistrations.computeIfAbsent(eventsConsumer,
                    c -> new BatchingRegistration<>(asyncStoreSettings, fixSessionId, messagesQueue, c, asyncEventInstanceProvider, asyncEventSerde, eventClass));
            batchingRegistrationsArray = batchingRegistrations.values().toArray(new BatchingRegistration[0]);
        }

        boolean unRegisterBatching(Consumer<T[]> eventsConsumer) {
            BatchingRegistration<T> registration = batchingRegistrations.remove(eventsConsumer);
            if (registration != null) {
                long lastThreadOperationsCount = threadOperationsCounter.get();
                registration.onUnregistered();
                batchingRegistrationsArray = batchingRegistrations.values().toArray(new BatchingRegistration[0]);
                waitForNextOperationByThread(lastThreadOperationsCount);
                return true;
            }
            return false;
        }

        /**
         * The running flag is raised here rather than by {@link #run()}: the new thread can take a while to be
         * scheduled on a loaded machine, and a {@link #stop} landing in that window used to be undone by run()'s own
         * {@code set(true)}, leaving a thread looping on a flag nobody clears again and a join() that never returns.
         * Comparing and setting also makes a second start a no-op, where testing the flag let two threads be created
         * and only the second one be kept, so the first could never be stopped.
         */
        void start() {
            if (running.compareAndSet(false, true)) {
                thread = threadFactory.newThread(this);
                thread.setName(threadName);
                thread.setUncaughtExceptionHandler((t, e) -> log.error("Uncaught exception occurred in thread {}", t, e));
                thread.start();
            }
        }

        /**
         * Mirrors {@link #start()}: lowering the flag with a compare and set makes stopping a thread that was never
         * started, or stopping twice, a no-op instead of joining or interrupting a thread that is not there.
         */
        void stop(Deadline stopDeadline) {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            try {
                thread.interrupt();
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
            // running is set by start(), see why there
            idleStrategy.assignToThread(Thread.currentThread());
            while (running.get()) {
                int readOperations = 0;
                long now = System.currentTimeMillis();
                Registration<T>[] localRegistrationsArray = registrationsArray;
                for (Registration<T> r : localRegistrationsArray) {
                    readOperations += r.readAndNotifyEvent(maxEventsProcessingPerStore);
                    r.evaluateQueueSizeThreshold(now);
                }
                BatchingRegistration<T>[] localBatchingRegistrationsArray = batchingRegistrationsArray;
                for (BatchingRegistration<T> r : localBatchingRegistrationsArray) {
                    readOperations += r.readAndNotifyEvents(now);
                    r.evaluateQueueSizeThreshold(now);
                }
                threadOperationsCounter.getAndIncrement();
                idleStrategy.idle(readOperations);
            }
        }

        private static class AbstractRegistration<T extends AsyncEvent> {
            final PersistentQueue<T> messagesQueue;
            private final QueueSizeThresholdsListener queueSizeThresholdsListener;
            private final AsyncStoreSettings.QueueSizeThresholdsSettings queueSizeThresholdsSettings;
            private final FixSessionId fixSessionId;
            private final long evaluationIntervalInMillis;
            private final AtomicBoolean activeRegistration;
            private QueueSizeThresholdsListener.QueueSizeThreshold currentQueueSizeThreshold;
            private long nextThresholdNotification;

            public AbstractRegistration(PersistentQueue<T> messagesQueue, FixSessionId fixSessionId, AsyncStoreSettings asyncStoreSettings) {
                this.messagesQueue = messagesQueue;
                this.fixSessionId = fixSessionId;
                this.currentQueueSizeThreshold = QueueSizeThresholdsListener.QueueSizeThreshold.NORMAL;
                this.queueSizeThresholdsListener = asyncStoreSettings.getQueueSizeThresholdsListener();
                this.queueSizeThresholdsSettings = asyncStoreSettings.getQueueSizeThresholdsSettings();
                this.evaluationIntervalInMillis = queueSizeThresholdsSettings.getEvaluationInterval().toMillis();
                this.nextThresholdNotification = System.currentTimeMillis() + evaluationIntervalInMillis;
                this.activeRegistration = new AtomicBoolean(true);
            }

            void evaluateQueueSizeThreshold(long now) {
                int queueSize = messagesQueue.getSize();
                if (queueSize >= queueSizeThresholdsSettings.getCritical()) {
                    triggerThresholdsIfNeeded(queueSizeThresholdsSettings.getCritical(), queueSize, now, QueueSizeThresholdsListener.QueueSizeThreshold.CRITICAL, true);
                } else if (queueSize >= queueSizeThresholdsSettings.getWarn()) {
                    triggerThresholdsIfNeeded(queueSizeThresholdsSettings.getWarn(), queueSize, now, QueueSizeThresholdsListener.QueueSizeThreshold.WARNING, true);
                } else if (queueSize <= queueSizeThresholdsSettings.getNormal()) {
                    triggerThresholdsIfNeeded(queueSizeThresholdsSettings.getNormal(), queueSize, now, QueueSizeThresholdsListener.QueueSizeThreshold.NORMAL, false);
                }
            }

            private void triggerThresholdsIfNeeded(int thresholdValue, int queueSize, long now, QueueSizeThresholdsListener.QueueSizeThreshold threshold, boolean checkNextThresholdNotification) {
                if (!currentQueueSizeThreshold.equals(threshold) || (checkNextThresholdNotification && nextThresholdNotification < now)) {
                    nextThresholdNotification = now + evaluationIntervalInMillis;
                    currentQueueSizeThreshold = threshold;
                    queueSizeThresholdsListener.onQueueSizeThresholdReached(fixSessionId, threshold, thresholdValue, queueSize);
                }
            }

            void onUnregistered() {
                activeRegistration.set(false);
            }

            boolean isActiveRegistration() {
                return activeRegistration.get();
            }
        }

        private static class Registration<T extends AsyncEvent> extends AbstractRegistration<T> {
            private final Consumer<T> eventsConsumer;
            private final AsyncEventSerde<T> asyncEventSerde;
            private final T asyncEvent;

            public Registration(AsyncStoreSettings asyncStoreSettings, FixSessionId fixSessionId, PersistentQueue<T> messagesQueue, Consumer<T> eventsConsumer,
                                T asyncEvent, AsyncEventSerde<T> asyncEventSerde) {
                super(messagesQueue, fixSessionId, asyncStoreSettings);
                this.eventsConsumer = eventsConsumer;
                this.asyncEventSerde = asyncEventSerde;
                this.asyncEvent = asyncEvent;
            }

            int readAndNotifyEvent(int maxEventsProcessing) {
                int readOperation = 0;
                while (isActiveRegistration() && readOperation++ < maxEventsProcessing && messagesQueue.poll(asyncEventSerde, asyncEvent)) {
                    eventsConsumer.accept(asyncEvent);
                    asyncEvent.release();
                }
                return readOperation;
            }
        }

        @Slf4j
        private static class BatchingRegistration<T extends AsyncEvent> extends AbstractRegistration<T> {
            private final Consumer<T[]> eventsConsumer;
            private final int eventsBatchingCount;
            private final long batchingFlushIntervalInMillis;
            private final Queue<T> asyncEventsCache;
            private final T[] asyncEvents;
            private final AsyncEventSerde<T> asyncEventSerde;
            private long lastFlushTimeStamp;

            public BatchingRegistration(AsyncStoreSettings asyncStoreSettings, FixSessionId fixSessionId,
                                        PersistentQueue<T> messagesQueue, Consumer<T[]> eventsConsumer, AsyncEventInstanceProvider<T> asyncEventInstanceProvider,
                                        AsyncEventSerde<T> asyncEventSerde, Class<T> eventClass) {
                super(messagesQueue, fixSessionId, asyncStoreSettings);
                this.eventsConsumer = eventsConsumer;
                this.eventsBatchingCount = asyncStoreSettings.getEventsBatching();
                this.batchingFlushIntervalInMillis = asyncStoreSettings.getBatchingFlushInterval().toMillis();
                this.asyncEventsCache = new ArrayDeque<>();
                this.asyncEvents = (T[]) java.lang.reflect.Array.newInstance(eventClass, eventsBatchingCount);
                this.lastFlushTimeStamp = System.currentTimeMillis();
                for (int i = 0; i < eventsBatchingCount; i++) {
                    this.asyncEventsCache.add(asyncEventInstanceProvider.instanciateAsyncEvent(this::onEventReleased));
                }
                this.asyncEventSerde = asyncEventSerde;
            }

            void onEventReleased(T event) {
                if (!asyncEventsCache.offer(event)) {
                    log.error("Unable to return cached {} to full pool, abnormal situation", event.getClass().getSimpleName());
                }
            }

            private boolean requiresFlush(long now) {
                return lastFlushTimeStamp + batchingFlushIntervalInMillis < now;
            }

            int readAndNotifyEvents(long now) {
                int elementsInQueue = messagesQueue.getSize();
                if (elementsInQueue == 0) {
                    return 0;
                }
                int elementsToRead = 0;
                if (elementsInQueue >= eventsBatchingCount) {
                    elementsToRead = eventsBatchingCount;
                } else if (requiresFlush(now)) {
                    elementsToRead = elementsInQueue;
                }

                if (elementsToRead > 0) {
                    lastFlushTimeStamp = now;
                    Arrays.fill(asyncEvents, null);
                    readEvents(elementsToRead);
                    eventsConsumer.accept(asyncEvents);
                }
                return elementsToRead;
            }

            private void readEvents(int elementsToRead) {
                for (int i = 0; i < elementsToRead && isActiveRegistration(); i++) {
                    T event = asyncEventsCache.poll();
                    if (event == null) {
                        log.error("Unable to borrow a cached AsyncEvent, abnormal situation");
                        return;
                    }
                    if (messagesQueue.poll(asyncEventSerde, event)) {
                        asyncEvents[i] = event;
                    } else {
                        log.error("Unable to read an AsyncEvent from the queue, abnormal situation");
                        event.release();
                        return;
                    }
                }
            }
        }
    }
}