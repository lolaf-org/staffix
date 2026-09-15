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
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.api.executor.MessageExecutor;
import org.lolaf.staffix.api.executor.MessageProcessor;

import java.util.function.IntSupplier;

/**
 * Processes messages on several threads while keeping related ones in order.
 *
 * <p>A message is routed by the hash of a chosen field, so everything sharing that value - one symbol, one
 * account - stays sequential on one thread while unrelated work runs in parallel.
 */
public class MessageExecutorImpl<M, P1, P2, P3> implements MessageExecutor<M, P1, P2, P3>, IntSupplier {

    private final MessageExecutor<M, P1, P2, P3> underlyingExecutor;
    private final Releaser releaser;
    private final int index;
    private volatile boolean released;

    public MessageExecutorImpl(MessageExecutor<M, P1, P2, P3> underlyingExecutor, int index, Releaser releaser) {
        this.underlyingExecutor = underlyingExecutor;
        this.index = index;
        this.releaser = releaser;
    }

    @Override
    public boolean execute(MessageProcessor<M, P1, P2, P3> processor, M message, P1 param1, P2 param2, P3 param3) {
        failIfReleased();
        return underlyingExecutor.execute(processor, message, param1, param2, param3);
    }

    @Override
    public void execute(MessageProcessor<M, P1, P2, P3> processor, M message, P1 param1, P2 param2, P3 param3, IdleStrategy queueFullIdleStrategy) {
        failIfReleased();
        underlyingExecutor.execute(processor, message, param1, param2, param3, queueFullIdleStrategy);
    }

    private void failIfReleased() {
        if (released) {
            throw new IllegalStateException("Executor has been released");
        }
    }

    /**
     * Releases without waiting on the thread behind this executor: an application letting go of a routing key it no
     * longer needs is not shutting anything down. A session stopping releases with a deadline instead, through
     * {@link #release(Deadline)}.
     */
    @Override
    public void release() {
        release(Deadline.immediate());
    }

    /**
     * @param deadline how long the executor thread may be waited on, when releasing this executor is what leaves it
     *                 with nothing assigned and stops it. Elapsed or immediate means the tasks it still holds are
     *                 flushed without this call waiting for them.
     */
    public void release(Deadline deadline) {
        // flagged before the release runs: a submitter racing this gets the IllegalStateException rather than a task
        // enqueued onto a thread that is on its way out
        released = true;
        releaser.release(this, deadline);
    }

    @Override
    public int getAsInt() {
        return index;
    }

    /**
     * What a release has to do besides flagging the executor: take it out of the session's executors and tell the
     * thread it was assigned to, in that order.
     */
    public interface Releaser {

        void release(MessageExecutorImpl<?, ?, ?, ?> executor, Deadline deadline);
    }
}
