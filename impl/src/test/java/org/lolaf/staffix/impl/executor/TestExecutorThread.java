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

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.staffix.api.executor.MessageExecutor;
import org.lolaf.staffix.api.executor.MessageExecutorSettings;
import org.lolaf.staffix.api.executor.MessageProcessor;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestExecutorThread {

    private static final String THREAD_NAME = "Staffix-messages-executor-test-0";

    ExecutorThread executorThread;
    MessageExecutor exec;
    MessageProcessor processor;

    @BeforeEach
    void setup() {
        executorThread = new ExecutorThread(MessageExecutorSettings.builder().build(), "test", 0);
        executorThread.start();
        exec = Mockito.mock(MessageExecutor.class);
        processor = Mockito.mock(MessageProcessor.class);
    }

    @AfterEach
    void shutdown() {
        executorThread.stop(Deadline.of(Duration.ofSeconds(1)));
    }

    @Test
    void testTaskIsExecuted() {
        executorThread.onMessageExecutorAssigned(exec);

        executorThread.execute(processor, "test", "p1", "p2", "p3");

        Awaitility.await().untilAsserted(() -> Mockito.verify(processor).process("test", "p1", "p2", "p3"));
    }

    @Test
    void testExecuteReturnsTrueWhenTaskEnqueued() {
        executorThread.onMessageExecutorAssigned(exec);

        boolean enqueued = executorThread.execute(processor, "test", "p1", "p2", "p3");

        assertThat(enqueued).isTrue();
        Awaitility.await().untilAsserted(() -> Mockito.verify(processor).process("test", "p1", "p2", "p3"));
    }

    @Test
    void testBlockingExecuteProcessesTask() {
        executorThread.onMessageExecutorAssigned(exec);

        executorThread.execute(processor, "test", "p1", "p2", "p3", new BackoffIdleStrategy());

        Awaitility.await().untilAsserted(() -> Mockito.verify(processor).process("test", "p1", "p2", "p3"));
    }

    @Test
    void testAssigningExecutorStartsTheWorkerThreadAndReleasingItStopsIt() {
        executorThread.onMessageExecutorAssigned(exec);
        assertThat(aliveWorkerThreads()).isEqualTo(1);

        executorThread.onMessageExecutorReleased(exec, Deadline.immediate());
        Awaitility.await().untilAsserted(() -> assertThat(aliveWorkerThreads()).isZero());
    }

    @Test
    void testMultipleExecutorsShareASingleWorkerThread() {
        MessageExecutor otherExec = Mockito.mock(MessageExecutor.class);

        executorThread.onMessageExecutorAssigned(exec);
        executorThread.onMessageExecutorAssigned(otherExec);

        assertThat(aliveWorkerThreads()).isEqualTo(1);
    }

    @Test
    void testThreadStaysAliveUntilLastExecutorReleased() {
        MessageExecutor otherExec = Mockito.mock(MessageExecutor.class);
        executorThread.onMessageExecutorAssigned(exec);
        executorThread.onMessageExecutorAssigned(otherExec);

        // releasing one of two executors must keep the worker thread running and processing
        executorThread.onMessageExecutorReleased(exec, Deadline.immediate());
        executorThread.execute(processor, "alive", null, null, null);
        Awaitility.await().untilAsserted(() -> Mockito.verify(processor).process("alive", null, null, null));
        assertThat(aliveWorkerThreads()).isEqualTo(1);

        // releasing the last executor stops the worker thread
        executorThread.onMessageExecutorReleased(otherExec, Deadline.immediate());
        Awaitility.await().untilAsserted(() -> assertThat(aliveWorkerThreads()).isZero());
    }

    @Test
    void testReleaseIsNotSupported() {
        assertThatThrownBy(() -> executorThread.release()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testProcessorExceptionDoesNotKillWorkerThread() {
        executorThread.onMessageExecutorAssigned(exec);
        MessageProcessor failing = Mockito.mock(MessageProcessor.class);
        Mockito.doThrow(new RuntimeException("boom")).when(failing).process("boom", null, null, null);

        executorThread.execute(failing, "boom", null, null, null);
        executorThread.execute(processor, "survivor", null, null, null);

        // the thread must have swallowed the exception and processed the following task
        Awaitility.await().untilAsserted(() -> Mockito.verify(processor).process("survivor", null, null, null));
    }

    @Test
    void testTasksAreProcessedInFifoOrder() {
        executorThread.onMessageExecutorAssigned(exec);
        List<String> processed = new CopyOnWriteArrayList<>();
        AtomicInteger count = new AtomicInteger();
        MessageProcessor<String, Object, Object, Object> collector = (message, p1, p2, p3) -> {
            processed.add(message);
            count.incrementAndGet();
        };

        for (int i = 0; i < 10; i++) {
            executorThread.execute(collector, "msg-" + i, null, null, null);
        }

        Awaitility.await().untilAtomic(count, org.hamcrest.Matchers.equalTo(10));
        assertThat(processed).containsExactly("msg-0", "msg-1", "msg-2", "msg-3", "msg-4", "msg-5", "msg-6", "msg-7", "msg-8", "msg-9");
    }

    private long aliveWorkerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> THREAD_NAME.equals(t.getName()) && t.isAlive())
                .count();
    }
}
