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
package org.lolaf.staffix.monitoring.micrometer.otlp.spring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.registry.otlp.OtlpConfig;
import org.lolaf.staffix.api.http.HttpSender;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;
import org.lolaf.staffix.monitoring.micrometer.otlp.OtlpMicrometerMonitoringManagerSettings;
import org.lolaf.staffix.spring.boot.spi.BeanRef;
import org.lolaf.staffix.spring.boot.spi.FixSessionsPluginSettingsContributor;
import org.springframework.context.ApplicationContext;

import java.net.Authenticator;
import java.net.ProxySelector;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Contributes the OTLP meter registry's settings to the engine being built, so a session can name it in
 * configuration rather than the application wiring it.
 */
public class OtlpMicrometerMonitoringContributor implements FixSessionsPluginSettingsContributor {

    private final OtlpMicrometerMonitoringProps props;
    private final ApplicationContext ctx;

    public OtlpMicrometerMonitoringContributor(OtlpMicrometerMonitoringProps props, ApplicationContext ctx) {
        this.props = props;
        this.ctx = ctx;
    }

    @Override
    public void contribute(Map<String, FixSessionsPluginSettings<?>> registry) {
        if (props.getInstances().isEmpty()) {
            return;
        }
        props.getInstances().forEach((instanceId, ip) -> {
            OtlpMicrometerMonitoringManagerSettings.OtlpMicrometerMonitoringManagerSettingsBuilder<?, ?> b =
                    OtlpMicrometerMonitoringManagerSettings.builder()
                            .instanceId(instanceId)
                            .readLatencyEnabled(ip.isReadLatencyEnabled())
                            .writeLatencyEnabled(ip.isWriteLatencyEnabled())
                            .decodingLatencyEnabled(ip.isDecodingLatencyEnabled())
                            .encodingLatencyEnabled(ip.isEncodingLatencyEnabled());
            if (ip.getDefaultTimer() != null) b.defaultTimersSettings(ip.getDefaultTimer().toSettings());
            ip.getBuiltInTimerSettings().forEach((mt, t) -> b.builtInTimerSetting(mt, t.toSettings()));
            ip.getTimerSettings().forEach((name, t) -> b.timerSetting(name, t.toSettings()));
            if (ip.getOtlpEndpointUrl() != null) b.otlpEndpointUrl(ip.getOtlpEndpointUrl());
            if (ip.getStep() != null) b.step(ip.getStep());
            if (ip.getBaseTimeUnit() != null) b.baseTimeUnit(ip.getBaseTimeUnit());
            if (ip.getAggregationTemporality() != null) b.aggregationTemporality(ip.getAggregationTemporality());
            if (ip.getHistogramFlavor() != null) b.histogramFlavor(ip.getHistogramFlavor());
            ip.getHeaders().forEach(b::header);
            ip.getResourceAttributes().forEach(b::resourceAttribute);

            String path = "staffix.monitoring.otlp.instances." + instanceId;
            BeanRef.<Consumer<MeterRegistry>>resolveOptional(ctx, ip.getStoppingMeterRegistryConsumerBean(), Consumer.class, path + ".stopping-meter-registry-consumer-bean")
                    .ifPresent(b::stoppingMeterRegistryConsumer);
            BeanRef.<Consumer<MeterRegistry>>resolveOptional(ctx, ip.getStartedMeterRegistryConsumerBean(), Consumer.class, path + ".started-meter-registry-consumer-bean")
                    .ifPresent(b::startedMeterRegistryConsumer);
            BeanRef.<OtlpConfig>resolveOptional(ctx, ip.getOtlpConfigBean(), OtlpConfig.class, path + ".otlp-config-bean")
                    .ifPresent(b::otlpConfig);
            BeanRef.<Function<HttpSenderSettings, HttpSender>>resolveOptional(ctx, ip.getHttpSenderFactoryBean(), Function.class, path + ".http-sender-factory-bean")
                    .ifPresent(b::httpSenderFactory);
            b.httpSenderSettings(httpSenderSettings(ip, path));

            if (registry.putIfAbsent(instanceId, b.build()) != null) {
                throw new IllegalStateException("staffix.monitoring.otlp.instances." + instanceId
                        + " collides with another sessions plugin contributor for the same key");
            }
        });
    }

    /**
     * Everything the HTTP sender binds bar the endpoint and the content type, which the manager sets
     * itself. Request headers are not here: they belong to the OTLP config, which micrometer sends per
     * request.
     */
    private HttpSenderSettings httpSenderSettings(OtlpInstanceProps ip, String path) {
        HttpSenderSettings.HttpSenderSettingsBuilder http = HttpSenderSettings.builder();
        if (ip.getConnectTimeout() != null) http.connectTimeout(ip.getConnectTimeout());
        if (ip.getRequestTimeout() != null) http.requestTimeout(ip.getRequestTimeout());
        BeanRef.<ProxySelector>resolveOptional(ctx, ip.getProxySelectorBean(), ProxySelector.class, path + ".proxy-selector-bean")
                .ifPresent(http::proxySelector);
        BeanRef.<Authenticator>resolveOptional(ctx, ip.getAuthenticatorBean(), Authenticator.class, path + ".authenticator-bean")
                .ifPresent(http::authenticator);
        return http.build();
    }
}
