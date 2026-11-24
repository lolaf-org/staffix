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

import lombok.EqualsAndHashCode;
import org.lolaf.staffix.api.msg.MessageType;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.LinkedHashMap;
import java.util.Map;

@EqualsAndHashCode
public abstract class MicrometerInstanceBaseProps {
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
     * Whether to enable the messages.read.latency timer. Defaults to true.
     */
    private boolean readLatencyEnabled = true;
    /**
     * Whether to enable the messages.write.latency timer. Defaults to true.
     */
    private boolean writeLatencyEnabled = true;
    /**
     * Whether to enable the messages.decoding.latency timer. Defaults to false.
     */
    private boolean decodingLatencyEnabled = false;
    /**
     * Whether to enable the messages.encoding.latency timer. Defaults to false.
     */
    private boolean encodingLatencyEnabled = false;

    /**
     * Spring bean name of Consumer of MeterRegistry invoked when the registry stops.
     */
    private String stoppingMeterRegistryConsumerBean;
    /**
     * Spring bean name of Consumer of MeterRegistry invoked when the registry has started.
     */
    private String startedMeterRegistryConsumerBean;

    public TimerSettingsProps getDefaultTimer() {
        return defaultTimer;
    }

    public void setDefaultTimer(TimerSettingsProps defaultTimer) {
        this.defaultTimer = defaultTimer;
    }

    public Map<MessageType, TimerSettingsProps> getBuiltInTimerSettings() {
        return builtInTimerSettings;
    }

    public void setBuiltInTimerSettings(Map<MessageType, TimerSettingsProps> builtInTimerSettings) {
        this.builtInTimerSettings = builtInTimerSettings;
    }

    public Map<String, TimerSettingsProps> getTimerSettings() {
        return timerSettings;
    }

    public void setTimerSettings(Map<String, TimerSettingsProps> timerSettings) {
        this.timerSettings = timerSettings;
    }

    public boolean isReadLatencyEnabled() {
        return readLatencyEnabled;
    }

    public void setReadLatencyEnabled(boolean readLatencyEnabled) {
        this.readLatencyEnabled = readLatencyEnabled;
    }

    public boolean isWriteLatencyEnabled() {
        return writeLatencyEnabled;
    }

    public void setWriteLatencyEnabled(boolean writeLatencyEnabled) {
        this.writeLatencyEnabled = writeLatencyEnabled;
    }

    public boolean isDecodingLatencyEnabled() {
        return decodingLatencyEnabled;
    }

    public void setDecodingLatencyEnabled(boolean decodingLatencyEnabled) {
        this.decodingLatencyEnabled = decodingLatencyEnabled;
    }

    public boolean isEncodingLatencyEnabled() {
        return encodingLatencyEnabled;
    }

    public void setEncodingLatencyEnabled(boolean encodingLatencyEnabled) {
        this.encodingLatencyEnabled = encodingLatencyEnabled;
    }

    public String getStoppingMeterRegistryConsumerBean() {
        return stoppingMeterRegistryConsumerBean;
    }

    public void setStoppingMeterRegistryConsumerBean(String stoppingMeterRegistryConsumerBean) {
        this.stoppingMeterRegistryConsumerBean = stoppingMeterRegistryConsumerBean;
    }

    public String getStartedMeterRegistryConsumerBean() {
        return startedMeterRegistryConsumerBean;
    }

    public void setStartedMeterRegistryConsumerBean(String startedMeterRegistryConsumerBean) {
        this.startedMeterRegistryConsumerBean = startedMeterRegistryConsumerBean;
    }
}
