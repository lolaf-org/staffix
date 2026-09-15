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

import io.micrometer.registry.otlp.AggregationTemporality;
import io.micrometer.registry.otlp.HistogramFlavor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.lolaf.staffix.monitoring.micrometer.spring.MicrometerInstanceProps;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One configured instance of the OTLP meter registry: its instance id and the settings a session naming that id gets.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ConfigurationPropertiesSource
public class OtlpInstanceProps extends MicrometerInstanceProps {
    /**
     * The collector's address.
     */
    private String otlpEndpointUrl;
    /**
     * How often metrics are pushed to the collector.
     */
    private Duration step;
    /**
     * The unit durations are reported in.
     */
    private TimeUnit baseTimeUnit;
    /**
     * Whether counters are reported as cumulative totals or as deltas since the last push.
     */
    private AggregationTemporality aggregationTemporality;
    /**
     * Which histogram representation the collector is sent.
     */
    private HistogramFlavor histogramFlavor;
    /**
     * Additional headers to provide to the
     */
    private Map<String, String> headers = new LinkedHashMap<>();
    /**
     * Attributes attached to everything this process exports, such as service name and environment.
     */
    private Map<String, String> resourceAttributes = new LinkedHashMap<>();

    /**
     * Spring bean name of an {@code io.micrometer.registry.otlp.OtlpConfig} that overrides the generated config.
     */
    private String otlpConfigBean;
    /**
     * Timeout for establishing a connection to the collector.
     */
    private Duration connectTimeout;
    /**
     * Timeout for a whole publish.
     */
    private Duration requestTimeout;

    /**
     * Spring bean name of a {@code Function<HttpSenderSettings, HttpSender>} building the HTTP sender.
     * Defaults to the JDK client, which needs no dependency but is the most expensive of them over TLS;
     * point this at {@code OkHttpSender::new} for an HTTPS collector.
     */
    private String httpSenderFactoryBean;
    /**
     * Spring bean name of {@link java.net.ProxySelector} for the OTLP HTTP client.
     */
    private String proxySelectorBean;
    /**
     * Spring bean name of {@link java.net.Authenticator} for the OTLP HTTP client.
     */
    private String authenticatorBean;
}
