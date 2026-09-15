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
package org.lolaf.staffix.plugins.async;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.ringos.rb.RingBuffer;
import org.lolaf.ringos.rb.RingBufferFactory;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Pool of consumer threads that drain per-session {@link AsyncPluginEvent} ring buffers and replay the
 * buffered callbacks against the wrapped delegate plugins.
 *
 * <p>Each per-session queue is single-consumer / multi-producer (inbound decode and outbound encode/send
 * callbacks for the same session originate on different engine threads) and is round-robin-assigned to a
 * consumer thread.
 */
@Slf4j
final class AsyncPluginConsumerPool {

    private final ConsumerThread[] threads;
    private final int queueSize;
    private int nextRegisterIndex;

    AsyncPluginConsumerPool(int poolSize, int queueSize, String instanceId, Supplier<IdleStrategy> idleStrategySupplier) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("consumerThreadPoolSize must be >= 1, got " + poolSize);
        }
        this.queueSize = queueSize;
        this.threads = new ConsumerThread[poolSize];
        for (int i = 0; i < poolSize; i++) {
            threads[i] = new ConsumerThread(instanceId, i, idleStrategySupplier.get());
        }
    }

    /**
     * Create a queue for a newly created session and bind it to a consumer thread that will replay its
     * events against {@code delegate}. The queue is unregistered automatically once its
     * {@link AsyncPluginEvent.Type#SESSION_DESTROYED} event has been drained.
     */
    RingBuffer<AsyncPluginEvent> register(FixSessionPlugin<?, Object> delegate) {
        RingBuffer<AsyncPluginEvent> queue = RingBufferFactory.build(
                RingBufferFactory.AccessType.SINGLE_CONSUMER_MULTI_PRODUCER, queueSize, i -> new AsyncPluginEvent());
        synchronized (this) {
            if (nextRegisterIndex >= threads.length) {
                nextRegisterIndex = 0;
            }
            threads[nextRegisterIndex++].register(queue, delegate);
        }
        return queue;
    }

    void start() {
        for (ConsumerThread t : threads) {
            t.start();
        }
    }

    void stop(Deadline stopDeadline) {
        for (ConsumerThread t : threads) {
            t.stop(stopDeadline);
        }
    }

    private static final class Binding {
        private final RingBuffer<AsyncPluginEvent> queue;
        private final Consumer<AsyncPluginEvent> consumer;

        private Binding(RingBuffer<AsyncPluginEvent> queue, Consumer<AsyncPluginEvent> consumer) {
            this.queue = queue;
            this.consumer = consumer;
        }
    }

    private static final class ConsumerThread implements Runnable {

        private final IdleStrategy idleStrategy;
        private final String threadName;
        private final AtomicBoolean running;
        private volatile Binding[] bindings;
        private Thread thread;

        ConsumerThread(String instanceId, int index, IdleStrategy idleStrategy) {
            this.idleStrategy = idleStrategy;
            this.threadName = "Staffix-async-plugin-consumer-" + instanceId + "-" + index;
            this.running = new AtomicBoolean(false);
            this.bindings = new Binding[0];
        }

        void register(RingBuffer<AsyncPluginEvent> queue, FixSessionPlugin<?, Object> delegate) {
            // Inject the unregister action into every pre-allocated slot so the SESSION_DESTROYED event
            // triggers it directly from dispatch(); the consumer loop then needs no per-event type test.
            Runnable unregister = () -> unregister(queue);
            queue.forEachEntry(event -> event.setOnSessionDestroyed(unregister));
            Consumer<AsyncPluginEvent> consumer = event -> event.safelyDispatch(delegate);
            synchronized (this) {
                Binding[] previous = this.bindings;
                Binding[] updated = new Binding[previous.length + 1];
                System.arraycopy(previous, 0, updated, 0, previous.length);
                updated[previous.length] = new Binding(queue, consumer);
                this.bindings = updated;
            }
        }

        private void unregister(RingBuffer<AsyncPluginEvent> queue) {
            synchronized (this) {
                Binding[] previous = this.bindings;
                int idx = -1;
                for (int i = 0; i < previous.length; i++) {
                    if (previous[i].queue == queue) {
                        idx = i;
                        break;
                    }
                }
                if (idx < 0) {
                    return;
                }
                Binding[] updated = new Binding[previous.length - 1];
                System.arraycopy(previous, 0, updated, 0, idx);
                System.arraycopy(previous, idx + 1, updated, idx, previous.length - idx - 1);
                this.bindings = updated;
            }
        }

        void start() {
            if (running.compareAndSet(false, true)) {
                thread = new Thread(this, threadName);
                thread.setDaemon(false);
                thread.setUncaughtExceptionHandler(
                        (t, e) -> log.error("Uncaught exception in async plugin consumer {}", t.getName(), e));
                thread.start();
            }
        }

        void stop(Deadline stopDeadline) {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            idleStrategy.wakeup();
            try {
                thread.join(Math.max(1L, stopDeadline.getRemainingTime().toMillis()));
                if (thread.isAlive()) {
                    log.warn("Async plugin consumer {} did not exit within deadline; some callbacks may be lost", threadName);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                thread = null;
            }
        }

        @Override
        public void run() {
            idleStrategy.assignToThread(Thread.currentThread());
            while (running.get()) {
                idleStrategy.idle(drainOnce());
            }
            while (drainOnce() > 0) {
                // final drain after stop signal so in-flight callbacks (including onSessionDestroyed) still land
            }
        }

        private int drainOnce() {
            int work = 0;
            Binding[] local = bindings;
            for (Binding b : local) {
                while (b.queue.poll(b.consumer)) {
                    work++;
                }
            }
            return work;
        }
    }
}
