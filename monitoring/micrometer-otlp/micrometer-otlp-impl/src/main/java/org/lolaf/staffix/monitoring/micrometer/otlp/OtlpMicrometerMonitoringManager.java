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

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import io.micrometer.registry.otlp.*;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.api.monitoring.FixSessionMonitoringManagerSettings;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager;
import org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManager;
import org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManagerSettings;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes Micrometer metrics to an OpenTelemetry collector over OTLP, through this library's own HTTP clients
 * rather than a second HTTP stack.
 */
@Slf4j
public class OtlpMicrometerMonitoringManager extends MicrometerMonitoringManager {

    /**
     * What micrometer's OTLP sender writes, whatever the sender was configured with.
     */
    private static final String PROTOBUF_CONTENT_TYPE = "application/x-protobuf";

    /**
     * The sender behind the registry, held so that it can be released when the registry stops.
     * Micrometer's own sender interface has no lifecycle, so nothing else would close it.
     */
    private final AtomicReference<MicrometerHttpSenderAdapter> httpSender;

    public OtlpMicrometerMonitoringManager(OtlpMicrometerMonitoringManagerSettings settings) {
        this(settings, new AtomicReference<>());
    }

    private OtlpMicrometerMonitoringManager(OtlpMicrometerMonitoringManagerSettings settings,
                                            AtomicReference<MicrometerHttpSenderAdapter> httpSender) {
        super(transformSettings(settings, httpSender));
        this.httpSender = httpSender;
    }

    private static MicrometerMonitoringManagerSettings transformSettings(
            OtlpMicrometerMonitoringManagerSettings settings,
            AtomicReference<MicrometerHttpSenderAdapter> httpSender) {
        OtlpConfig config = settings.getOtlpConfig() != null
                ? settings.getOtlpConfig() : new OtlpConfig() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public AggregationTemporality aggregationTemporality() {
                return settings.getAggregationTemporality();
            }

            @Override
            public HistogramFlavor histogramFlavor() {
                return settings.getHistogramFlavor();
            }

            @Override
            public Map<String, String> resourceAttributes() {
                if (settings.getResourceAttributes().isEmpty()) {
                    return OtlpConfig.DEFAULT.resourceAttributes();
                }
                return settings.getResourceAttributes();
            }

            @Override
            public Map<String, String> headers() {
                if (settings.getHeaders().isEmpty()) {
                    return OtlpConfig.DEFAULT.headers();
                }
                return settings.getHeaders();
            }

            @Override
            public String url() {
                String endpoint = settings.getOtlpEndpointUrl();
                if (!endpoint.endsWith("/v1/metrics")) {
                    endpoint += "/v1/metrics";
                }
                return endpoint;
            }

            @Override
            public TimeUnit baseTimeUnit() {
                return settings.getBaseTimeUnit();
            }

            @Override
            public Duration step() {
                return settings.getStep();
            }

        };
        return settings.toBuilder().meterRegistrySupplier(() -> OtlpMeterRegistry.builder(config)
                .clock(Clock.SYSTEM)
                .threadFactory(new NamedThreadFactory("otlp-metrics-publisher"))
                .metricsSender(new OtlpHttpMetricsSender(newHttpSender(settings, config, httpSender)))
                .build()).build();
    }

    /**
     * Builds the configured sender, binds it to the address the registry publishes to, and records it
     * so that stopping the manager can close it.
     */
    private static MicrometerHttpSenderAdapter newHttpSender(OtlpMicrometerMonitoringManagerSettings settings,
                                                             OtlpConfig config,
                                                             AtomicReference<MicrometerHttpSenderAdapter> holder) {
        // The endpoint and the content type belong to the registry, not to whoever configured the
        // sender: it publishes protobuf, to the address the OTLP config names, and nowhere else.
        HttpSenderSettings httpSenderSettings = settings.getHttpSenderSettings().toBuilder()
                .endpointUrl(config.url())
                .contentType(PROTOBUF_CONTENT_TYPE)
                .build();
        MicrometerHttpSenderAdapter adapter = new MicrometerHttpSenderAdapter(
                settings.getHttpSenderFactory().apply(httpSenderSettings), httpSenderSettings);
        MicrometerHttpSenderAdapter previous = holder.getAndSet(adapter);
        if (previous != null) {
            previous.close();
        }
        return adapter;
    }

    /**
     * Stops the registry first, so its final publish still goes out, and only then releases the client
     * behind it - its connection pool, and any thread it owns.
     */
    @Override
    protected void stopMe(Deadline stopDeadline) throws Startable.StartStopException {
        try {
            super.stopMe(stopDeadline);
        } finally {
            MicrometerHttpSenderAdapter sender = httpSender.getAndSet(null);
            if (sender != null) {
                sender.close();
            }
        }
    }

    public static class OtlpMicrometerMonitoringManagerFactoryImpl implements FixSessionMonitoringManagerSettings.FixSessionMonitoringManagerFactory<OtlpMicrometerMonitoringManagerSettings> {

        @Override
        public Class<OtlpMicrometerMonitoringManagerSettings> getSettingsClass() {
            return OtlpMicrometerMonitoringManagerSettings.class;
        }

        @Override
        public FixSessionsMonitoringManager newInstance(OtlpMicrometerMonitoringManagerSettings settings) {
            return new OtlpMicrometerMonitoringManager(settings);
        }
    }
}