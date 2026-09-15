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
package org.lolaf.staffix.monitoring.micrometer.otlp;

import io.micrometer.registry.otlp.AggregationTemporality;
import io.micrometer.registry.otlp.HistogramFlavor;
import io.micrometer.registry.otlp.OtlpConfig;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.experimental.SuperBuilder;
import org.lolaf.staffix.api.http.HttpSender;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.http.jdk.JdkHttpSender;
import org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManagerSettings;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The collector endpoint, the push interval, and which HTTP client carries it.
 */
@Getter
@SuperBuilder(toBuilder = true)
public class OtlpMicrometerMonitoringManagerSettings extends MicrometerMonitoringManagerSettings {

    /**
     * Overrides the generated OtlpConfig from settings
     */
    private final OtlpConfig otlpConfig;

    /**
     * HTTP OTLP endpoint url
     */
    private final String otlpEndpointUrl;

    /**
     * Builds the HTTP sender the metrics are published through, from the settings assembled by the
     * manager.
     *
     * <p>The sender is built when the registry starts and closed when it stops.
     */
    @Builder.Default
    private final Function<HttpSenderSettings, HttpSender> httpSenderFactory = JdkHttpSender::new;

    /**
     * Everything else the HTTP sender binds: timeouts, TLS context, proxy and authenticator.
     */
    @Builder.Default
    private final HttpSenderSettings httpSenderSettings = HttpSenderSettings.builder().build();

    /**
     * How often metrics are pushed to the collector.
     */
    @Builder.Default
    private final Duration step = Duration.ofSeconds(10);

    /**
     * The unit durations are reported in.
     */
    @Builder.Default
    private final TimeUnit baseTimeUnit = TimeUnit.MILLISECONDS;

    /**
     * Extra HTTP headers on each push, typically an API key.
     */
    @Singular
    private final Map<String, String> headers;

    /**
     * Otlp resource attributes to send over the wire such as "service.name"
     */
    @Singular
    private final Map<String, String> resourceAttributes;

    /**
     * Aggregation temporality of, some metrics backed may  ot work depending how you set this,
     * I.E Prometheus require AggregationTemporality.CUMULATIVE
     */
    @Builder.Default
    private final AggregationTemporality aggregationTemporality = AggregationTemporality.CUMULATIVE;

    /**
     * Which histogram representation the collector is sent.
     */
    @Builder.Default
    private final HistogramFlavor histogramFlavor = HistogramFlavor.EXPLICIT_BUCKET_HISTOGRAM;

}
