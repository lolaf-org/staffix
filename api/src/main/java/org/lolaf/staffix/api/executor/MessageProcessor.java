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

/**
 * Processes a single message on a {@link MessageExecutor}'s executor thread.
 * <p>
 * Implementations encapsulate the message-handling logic submitted through
 * {@link MessageExecutor#execute(MessageProcessor, Object, Object, Object, Object) MessageExecutor.execute}. The
 * message and up to three parameters are supplied at execution time rather than captured by the processor, so a single
 * stateless processor instance can be reused across many tasks without per-task allocation on the hot path.
 * <p>
 * {@link #process(Object, Object, Object, Object)} is always invoked on the executor thread bound to the routing key
 * the task was submitted under. Tasks sharing a routing key are processed serially in submission order, so a processor
 * need not guard against concurrent invocation for a given key; implementations should nonetheless avoid blocking, as
 * doing so stalls every other task queued on the same executor thread.
 *
 * @param <M>  the message type
 * @param <P1> the type of the first parameter
 * @param <P2> the type of the second parameter
 * @param <P3> the type of the third parameter
 * @see MessageExecutor
 */
@FunctionalInterface
public interface MessageProcessor<M, P1, P2, P3> {

    /**
     * Processes the given message together with its associated parameters.
     *
     * @param message the message to process
     * @param param1  the first parameter
     * @param param2  the second parameter
     * @param param3  the third parameter
     */
    void process(M message, P1 param1, P2 param2, P3 param3);
}
