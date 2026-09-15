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
package org.lolaf.staffix.plugins.async.spring;

import lombok.Data;
import org.lolaf.staffix.plugins.async.BackpressurePolicy;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

/**
 * One configured instance of the asynchronous session plugin wrapper: its instance id and the settings a session naming that id gets.
 */
@Data
@ConfigurationPropertiesSource
public class AsyncPluginEntryProps {
    /**
     * Key into any other sessions-plugin contributor (e.g. an entry under
     * {@code staffix.monitoring-micrometer.instances} or {@code staffix.tracing.otel.instances}). The
     * async wrapper replaces the wrapped entry in the registry; the wrapped plugin's {@code instance-id}
     * stays in effect, so existing session references keep matching through the wrapper.
     */
    private String wraps;

    /**
     * Number of consumer threads draining per-session queues, round-robin-assigned.
     */
    private Integer consumerThreadPoolSize;

    /**
     * Capacity of each per-session ring buffer. Must be a power of two.
     */
    private Integer queueSize;

    /**
     * Behaviour when a session queue is full.
     */
    private BackpressurePolicy backpressurePolicy;

    /**
     * Spring bean name of a {@code Supplier<IdleStrategy>} used by the consumer threads.
     */
    private String consumerIdleStrategySupplierBean;

    /**
     * Spring bean name of a {@code Supplier<IdleStrategy>} used by producers waiting for a free slot.
     */
    private String producerIdleStrategySupplierBean;
}
