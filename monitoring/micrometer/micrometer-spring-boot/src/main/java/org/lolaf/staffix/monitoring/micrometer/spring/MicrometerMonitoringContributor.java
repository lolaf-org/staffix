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

import io.micrometer.core.instrument.MeterRegistry;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;
import org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManagerSettings;
import org.lolaf.staffix.spring.boot.spi.BeanRef;
import org.lolaf.staffix.spring.boot.spi.FixSessionsPluginSettingsContributor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Contributes the Micrometer monitoring plugin's settings to the engine being built, so a session can name it in
 * configuration rather than the application wiring it.
 */
public class MicrometerMonitoringContributor implements FixSessionsPluginSettingsContributor {

    private final MicrometerMonitoringProps props;
    private final ApplicationContext ctx;
    private final ObjectProvider<MeterRegistry> meterRegistries;

    public MicrometerMonitoringContributor(MicrometerMonitoringProps props,
                                           ApplicationContext ctx,
                                           ObjectProvider<MeterRegistry> meterRegistries) {
        this.props = props;
        this.ctx = ctx;
        this.meterRegistries = meterRegistries;
    }

    @Override
    public void contribute(Map<String, FixSessionsPluginSettings<?>> registry) {
        if (props.getInstances().isEmpty()) {
            return;
        }
        Supplier<MeterRegistry> registrySupplier = () -> {
            if (props.getMeterRegistryBean() != null && !props.getMeterRegistryBean().isBlank()) {
                return ctx.getBean(props.getMeterRegistryBean(), MeterRegistry.class);
            }
            MeterRegistry mr = meterRegistries.getIfAvailable();
            if (mr == null) {
                throw new IllegalStateException("staffix.monitoring is enabled but no MeterRegistry bean was found in the context;"
                        + " declare one or set staffix.monitoring.meter-registry-bean");
            }
            return mr;
        };
        props.getInstances().forEach((instanceId, ip) -> {
            MicrometerMonitoringManagerSettings.MicrometerMonitoringManagerSettingsBuilder<?, ?> b = MicrometerMonitoringManagerSettings.builder()
                    .instanceId(instanceId)
                    .meterRegistrySupplier(registrySupplier)
                    .readLatencyEnabled(ip.isReadLatencyEnabled())
                    .writeLatencyEnabled(ip.isWriteLatencyEnabled())
                    .decodingLatencyEnabled(ip.isDecodingLatencyEnabled())
                    .encodingLatencyEnabled(ip.isEncodingLatencyEnabled());
            if (ip.getDefaultTimer() != null) b.defaultTimersSettings(ip.getDefaultTimer().toSettings());
            ip.getBuiltInTimerSettings().forEach((mt, t) -> b.builtInTimerSetting(mt, t.toSettings()));
            ip.getTimerSettings().forEach((name, t) -> b.timerSetting(name, t.toSettings()));
            String path = "staffix.monitoring.instances." + instanceId;
            BeanRef.<Consumer<MeterRegistry>>resolveOptional(ctx, ip.getStoppingMeterRegistryConsumerBean(), Consumer.class, path + ".stopping-meter-registry-consumer-bean")
                    .ifPresent(b::stoppingMeterRegistryConsumer);
            BeanRef.<Consumer<MeterRegistry>>resolveOptional(ctx, ip.getStartedMeterRegistryConsumerBean(), Consumer.class, path + ".started-meter-registry-consumer-bean")
                    .ifPresent(b::startedMeterRegistryConsumer);
            if (registry.putIfAbsent(instanceId, b.build()) != null) {
                throw new IllegalStateException("staffix.monitoring.instances." + instanceId
                        + " collides with another sessions plugin contributor for the same key");
            }
        });
    }
}
