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

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A meter a session's monitoring publishes, so an admin tool can offer it and query it from the metrics backend.
 */
@Value
@Builder(toBuilder = true)
public class FixMeterDescriptor {

    /**
     * The meter's name as the monitoring plugin publishes it, such as {@code messages.read.latency}.
     */
    String name;
    Type type;
    String description;
    /**
     * Tags beyond the session's own ({@link FixMonitoringAttributes}), which split the meter into several series,
     * such as {@code fix.msg.type}.
     */
    @Singular
    List<String> tagKeys;
    /**
     * A gauge's unit; null for a timer, recorded in the monitoring plugin's base time unit, or a gauge without one.
     */
    TimeUnit unit;
    /**
     * Whether the backend can compute quantiles of this timer, which needs its percentile histogram published.
     */
    boolean percentiles;
    /**
     * Registered by the application through {@link FixSessionsMonitoringContext#getTimer}, not by the monitoring itself.
     */
    boolean custom;

    public enum Type {
        TIMER,
        GAUGE
    }
}
