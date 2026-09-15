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

import lombok.Data;
import org.lolaf.staffix.tracing.otlp.OtelTracingSettings.OtlpEndpointType;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One configured instance of OpenTelemetry tracing: its instance id and the settings a session naming that id gets.
 */
@Data
@ConfigurationPropertiesSource
public class OtelTracingInstanceProps {
    /**
     * The collector's address.
     */
    private String otlpEndpointUrl;
    /**
     * Whether spans go over OTLP/HTTP or OTLP/gRPC. gRPC needs the OkHttp or Jetty client.
     */
    private OtlpEndpointType otlpEndpointType;
    /**
     * Additional headers to provide to the
     */
    private Map<String, String> headers = new LinkedHashMap<>();
    /**
     * Attributes attached to everything this process exports, such as service name and environment.
     */
    private Map<String, String> resourceAttributes = new LinkedHashMap<>();
    /**
     * Whether to propagate <a href="https://www.w3.org/TR/trace-context/">W3C Trace Context</a> in outbound
     * FIX messages. Defaults to {@code false}.
     */
    private Boolean w3cTracePropagationEnabled;
    /**
     * The FIX field code used to carry the W3C trace context value in outbound messages. Only used when {@link
     * #w3cTracePropagationEnabled} is {@code true}. Defaults to {@code 7777}. <p>Note: {@link
     * org.lolaf.staffix.api.session.FixSessionSettings.ValidationSettings#isAllowUserDefinedFields()} must be
     * enabled on the session for user-defined fields to be accepted.
     */
    private Integer w3cTraceFieldCode;

    /**
     * Spring bean name of {@code Consumer<OtlpGrpcSpanExporterBuilder>}.
     *
     * <p>Also the way in for a container-built gRPC client: {@code setComponentLoader} on the builder,
     * with a loader returning {@code new StaffixGrpcSenderProvider(myFactory)}. Otherwise the client is
     * whatever {@code org.lolaf.staffix.otlp.grpcSenderFactory} names, which is mandatory.
     */
    private String grpcSpanExporterCustomizerBean;
    /**
     * Spring bean name of {@code Consumer<OtlpHttpSpanExporterBuilder>}.
     *
     * <p>Also the way in for a container-built HTTP client and its {@code HttpVersion}:
     * {@code setComponentLoader} with a loader returning {@code new StaffixHttpSenderProvider(myFactory)}.
     * Otherwise the client is whatever {@code org.lolaf.staffix.otlp.httpSenderFactory} names.
     */
    private String httpSpanExporterCustomizerBean;
    /**
     * Spring bean name of {@code Consumer<BatchSpanProcessorBuilder>}.
     */
    private String batchSpanProcessorCustomizerBean;
    /**
     * Spring bean name of {@code Consumer<SdkTracerProviderBuilder>}.
     */
    private String sdkTracerProviderCustomizerBean;
    /**
     * Spring bean name of {@code Supplier<Sampler>}.
     */
    private String otleSamplerSupplierBean;
    /**
     * Spring bean name of {@link java.util.concurrent.ExecutorService}.
     */
    private String executorServiceBean;
}
