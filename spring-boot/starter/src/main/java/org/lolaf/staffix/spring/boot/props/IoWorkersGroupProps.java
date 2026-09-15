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
     * Names this worker group, so a session can select it.
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
    private Duration workersLoadEMATimeWindow;
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

    public enum IoWorkerLoadBalancer {MIN_REGISTERED_SESSIONS, MIN_IO_THREAD_LOAD, NAPI_ID}

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
         * Wakeup count when {@code selectStrategy=WAKEUP}.
         */
        private int wakeupCount = 10;

        public enum SelectStrategy {WAKEUP, BUSY_SPIN, YIELDING, BACKOFF}
    }
}
