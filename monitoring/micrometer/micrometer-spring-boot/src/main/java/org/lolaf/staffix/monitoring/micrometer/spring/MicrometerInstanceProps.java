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
package org.lolaf.staffix.monitoring.micrometer.spring;

import org.springframework.boot.context.properties.NestedConfigurationProperty;
import lombok.Data;
import org.lolaf.staffix.api.msg.MessageType;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One configured instance of the Micrometer monitoring plugin: its instance id and the settings a session naming that id gets.
 */
@Data
@ConfigurationPropertiesSource
public class MicrometerInstanceProps {
    /**
     * Timer settings applied to every timer unless overridden below.
     */
    @NestedConfigurationProperty
    private TimerSettingsProps defaultTimer;
    /**
     * Per-message-type overrides, for measuring one message type more finely than the rest.
     */
    private Map<MessageType, TimerSettingsProps> builtInTimerSettings = new LinkedHashMap<>();
    /**
     * Overrides by timer name, for the timers that are not per message type.
     */
    private Map<String, TimerSettingsProps> timerSettings = new LinkedHashMap<>();
    /**
     * Whether to enable the {@code messages.read.latency} timer. Defaults to {@code true}.
     */
    private boolean readLatencyEnabled = true;
    /**
     * Whether to enable the {@code messages.write.latency} timer. Defaults to {@code true}.
     */
    private boolean writeLatencyEnabled = true;
    /**
     * Whether to enable the {@code messages.decoding.latency} timer. Defaults to {@code false}.
     */
    private boolean decodingLatencyEnabled = false;
    /**
     * Whether to enable the {@code messages.encoding.latency} timer. Defaults to {@code false}.
     */
    private boolean encodingLatencyEnabled = false;

    /**
     * Spring bean name of {@code Consumer<MeterRegistry>} invoked when the registry stops.
     */
    private String stoppingMeterRegistryConsumerBean;
    /**
     * Spring bean name of {@code Consumer<MeterRegistry>} invoked when the registry has started.
     */
    private String startedMeterRegistryConsumerBean;
}
