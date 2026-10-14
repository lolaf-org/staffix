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

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/**
 * The consumer threads draining the queue into the wrapped store or logger.
 */
@Slf4j
public class AsyncStoreThreads<T extends AsyncEvent> extends Startable.SimpleStartable<AsyncStoreThreads<T>> {

    private final AsyncStoreThread<T>[] asyncStoreThreads;
    private final AsyncStoreSettings asyncStoreSettings;
    private int nextRegisterIndex;

    /**
     * Creates {@code readerThreadsCount} reader threads, named after {@code instanceId}, which run only once started.
     */
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

    /**
     * Assigns the queue to the next reader thread in turn, for good: a write blocking there delays every queue sharing
     * it. The consumer gets up to {@code eventsBatching} events, the array null-terminated when fewer, once that many
     * are waiting or the flush interval has passed. It must release each event, which returns it to the pool.
     */
    public synchronized void registerBatching(PersistentQueue<T> messagesQueue, Consumer<T[]> eventsConsumer,
                                              AsyncEventSerde<T> asyncEventSerde, Class<T> eventClass) {
        if (nextRegisterIndex >= asyncStoreThreads.length) {
            nextRegisterIndex = 0;
        }
        asyncStoreThreads[nextRegisterIndex++].registerBatching(asyncStoreSettings, messagesQueue, eventsConsumer, asyncEventSerde, eventClass);
    }

    /**
     * Returns false when the reader thread is still inside {@code eventsConsumer} once {@code deadline} expires (waiting
     * at least a second): the caller must then expect a late call into it.
     */
    public boolean unregisterBatching(Consumer<T[]> eventsConsumer, Deadline deadline) {
        return removeAndAwait(t -> t.unRegisterBatching(eventsConsumer), deadline);
    }

    /**
     * Assigns the queue to the next reader thread in turn, for good: a write blocking there delays every queue sharing
     * it. The event handed to the consumer is reused once it returns, so it must not be kept.
     */
    public synchronized void register(PersistentQueue<T> messagesQueue, Consumer<T> eventsConsumer,
                                      AsyncEventSerde<T> asyncEventSerde) {
        if (nextRegisterIndex >= asyncStoreThreads.length) {
            nextRegisterIndex = 0;
        }
        asyncStoreThreads[nextRegisterIndex++].register(messagesQueue, eventsConsumer, asyncEventSerde);
    }

    /**
     * Returns false when the reader thread is still inside {@code eventsConsumer} once {@code deadline} expires (waiting
     * at least a second): the caller must then expect a late call into it.
     */
    public boolean unregister(Consumer<T> eventsConsumer, Deadline deadline) {
        return removeAndAwait(t -> t.unRegister(eventsConsumer), deadline);
    }

    private boolean removeAndAwait(ToLongFunction<AsyncStoreThread<T>> unRegister, Deadline deadline) {
        AsyncStoreThread<T> owner = null;
        long operationsCountAtRemoval = NOT_REGISTERED;
        synchronized (this) {
            for (AsyncStoreThread<T> asyncStoreThread : asyncStoreThreads) {
                operationsCountAtRemoval = unRegister.applyAsLong(asyncStoreThread);
                if (operationsCountAtRemoval != NOT_REGISTERED) {
                    owner = asyncStoreThread;
                    break;
                }
            }
        }
        return owner == null || owner.awaitNextOperation(operationsCountAtRemoval, deadline);
    }

    private static final long NOT_REGISTERED = -1;
    private static final Duration MIN_UNREGISTRATION_WAIT = Duration.ofSeconds(1);

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
        private volatile Registration<T>[] registrationsArray;
        private volatile BatchingRegistration<T>[] batchingRegistrationsArray;
        private volatile Thread thread;

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

        private void register(PersistentQueue<T> messagesQueue, Consumer<T> eventsConsumer,
                              AsyncEventSerde<T> asyncEventSerde) {
            registrations.computeIfAbsent(eventsConsumer,
                    c -> new Registration<>(messagesQueue, c,
                            asyncEventInstanceProvider.instanciateAsyncEvent(null), asyncEventSerde));
            registrationsArray = registrations.values().toArray(new Registration[0]);
        }

        long unRegister(Consumer<T> eventsConsumer) {
            Registration<T> registration = registrations.remove(eventsConsumer);
            if (registration == null) {
                return NOT_REGISTERED;
            }
            registration.onUnregistered();
            long operationsCount = threadOperationsCounter.get();
            registrationsArray = registrations.values().toArray(new Registration[0]);
            return operationsCount;
        }

        boolean awaitNextOperation(long operationsCountAtRemoval, Deadline deadline) {
            Thread readerThread = thread;
            // the reader thread unregisters itself when its consumer starts a watchdog: its counter cannot tick until it
            // returns, and the inactive flag already keeps it off the queue once it does
            if (readerThread == null || readerThread == Thread.currentThread() || !readerThread.isAlive()) {
                return true;
            }
            Deadline waitDeadline = deadline.getRemainingTime().compareTo(MIN_UNREGISTRATION_WAIT) < 0
                    ? Deadline.of(MIN_UNREGISTRATION_WAIT) : deadline;
            return waitDeadline.waitAsLongAs(() -> threadOperationsCounter.get() == operationsCountAtRemoval);
        }

        private void registerBatching(AsyncStoreSettings asyncStoreSettings, PersistentQueue<T> messagesQueue, Consumer<T[]> eventsConsumer,
                                      AsyncEventSerde<T> asyncEventSerde, Class<T> eventClass) {
            batchingRegistrations.computeIfAbsent(eventsConsumer,
                    c -> new BatchingRegistration<>(asyncStoreSettings, messagesQueue, c, asyncEventInstanceProvider, asyncEventSerde, eventClass));
            batchingRegistrationsArray = batchingRegistrations.values().toArray(new BatchingRegistration[0]);
        }

        long unRegisterBatching(Consumer<T[]> eventsConsumer) {
            BatchingRegistration<T> registration = batchingRegistrations.remove(eventsConsumer);
            if (registration == null) {
                return NOT_REGISTERED;
            }
            registration.onUnregistered();
            long operationsCount = threadOperationsCounter.get();
            batchingRegistrationsArray = batchingRegistrations.values().toArray(new BatchingRegistration[0]);
            return operationsCount;
        }

        void start() {
            if (running.compareAndSet(false, true)) {
                thread = threadFactory.newThread(this);
                thread.setName(threadName);
                thread.setUncaughtExceptionHandler((t, e) -> log.error("Uncaught exception occurred in thread {}", t, e));
                thread.start();
            }
        }

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
                }
                BatchingRegistration<T>[] localBatchingRegistrationsArray = batchingRegistrationsArray;
                for (BatchingRegistration<T> r : localBatchingRegistrationsArray) {
                    readOperations += r.readAndNotifyEvents(now);
                }
                threadOperationsCounter.getAndIncrement();
                idleStrategy.idle(readOperations);
            }
        }

        private static class AbstractRegistration<T extends AsyncEvent> {
            final PersistentQueue<T> messagesQueue;
            private final AtomicBoolean activeRegistration;

            public AbstractRegistration(PersistentQueue<T> messagesQueue) {
                this.messagesQueue = messagesQueue;
                this.activeRegistration = new AtomicBoolean(true);
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

            public Registration(PersistentQueue<T> messagesQueue, Consumer<T> eventsConsumer,
                                T asyncEvent, AsyncEventSerde<T> asyncEventSerde) {
                super(messagesQueue);
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

            public BatchingRegistration(AsyncStoreSettings asyncStoreSettings,
                                        PersistentQueue<T> messagesQueue, Consumer<T[]> eventsConsumer, AsyncEventInstanceProvider<T> asyncEventInstanceProvider,
                                        AsyncEventSerde<T> asyncEventSerde, Class<T> eventClass) {
                super(messagesQueue);
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