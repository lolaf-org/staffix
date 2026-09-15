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
package org.lolaf.staffix.api.executor;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.ringos.idling.WaitNotifyIdleStrategy;
import org.lolaf.ringos.threading.FastThreadLocalThread;
import org.lolaf.staffix.api.InstanceIdSupplier;

import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

import static org.lolaf.staffix.api.InstanceProvider.DEFAULT_INSTANCE_ID;

/**
 * Configures a message executor: how many threads process messages, and how deep the queue in front of them is.
 *
 * <p>Messages are routed to a thread by the hash of a chosen field, so everything sharing that field's value
 * stays in order on one thread while unrelated work runs in parallel.
 */
@Getter
@SuperBuilder(toBuilder = true)
public class MessageExecutorSettings implements InstanceIdSupplier {

    /**
     * Names this instance, so a session can select it by id when more than one is configured.
     */
    @Builder.Default
    private final String instanceId = DEFAULT_INSTANCE_ID;
    /**
     * Thread factory for the executors thread, if you provide one please try to return a {@link FastThreadLocalThread} to improve perfs
     */
    /**
     * Builds the worker threads. The default produces threads that support ringos' fast thread locals, which the
     * serdes rely on for their zero-allocation paths.
     */
    @Builder.Default
    private final ThreadFactory threadFactory = FastThreadLocalThread::new;
    /**
     * How many threads process messages. One by default, which keeps ordering trivial; more than one only helps if
     * the routing field spreads work across them.
     */
    @Builder.Default
    private int executorsThreadsCount = 1;
    /**
     * Size of the ring buffer queue allocated per executor thread. Must be a power of two.
     * Larger values reduce the risk of back-pressure under burst load at the cost of additional memory.
     */
    /**
     * The depth of each thread's queue, and so how much a slow consumer may buffer before the session thread is made
     * to wait.
     */
    @Builder.Default
    private int queueSizePerThread = 64;

    /**
     * Idle strategy to use when the thread is waiting for new tasks to process,
     * look at {@link org.lolaf.ringos.idling.BusySpinIdleStrategy} and {@link org.lolaf.ringos.idling.YieldingIdleStrategy} for lowest latency possible
     * or to {@link org.lolaf.ringos.idling.BackoffIdleStrategy} for a compromise with the default {@link org.lolaf.ringos.idling.WaitNotifyIdleStrategy} setting
     */
    /**
     * What a worker does with an empty queue. The default parks; a spinning strategy trades a core for latency and is
     * only worth it on a pinned thread.
     */
    @Builder.Default
    private Supplier<IdleStrategy> idleStrategy = WaitNotifyIdleStrategy::new;

}