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
import org.lolaf.betty.api.io.IOWorker;
import org.lolaf.betty.api.io.IOWorkerLoadBalancer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A group of IO threads, as properties.
 *
 * <p>Declared separately from the initiators and acceptors that use it because one group is normally shared:
 * a thread per session stops scaling well before a venue stops adding sessions.
 */
@Data
public class IoWorkersGroupProps {

    /**
     * Names this worker group, and so its threads in logs and thread dumps.
     */
    private String id = "default";
    /**
     * The thread groups in it, for pinning different sessions to different threads.
     */
    private List<IoThreadGroupProps> threadGroups = new ArrayList<>();
    /**
     * Whether the platform's optimised selector is used where one is available.
     */
    private Boolean optimizedSelector;
    /**
     * The window load is averaged over when deciding to rebalance.
     */
    private Duration workersLoadEmaTimeWindow;
    /**
     * How sessions are spread across workers.
     */
    private IoWorkerLoadBalancer ioWorkerLoadBalancer;
    /**
     * Bean name of a custom {@code io.betty.api.io.IOWorkerLoadBalancer}. Takes precedence over {@link #ioWorkerLoadBalancer} when set.
     */
    private String ioWorkerLoadBalancerBean;
    /**
     * Interval at which the configured {@link IOWorkerLoadBalancer#rebalance(IOWorker[])} is invoked
     * to redistribute existing sessions across IO workers (transparent migration). When null/zero, no rebalancing scheduler is created.
     */
    private Duration ioWorkersRebalanceInterval;
    /**
     * Bean name of a custom {@code java.nio.channels.spi.SelectorProvider}.
     */
    private String selectorProviderBean;
    /**
     * Bean name of a custom {@code io.betty.api.stats.IOWorkerStats.IOWorkerStatsProvider}.
     */
    private String ioWorkerStatisticsProviderBean;

    public enum IoWorkerLoadBalancer {
        /**
         * Places a session on the worker with the fewest sessions. The default: cheap, and right when sessions cost
         * roughly the same.
         */
        MIN_REGISTERED_SESSIONS,
        /**
         * Places a session on the least busy worker, by measured IO time. For skewed workloads; measuring costs a
         * clock read per IO operation.
         */
        MIN_IO_THREAD_LOAD,
        /**
         * Groups the sessions fed by the same NIC receive queue on one worker. Linux only, JDK 15 or later, and
         * useful only with the workers pinned to the queues' CPUs.
         */
        NAPI_ID
    }

    /**
     * A set of IO threads sharing one select strategy. Several let one group mix, say, a busy-spinning thread for the
     * sessions that matter with sleeping ones for the rest.
     */
    @Data
    public static class IoThreadGroupProps {

        /**
         * Names this thread group, which appears in thread names.
         */
        private String name = "default";
        /**
         * How many IO threads the group runs.
         */
        private int ioThreadCount = 1;
        /**
         * What an IO thread does with no work: wake up on a selector, or spin. Spinning trades a core for latency.
         */
        private SelectStrategy selectStrategy = SelectStrategy.WAKEUP;
        /**
         * With {@code WAKEUP}, the longest an idle thread blocks on the selector, in milliseconds. It bounds how late
         * a missed wakeup is noticed, not the latency of ordinary traffic.
         */
        private int selectTimeoutMillis = 10;

        public enum SelectStrategy {
            /**
             * Blocks on the selector and gives the core back while idle. The default, and the right choice unless a
             * core can be spent on one IO thread.
             */
            WAKEUP,
            /**
             * Polls the selector and spins in between: the lowest latency, and a whole core burnt.
             */
            BUSY_SPIN,
            /**
             * Polls the selector and yields in between. Still burns CPU on an idle machine.
             */
            YIELDING,
            /**
             * Polls the selector, then spins, yields and parks for longer and longer while nothing happens.
             */
            BACKOFF
        }
    }
}
