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
package org.lolaf.staffix.spring.boot.props;

import lombok.Data;

/**
 * A message executor as properties: how many threads, how deep the queues, and which field routes a message to
 * one of them.
 */
@Data
public class MessageExecutorProps {

    /**
     * How many threads process messages. One keeps ordering trivial; more helps only if the routing field spreads
     * work across them.
     */
    private int executorsThreadsCount = 1;
    /**
     * Depth of each thread's queue, and so how much a slow consumer may buffer before the session thread waits.
     */
    private int queueSizePerThread = 64;
    /**
     * What a worker does with an empty queue.
     */
    private IdleStrategy idleStrategy = IdleStrategy.WAIT_NOTIFY;

    public enum IdleStrategy {WAIT_NOTIFY, BUSY_SPIN, YIELDING, BACKOFF}
}
