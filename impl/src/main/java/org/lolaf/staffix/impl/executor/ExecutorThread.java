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
package org.lolaf.staffix.impl.executor;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.ringos.rb.RingBuffer;
import org.lolaf.ringos.rb.RingBufferFactory;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.executor.MessageExecutor;
import org.lolaf.staffix.api.executor.MessageExecutorSettings;
import org.lolaf.staffix.api.executor.MessageProcessor;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;

/**
 * One worker of a {@link MessageExecutorImpl}, with its own queue.
 *
 * <p>A thread per queue rather than a shared one: work is already partitioned by field hash, so a shared queue
 * would add contention without adding parallelism.
 */
@Slf4j
public class ExecutorThread extends Startable.SimpleStartable<ExecutorThread> implements Runnable, MessageExecutor {

    private final RingBuffer<Task> queue;
    private final ThreadFactory threadFactory;
    private final String threadName;
    private final Set<MessageExecutor> activeExecutors;
    private final IdleStrategy idleStrategy;
    private Thread thread;
    private volatile boolean running;

    public ExecutorThread(MessageExecutorSettings settings, String instanceId, int instance) {
        this.queue = RingBufferFactory.build(RingBufferFactory.AccessType.SINGLE_CONSUMER_MULTI_PRODUCER, settings.getQueueSizePerThread(), i -> new Task<>());
        this.threadFactory = settings.getThreadFactory();
        this.threadName = "Staffix-messages-executor-" + instanceId + "-" + instance;
        this.activeExecutors = ConcurrentHashMap.newKeySet();
        this.idleStrategy = settings.getIdleStrategy().get();
    }

    @Override
    public boolean execute(MessageProcessor processor, Object message, Object param1, Object param2, Object param3) {
        try {
            return queue.offer(this::taskEventTranslator, processor, message, param1, param2, param3);
        } finally {
            idleStrategy.wakeup();
        }
    }

    @Override
    public void execute(MessageProcessor processor, Object message, Object param1, Object param2, Object param3, IdleStrategy queueFullIdleStrategy) {
        queue.offerBlocking(this::taskEventTranslator, processor, message, param1, param2, param3, queueFullIdleStrategy);
        idleStrategy.wakeup();
    }

    @Override
    public void release() {
        throw new IllegalStateException("Should never have been called");
    }

    synchronized void onMessageExecutorAssigned(MessageExecutor executor) {
        if (activeExecutors.isEmpty()) {
            thread = threadFactory.newThread(this);
            thread.setName(threadName);
            thread.setUncaughtExceptionHandler((t, e) -> log.error("Uncaught exception occurred in thread {}", t, e));
            // establish the running flag before the thread starts so a concurrent stop cannot be clobbered by run()
            running = true;
            thread.start();
        }
        activeExecutors.add(executor);
    }

    synchronized void onMessageExecutorReleased(MessageExecutor executor, Deadline deadline) {
        activeExecutors.remove(executor);
        if (activeExecutors.isEmpty()) {
            stopThread(deadline);
        }
    }

    private <M, P1, P2, P3> void taskEventTranslator(Task<M, P1, P2, P3> task, MessageProcessor<M, P1, P2, P3> processor, M message, P1 param1, P2 param2, P3 param3) {
        task.processor = processor;
        task.message = message;
        task.param1 = param1;
        task.param2 = param2;
        task.param3 = param3;
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        stopThread(stopDeadline);
        // a stopped thread holds no assignment: leaving them here would have onMessageExecutorAssigned believe the
        // thread is already running, and this one never start again if the runtime around it does
        activeExecutors.clear();
    }

    private void stopThread(Deadline stopDeadline) {
        if (thread == null) {
            return;
        }
        running = false;
        thread.interrupt();
        Task<?, ?, ?, ?> unblockThreadTask = new Task<>();
        unblockThreadTask.processor = (message, param1, param2, param3) -> {
            // don't care
        };
        queue.offerBlocking(unblockThreadTask, new BackoffIdleStrategy());
        if (!stopDeadline.isImmediate()) {
            try {
                // Deadline.getRemainingTime() floors at zero and Thread.join(0) waits for ever, so an expired
                // deadline has to be given a millisecond of its own rather than be passed through
                thread.join(Math.max(1L, stopDeadline.getRemainingTime().toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }

    @Override
    protected void startMe() throws StartStopException {
        // nothing to do
    }

    @Override
    public void run() {
        log.info("Message executor Thread {} started", threadName);
        idleStrategy.assignToThread(Thread.currentThread());
        while (running) {
            queue.pollBlocking(this::processTask, idleStrategy);
        }
        // flush remaining messages
        while (!queue.isEmpty()) {
            queue.poll(this::processTask);
        }
        log.info("Message executor Thread {} stopped", threadName);
    }

    private void processTask(Task task) {
        try {
            task.processor.process(task.message, task.param1, task.param2, task.param3);
        } catch (Exception ex) {
            log.error("Failed to process task", ex);
        }
    }

    private static class Task<M, P1, P2, P3> {
        private MessageProcessor<M, P1, P2, P3> processor;
        private M message;
        private P1 param1;
        private P2 param2;
        private P3 param3;
    }

}
