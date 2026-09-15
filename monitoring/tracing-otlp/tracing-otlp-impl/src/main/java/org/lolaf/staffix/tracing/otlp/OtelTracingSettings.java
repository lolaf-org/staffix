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
package org.lolaf.staffix.tracing.otlp;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessorBuilder;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The collector endpoint, the sampling, and the field trace context is carried in.
 */
@Getter
@Builder(toBuilder = true)
public class OtelTracingSettings implements FixSessionsPluginSettings<OtelTracing> {

    /**
     * Names this instance, so a session can select it by id when more than one is configured.
     */
    @Builder.Default
    private final String instanceId = DEFAULT_INSTANCE_ID;

    /**
     * HTTP or GRPC OTLP endpoint url
     */
    private final String otlpEndpointUrl;

    /**
     * Whether spans go over OTLP/HTTP or OTLP/gRPC. gRPC needs the OkHttp or Jetty client.
     */
    @Builder.Default
    private final OtlpEndpointType otlpEndpointType = OtlpEndpointType.HTTP;

    /**
     * Optional customizer for the GRPC span exporter builder, called after default configuration is applied.
     */
    @Builder.Default
    private final Consumer<OtlpGrpcSpanExporterBuilder> grpcSpanExporterCustomizer = InstanceProvider.emptyConsumer();

    /**
     * Optional customizer for the HTTP span exporter builder, called after default configuration is applied.
     */
    @Builder.Default
    private final Consumer<OtlpHttpSpanExporterBuilder> httpSpanExporterCustomizer = InstanceProvider.emptyConsumer();

    /**
     * Optional batch span exporter customizer
     */
    @Builder.Default
    private final Consumer<BatchSpanProcessorBuilder> batchSpanProcessorCustomizer = InstanceProvider.emptyConsumer();

    /**
     * Optional customizer for Otel tracer SDK
     */
    @Builder.Default
    private final Consumer<SdkTracerProviderBuilder> sdkTracerProviderCustomizer = InstanceProvider.emptyConsumer();

    /**
     * Otel SDK Sampler supplier
     */
    @Builder.Default
    private final Supplier<Sampler> otleSamplerSupplier = Sampler::alwaysOn;

    /**
     * Executor service used by the OTLP span exporter client. If not provided, OtelTracing will create and manage its own instance.
     */
    private final ExecutorService executorService;

    /**
     * Additional headers to provide to the
     */
    @Singular
    private final Map<String, String> headers;

    /**
     * Otlp resource attributes to send over the wire such as "service.name"
     */
    @Singular
    private final Map<String, String> resourceAttributes;

    /**
     * Whether to propagate <a href="https://www.w3.org/TR/trace-context/">W3C Trace Context</a>
     * in outbound FIX messages. Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean w3cTracePropagationEnabled = false;

    /**
     * The FIX field code used to carry the W3C trace context value in outbound messages.
     * Only used when {@link #w3cTracePropagationEnabled} is {@code true}. Defaults to {@code 7777}.
     *
     * <p>Note: {@link org.lolaf.staffix.api.session.FixSessionSettings.ValidationSettings#isAllowUserDefinedFields()}
     * must be enabled on the session for user-defined fields to be accepted.
     */
    @Builder.Default
    private final int w3cTraceFieldCode = 7777;

    public enum OtlpEndpointType {
        HTTP,
        GRPC
    }

    public static class OtelTracingFactoryImpl implements FixSessionsPluginFactory<OtelTracingSettings> {

        @Override
        public Class<OtelTracingSettings> getSettingsClass() {
            return OtelTracingSettings.class;
        }

        @Override
        public FixSessionsPlugin<?> newInstance(OtelTracingSettings settings) {
            return new OtelTracing(settings);
        }
    }
}