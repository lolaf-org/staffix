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

import org.lolaf.ringos.idling.IdleStrategy;

/**
 * Hands message-processing tasks off to a dedicated executor thread for asynchronous, single-threaded execution.
 * <p>
 * Each {@code execute} call enqueues a task that pairs a {@link MessageProcessor} with a message and up to three
 * parameters. The task is later dequeued and run by the executor thread bound to this executor, which invokes
 * {@link MessageProcessor#process(Object, Object, Object, Object)} with exactly those arguments. Passing the message
 * and parameters alongside the processor (rather than capturing them in a lambda or closure) lets callers reuse a
 * single stateless processor instance and avoid per-task allocation on the hot path.
 * <p>
 * An executor is obtained for a given routing key, and all tasks submitted to the same executor are processed
 * serially in submission order on the same thread. This preserves per-key ordering while allowing different keys to be
 * processed in parallel across distinct executor threads.
 * <p>
 * Instances are not meant to be shared beyond their intended routing key; call {@link #release()} once the executor is
 * no longer needed so its underlying thread resources can be reclaimed or reassigned.
 *
 * @param <M>  the message type passed to the processor
 * @param <P1> the type of the first parameter passed to the processor
 * @param <P2> the type of the second parameter passed to the processor
 * @param <P3> the type of the third parameter passed to the processor
 * @see MessageProcessor
 */
public interface MessageExecutor<M, P1, P2, P3> {

    /**
     * Enqueues a task that runs {@code processor} with the given message and parameters on the executor thread.
     * <p>
     * If the task queue is full this call blocks, applying {@code queueFullIdleStrategy} between attempts, until space
     * becomes available and the task is enqueued. Use this overload when no task may be dropped.
     *
     * @param processor             the processor invoked on the executor thread
     * @param message               the message to process
     * @param param1                the first parameter forwarded to the processor
     * @param param2                the second parameter forwarded to the processor
     * @param param3                the third parameter forwarded to the processor
     * @param queueFullIdleStrategy the idle strategy applied while waiting for queue space when the queue is full
     * @throws IllegalStateException if this executor has already been {@link #release() released}
     */
    void execute(MessageProcessor<M, P1, P2, P3> processor, M message, P1 param1, P2 param2, P3 param3, IdleStrategy queueFullIdleStrategy);

    /**
     * Enqueues a task that runs {@code processor} with the given message and parameters on the executor thread,
     * without blocking.
     * <p>
     * If the task queue is full the task is rejected rather than waiting for space. Use this overload when dropping a
     * task is preferable to blocking the submitting thread.
     *
     * @param processor the processor invoked on the executor thread
     * @param message   the message to process
     * @param param1    the first parameter forwarded to the processor
     * @param param2    the second parameter forwarded to the processor
     * @param param3    the third parameter forwarded to the processor
     * @return {@code true} if the task was enqueued, or {@code false} if it was rejected because the queue was full
     * @throws IllegalStateException if this executor has already been {@link #release() released}
     */
    boolean execute(MessageProcessor<M, P1, P2, P3> processor, M message, P1 param1, P2 param2, P3 param3);

    /**
     * Releases this executor once it is no longer needed, allowing the underlying executor-thread resources to be
     * reclaimed or reassigned to another routing key. After release, any further {@code execute} call throws
     * {@link IllegalStateException}.
     */
    void release();
}