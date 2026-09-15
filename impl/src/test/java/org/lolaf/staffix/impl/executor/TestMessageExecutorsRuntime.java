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
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.executor.MessageExecutor;
import org.lolaf.staffix.api.executor.MessageExecutorSettings;
import org.lolaf.staffix.api.executor.MessageProcessor;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestMessageExecutorsRuntime {

    private MessageExecutorsRuntime runtime;
    private SessionMessageExecutors sessionExecutors;

    private void newRuntime(int threadsCount) {
        runtime = new MessageExecutorsRuntime(MessageExecutorSettings.builder().executorsThreadsCount(threadsCount).build());
        runtime.start();
        sessionExecutors = runtime.newSessionExecutors();
    }

    @AfterEach
    void shutdown() {
        if (runtime != null) {
            runtime.stop(Deadline.of(Duration.ofSeconds(1)));
        }
    }

    @Test
    void sameNamespaceAndIndexReturnsSameExecutor() {
        newRuntime(1);

        MessageExecutor<?, ?, ?, ?> first = sessionExecutors.getMessageExecutor(NamespaceA.class, 0);
        MessageExecutor<?, ?, ?, ?> second = sessionExecutors.getMessageExecutor(NamespaceA.class, 0);

        assertThat(first).isNotNull();
        assertThat(second).isSameAs(first);
    }

    @Test
    void sameIndexUnderDifferentNamespacesReturnsDifferentExecutors() {
        newRuntime(1);

        MessageExecutor<?, ?, ?, ?> a = sessionExecutors.getMessageExecutor(NamespaceA.class, 0);
        MessageExecutor<?, ?, ?, ?> b = sessionExecutors.getMessageExecutor(NamespaceB.class, 0);

        assertThat(a).isNotNull();
        assertThat(b).isNotNull().isNotSameAs(a);
    }

    /**
     * The namespace cache in front of the map: going back and forth must keep resolving each namespace to its own
     * executors rather than serve whichever was cached last.
     */
    @Test
    void alternatingNamespacesKeepsResolvingEachToItsOwnExecutor() {
        newRuntime(1);

        MessageExecutor<?, ?, ?, ?> a = sessionExecutors.getMessageExecutor(NamespaceA.class, 0);
        MessageExecutor<?, ?, ?, ?> b = sessionExecutors.getMessageExecutor(NamespaceB.class, 0);

        assertThat(sessionExecutors.getMessageExecutor(NamespaceA.class, 0)).isSameAs(a);
        assertThat(sessionExecutors.getMessageExecutor(NamespaceB.class, 0)).isSameAs(b);
        assertThat(sessionExecutors.getMessageExecutor(NamespaceA.class, 0)).isSameAs(a);
    }

    @Test
    void differentIndexesUnderSameNamespaceReturnDifferentExecutors() {
        newRuntime(1);

        MessageExecutor<?, ?, ?, ?> index0 = sessionExecutors.getMessageExecutor(NamespaceA.class, 0);
        MessageExecutor<?, ?, ?, ?> index1 = sessionExecutors.getMessageExecutor(NamespaceA.class, 1);

        assertThat(index0).isNotSameAs(index1);
    }

    @Test
    void twoSessionsNeverShareAnExecutorForTheSameRoutingKey() {
        newRuntime(1);
        SessionMessageExecutors otherSession = runtime.newSessionExecutors();

        MessageExecutor<?, ?, ?, ?> mine = sessionExecutors.getMessageExecutor(NamespaceA.class, 0);
        MessageExecutor<?, ?, ?, ?> theirs = otherSession.getMessageExecutor(NamespaceA.class, 0);

        assertThat(theirs).isNotNull().isNotSameAs(mine);
    }

    @Test
    void aNegativeRoutingKeyIndexIsRejected() {
        newRuntime(1);

        assertThatThrownBy(() -> sessionExecutors.getMessageExecutor(NamespaceA.class, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
    }

    @Test
    void returnedExecutorExposesRequestedIndex() {
        newRuntime(1);

        MessageExecutor<?, ?, ?, ?> executor = sessionExecutors.getMessageExecutor(NamespaceA.class, 7);

        assertThat(((MessageExecutorImpl<?, ?, ?, ?>) executor).getAsInt()).isEqualTo(7);
    }

    @Test
    void executorProcessesTaskOnAWorkerThread() {
        newRuntime(1);

        MessageExecutor<String, Void, Void, Void> executor = sessionExecutors.getMessageExecutor(NamespaceA.class, 0);

        String runningThread = runAndCaptureThreadName(executor);

        assertThat(runningThread)
                .isNotEqualTo(Thread.currentThread().getName())
                .startsWith("Staffix-messages-executor-");
    }

    @Test
    void executorsAreAssignedToWorkerThreadsRoundRobin() {
        newRuntime(2);

        MessageExecutor<String, Void, Void, Void> first = sessionExecutors.getMessageExecutor(NamespaceA.class, 0);
        MessageExecutor<String, Void, Void, Void> second = sessionExecutors.getMessageExecutor(NamespaceA.class, 1);

        assertThat(runAndCaptureThreadName(first)).endsWith("-0");
        assertThat(runAndCaptureThreadName(second)).endsWith("-1");
    }

    @Test
    void roundRobinWrapsAroundOnceThreadsAreExhausted() {
        newRuntime(2);

        MessageExecutor<String, Void, Void, Void> first = sessionExecutors.getMessageExecutor(NamespaceA.class, 0);
        MessageExecutor<String, Void, Void, Void> second = sessionExecutors.getMessageExecutor(NamespaceA.class, 1);
        MessageExecutor<String, Void, Void, Void> third = sessionExecutors.getMessageExecutor(NamespaceA.class, 2);

        String firstThread = runAndCaptureThreadName(first);
        String secondThread = runAndCaptureThreadName(second);
        String thirdThread = runAndCaptureThreadName(third);

        assertThat(thirdThread).isEqualTo(firstThread).isNotEqualTo(secondThread);
    }

    @Test
    void roundRobinIsSharedAcrossNamespaces() {
        newRuntime(2);

        MessageExecutor<String, Void, Void, Void> a = sessionExecutors.getMessageExecutor(NamespaceA.class, 0);
        MessageExecutor<String, Void, Void, Void> b = sessionExecutors.getMessageExecutor(NamespaceB.class, 0);

        assertThat(runAndCaptureThreadName(a)).endsWith("-0");
        assertThat(runAndCaptureThreadName(b)).endsWith("-1");
    }

    @Test
    void roundRobinIsSharedAcrossSessions() {
        newRuntime(2);
        SessionMessageExecutors otherSession = runtime.newSessionExecutors();

        MessageExecutor<String, Void, Void, Void> mine = sessionExecutors.getMessageExecutor(NamespaceA.class, 0);
        MessageExecutor<String, Void, Void, Void> theirs = otherSession.getMessageExecutor(NamespaceA.class, 0);

        assertThat(runAndCaptureThreadName(mine)).endsWith("-0");
        assertThat(runAndCaptureThreadName(theirs)).endsWith("-1");
    }

    @Test
    void releasingASessionExecutorsLeavesTheOtherSessionRunning() {
        newRuntime(1);
        SessionMessageExecutors otherSession = runtime.newSessionExecutors();
        MessageExecutor<String, Void, Void, Void> mine = sessionExecutors.getMessageExecutor(NamespaceA.class, 0);
        MessageExecutor<String, Void, Void, Void> theirs = otherSession.getMessageExecutor(NamespaceA.class, 0);
        runAndCaptureThreadName(mine);

        sessionExecutors.releaseAll(Deadline.of(Duration.ofSeconds(1)));

        assertThatThrownBy(() -> mine.execute((message, p1, p2, p3) -> {
        }, "message", null, null, null))
                .isInstanceOf(IllegalStateException.class);
        // the thread is shared with the session that has not released, so it is still there to run its tasks
        assertThat(runAndCaptureThreadName(theirs)).startsWith("Staffix-messages-executor-");
    }

    @Test
    void anExecutorReleasedIsNotHandedOutAgain() {
        newRuntime(1);
        MessageExecutor<String, Void, Void, Void> executor = sessionExecutors.getMessageExecutor(NamespaceA.class, 0);

        executor.release();

        MessageExecutor<String, Void, Void, Void> replacement = sessionExecutors.getMessageExecutor(NamespaceA.class, 0);
        assertThat(replacement).isNotSameAs(executor);
        assertThat(runAndCaptureThreadName(replacement)).startsWith("Staffix-messages-executor-");
    }

    @Test
    void stopReleasesExecutorsSoTheyWorkAgainAfterRestart() {
        newRuntime(1);

        MessageExecutor<?, ?, ?, ?> beforeStop = sessionExecutors.getMessageExecutor(NamespaceA.class, 0);
        runAndCaptureThreadName((MessageExecutor<String, Void, Void, Void>) beforeStop);

        runtime.stop(Deadline.of(Duration.ofSeconds(1)));
        runtime.start();

        MessageExecutor<String, Void, Void, Void> afterRestart = runtime.newSessionExecutors().getMessageExecutor(NamespaceA.class, 0);

        assertThat(afterRestart).isNotNull().isNotSameAs(beforeStop);
        // and its thread runs: a stop that only forgot its executors left the thread believing it still had work, and
        // it never started again
        assertThat(runAndCaptureThreadName(afterRestart)).startsWith("Staffix-messages-executor-");
    }

    private String runAndCaptureThreadName(MessageExecutor<String, Void, Void, Void> executor) {
        AtomicReference<String> threadName = new AtomicReference<>();
        MessageProcessor<String, Void, Void, Void> processor = (message, p1, p2, p3) -> threadName.set(Thread.currentThread().getName());
        boolean enqueued = executor.execute(processor, "message", null, null, null);
        assertThat(enqueued).isTrue();
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAtomic(threadName, org.hamcrest.Matchers.notNullValue());
        return threadName.get();
    }

    private static final class NamespaceA {
    }

    private static final class NamespaceB {
    }
}
