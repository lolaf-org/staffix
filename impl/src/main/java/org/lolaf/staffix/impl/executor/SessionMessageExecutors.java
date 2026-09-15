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

import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.executor.MessageExecutor;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link MessageExecutor} instances of one FIX session, obtained from
 * {@link MessageExecutorsRuntime#newSessionExecutors()} and owned by that session for as long as it exists.
 * <p>
 * Two things follow from a session having its own, rather than looking executors up in a map shared by every session
 * of a connector:
 * <ul>
 *     <li><b>The message path stops paying for the lookup.</b> A routing key is a namespace and an index, and the
 *     namespace is fixed per call site - a decoder passes its own class, message after message - so the last namespace
 *     resolved is cached and the steady-state cost of {@link #getMessageExecutor(Class, int)} is a volatile read, a
 *     reference comparison and an array index. Nothing is locked and nothing is allocated. A namespace switch costs one
 *     map lookup, and creating an executor is the only path that takes a lock.</li>
 *     <li><b>The session can let go of them.</b> {@link #releaseAll(Deadline)} releases exactly the executors of one
 *     session, which is what stops the threads behind them when the last session using them goes, rather than leaving
 *     that to the connector shutting its whole runtime down.</li>
 * </ul>
 * Executors are per session and per routing key: the same key on two sessions resolves to two executors, so ordering
 * is preserved per key <b>within</b> a session and two sessions never share a queue.
 */
public class SessionMessageExecutors {

    private static final MessageExecutorImpl<?, ?, ?, ?>[] NONE = new MessageExecutorImpl[0];

    private final MessageExecutorsRuntime runtime;
    private final Map<Class<?>, NamespaceExecutors> executorsByNamespace;
    /**
     * The last namespace resolved, and the fast path of this class. Volatile and holding its own namespace so that a
     * reader either sees a namespace that matches - and then an executors array published before it - or misses and
     * goes through the map: a pair of plain fields could be seen half updated and hand out another namespace's
     * executor. Republished rather than allocated on a miss, so alternating namespaces costs nothing per message.
     */
    private volatile NamespaceExecutors lastResolved;

    SessionMessageExecutors(MessageExecutorsRuntime runtime) {
        this.runtime = runtime;
        this.executorsByNamespace = new ConcurrentHashMap<>();
    }

    /**
     * The executor for one routing key of this session, created on first use.
     *
     * @param routingNamespace namespaces the index space, so the same index under two namespaces is two executors
     * @param routingKeyIndex  the index within that namespace, from zero upwards
     * @throws IllegalArgumentException if the index is negative
     */
    @SuppressWarnings("unchecked")
    public <M, P1, P2, P3> MessageExecutor<M, P1, P2, P3> getMessageExecutor(Class<?> routingNamespace, int routingKeyIndex) {
        NamespaceExecutors executors = lastResolved;
        if (executors == null || executors.namespace != routingNamespace) {
            executors = executorsByNamespace.computeIfAbsent(routingNamespace, NamespaceExecutors::new);
            lastResolved = executors;
        }
        return (MessageExecutor<M, P1, P2, P3>) executors.get(routingKeyIndex);
    }

    /**
     * Releases every executor this session holds, waiting within {@code deadline} for the executor threads that have
     * nothing left assigned to them to finish what they were given.
     * <p>
     * Called when the session stops, before it releases its message store and its logger: a task still queued on an
     * executor thread can reach the application, and through it the session.
     */
    public void releaseAll(Deadline deadline) {
        executorsByNamespace.values().forEach(executors -> executors.releaseAll(deadline));
        executorsByNamespace.clear();
        lastResolved = null;
        runtime.onSessionExecutorsReleased(this);
    }

    /**
     * The executors of one namespace, indexed by routing key.
     * <p>
     * The array is published volatile and replaced rather than mutated, which is what lets
     * {@link #get(int)} read it without a lock while another thread is adding to it. Only creation and release
     * synchronize, and both are rare: a routing key is created once and released once.
     */
    private final class NamespaceExecutors {

        private final Class<?> namespace;
        private volatile MessageExecutorImpl<?, ?, ?, ?>[] executors = NONE;

        private NamespaceExecutors(Class<?> namespace) {
            this.namespace = namespace;
        }

        private MessageExecutorImpl<?, ?, ?, ?> get(int index) {
            MessageExecutorImpl<?, ?, ?, ?>[] current = executors;
            if (index >= 0 && index < current.length) {
                MessageExecutorImpl<?, ?, ?, ?> executor = current[index];
                if (executor != null) {
                    return executor;
                }
            }
            return create(index);
        }

        private synchronized MessageExecutorImpl<?, ?, ?, ?> create(int index) {
            if (index < 0) {
                // the routing key index is an array slot: a negative one used to have an executor created for it and
                // then dropped, so every message got a new executor of its own - no ordering, and a thread assignment
                // leaked per message
                throw new IllegalArgumentException("Routing key index must not be negative but was " + index
                        + " for routing namespace " + namespace.getName());
            }
            MessageExecutorImpl<?, ?, ?, ?>[] current = executors;
            if (index < current.length && current[index] != null) {
                // another thread created it while this one waited for the lock
                return current[index];
            }
            runtime.onSessionExecutorCreated(SessionMessageExecutors.this);
            ExecutorThread executorThread = runtime.nextExecutorThread();
            MessageExecutorImpl<?, ?, ?, ?> executor = new MessageExecutorImpl<>(executorThread, index,
                    (released, deadline) -> release(released, executorThread, deadline));
            MessageExecutorImpl<?, ?, ?, ?>[] grown = current.length > index
                    ? current.clone()
                    : Arrays.copyOf(current, index + 1);
            grown[index] = executor;
            executors = grown;
            executorThread.onMessageExecutorAssigned(executor);
            return executor;
        }

        /**
         * Takes a released executor out of the array so nothing is handed it again, then tells its thread, which stops
         * once nothing is assigned to it any more.
         */
        private synchronized void release(MessageExecutorImpl<?, ?, ?, ?> executor, ExecutorThread executorThread, Deadline deadline) {
            int index = executor.getAsInt();
            MessageExecutorImpl<?, ?, ?, ?>[] current = executors;
            if (index < current.length && current[index] == executor) {
                MessageExecutorImpl<?, ?, ?, ?>[] without = current.clone();
                without[index] = null;
                executors = without;
            }
            executorThread.onMessageExecutorReleased(executor, deadline);
        }

        private synchronized void releaseAll(Deadline deadline) {
            MessageExecutorImpl<?, ?, ?, ?>[] current = executors;
            // emptied first: whatever is released below is not to be handed out again, and each release() calls back
            // into release(...) above, which then finds the slot already gone
            executors = NONE;
            for (MessageExecutorImpl<?, ?, ?, ?> executor : current) {
                if (executor != null) {
                    executor.release(deadline);
                }
            }
        }
    }
}
