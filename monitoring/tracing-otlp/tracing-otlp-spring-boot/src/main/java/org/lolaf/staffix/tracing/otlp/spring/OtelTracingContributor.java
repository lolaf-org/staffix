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
package org.lolaf.staffix.tracing.otlp.spring;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessorBuilder;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.lolaf.staffix.tracing.otlp.OtelTracingSettings;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;
import org.lolaf.staffix.spring.boot.spi.BeanRef;
import org.lolaf.staffix.spring.boot.spi.FixSessionsPluginSettingsContributor;
import org.springframework.context.ApplicationContext;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Contributes OpenTelemetry tracing's settings to the engine being built, so a session can name it in
 * configuration rather than the application wiring it.
 */
public class OtelTracingContributor implements FixSessionsPluginSettingsContributor {

    private final OtelTracingProps props;
    private final ApplicationContext ctx;

    public OtelTracingContributor(OtelTracingProps props, ApplicationContext ctx) {
        this.props = props;
        this.ctx = ctx;
    }

    @Override
    public void contribute(Map<String, FixSessionsPluginSettings<?>> registry) {
        if (props.getInstances().isEmpty()) {
            return;
        }
        props.getInstances().forEach((instanceId, ip) -> {
            OtelTracingSettings.OtelTracingSettingsBuilder b = OtelTracingSettings.builder()
                    .instanceId(instanceId);
            if (ip.getOtlpEndpointUrl() != null) b.otlpEndpointUrl(ip.getOtlpEndpointUrl());
            if (ip.getOtlpEndpointType() != null) b.otlpEndpointType(ip.getOtlpEndpointType());
            ip.getHeaders().forEach(b::header);
            ip.getResourceAttributes().forEach(b::resourceAttribute);
            if (ip.getW3cTracePropagationEnabled() != null)
                b.w3cTracePropagationEnabled(ip.getW3cTracePropagationEnabled());
            if (ip.getW3cTraceFieldCode() != null) b.w3cTraceFieldCode(ip.getW3cTraceFieldCode());

            String path = "staffix.tracing.otel.instances." + instanceId;
            BeanRef.<Consumer<OtlpGrpcSpanExporterBuilder>>resolveOptional(ctx, ip.getGrpcSpanExporterCustomizerBean(), Consumer.class, path + ".grpc-span-exporter-customizer-bean")
                    .ifPresent(b::grpcSpanExporterCustomizer);
            BeanRef.<Consumer<OtlpHttpSpanExporterBuilder>>resolveOptional(ctx, ip.getHttpSpanExporterCustomizerBean(), Consumer.class, path + ".http-span-exporter-customizer-bean")
                    .ifPresent(b::httpSpanExporterCustomizer);
            BeanRef.<Consumer<BatchSpanProcessorBuilder>>resolveOptional(ctx, ip.getBatchSpanProcessorCustomizerBean(), Consumer.class, path + ".batch-span-processor-customizer-bean")
                    .ifPresent(b::batchSpanProcessorCustomizer);
            BeanRef.<Consumer<SdkTracerProviderBuilder>>resolveOptional(ctx, ip.getSdkTracerProviderCustomizerBean(), Consumer.class, path + ".sdk-tracer-provider-customizer-bean")
                    .ifPresent(b::sdkTracerProviderCustomizer);
            BeanRef.<Supplier<Sampler>>resolveOptional(ctx, ip.getOtleSamplerSupplierBean(), Supplier.class, path + ".otle-sampler-supplier-bean")
                    .ifPresent(b::otleSamplerSupplier);
            BeanRef.<ExecutorService>resolveOptional(ctx, ip.getExecutorServiceBean(), ExecutorService.class, path + ".executor-service-bean")
                    .ifPresent(b::executorService);

            if (registry.putIfAbsent(instanceId, b.build()) != null) {
                throw new IllegalStateException("staffix.tracing.otel.instances." + instanceId
                        + " collides with another sessions plugin contributor for the same key");
            }
        });
    }
}
