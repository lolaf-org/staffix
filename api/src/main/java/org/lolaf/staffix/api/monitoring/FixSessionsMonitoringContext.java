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
package org.lolaf.staffix.api.monitoring;

import org.lolaf.staffix.api.session.plugins.PluginContext;

import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * A session's meters, created once and reused.
 *
 * <p>{@link #getTimer} takes the description and tags on every call because the first call creates the meter and
 * later ones return it; passing them each time keeps the caller from having to know which call it is.
 */
public interface FixSessionsMonitoringContext extends PluginContext {

    /**
     * Retrieves a timer to measure time
     * The timer has a slight impact on performance, so it is recommended to use it only when needed.
     * The returned NOOP timer return if monitoring is disabled has however no impact on performances
     *
     * @param id          the timer id
     * @param description the time description
     * @param tags        the timer tags
     * @return a timer instance to measure time
     */
    Timer getTimer(String id, String description, Map<String, String> tags);

    /**
     * Retrieves a gauge the application sets; the same id and tags give the same gauge. The returned NOOP gauge
     * when monitoring is disabled has no impact on performances.
     *
     * @param id          the gauge id
     * @param description the gauge description
     * @param tags        the gauge tags
     * @return a gauge holding the value the next export reads
     */
    Gauge getGauge(String id, String description, Map<String, String> tags);

    /**
     * Registers a gauge read from {@code value} at each export, for a value the application already holds. The
     * supplier runs on the exporter's thread, so it must be thread-safe, cheap and non-blocking; it is kept until the
     * session ends. The returned gauge ignores {@link Gauge#set} and {@link Gauge#add}.
     *
     * @param id          the gauge id
     * @param description the gauge description
     * @param tags        the gauge tags
     * @param value       read at each export
     * @return the registered gauge
     */
    Gauge getGauge(String id, String description, Map<String, String> tags, DoubleSupplier value);

    /**
     * The meters this session publishes: the monitoring's own, and the timers obtained so far from
     * {@link #getTimer(String, String, Map)}.
     */
    List<FixMeterDescriptor> getMeterDescriptors();

}