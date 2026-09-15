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

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder;
import io.opentelemetry.sdk.common.InternalTelemetryVersion;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.common.export.RetryPolicy;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessorBuilder;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.semconv.NetworkAttributes;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.threading.NamedThreadFactory;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixFieldsEncoder;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.monitoring.FixMonitoringAttributes;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.session.plugins.PluginContext;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.serde.StringThreadLocalSerde;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The session plugin that traces the message path and exports the spans over OTLP.
 */
@Slf4j
public class OtelTracing extends Startable.SimpleStartable<OtelTracing> implements FixSessionsPlugin<FixTracer> {

    private final OtelTracingSettings otelTracingSettings;
    private final Map<FixSessionId, FixSessionPluginImpl> sessionTracers;

    private ExecutorService executorService;
    private boolean ownsExecutorService;
    private SpanExporter spanExporter;
    private SdkTracerProvider sdkTracerProvider;

    public OtelTracing(OtelTracingSettings otelTracingSettings) {
        this.otelTracingSettings = otelTracingSettings;
        this.sessionTracers = new ConcurrentHashMap<>();
    }

    @Override
    public Optional<FixSessionPlugin<FixTracer, Span>> onSessionCreated(String fixInstanceId, FixSession fixSession,
                                                                        Collection<MessageType> incomingMessageTypes, Collection<MessageType> outgoingMessageTypes) {
        return Optional.of(sessionTracers.computeIfAbsent(fixSession.getFixSessionId(), sid ->
                new FixSessionPluginImpl(newTracerInstance(), sessionTracers::remove, fixInstanceId, fixSession, otelTracingSettings)));
    }

    @Override
    public String getInstanceId() {
        return otelTracingSettings.getInstanceId();
    }

    @Override
    protected void startMe() throws StartStopException {
        if (otelTracingSettings.getExecutorService() != null) {
            executorService = otelTracingSettings.getExecutorService();
            ownsExecutorService = false;
        } else {
            executorService = Executors.newSingleThreadExecutor(new NamedThreadFactory("otlp-spans-exporter-client"));
            ownsExecutorService = true;
        }

        String endpoint = otelTracingSettings.getOtlpEndpointUrl();
        if (!endpoint.endsWith("/v1/traces")) {
            endpoint += "/v1/traces";
        }

        if (otelTracingSettings.getOtlpEndpointType().equals(OtelTracingSettings.OtlpEndpointType.GRPC)) {
            OtlpGrpcSpanExporterBuilder builder = OtlpGrpcSpanExporter.builder()
                    .setInternalTelemetryVersion(InternalTelemetryVersion.LATEST)
                    .setTimeout(Duration.ofSeconds(1))
                    .setExecutorService(executorService)
                    .setMemoryMode(MemoryMode.REUSABLE_DATA)
                    .setRetryPolicy(RetryPolicy.builder().build())
                    .setEndpoint(endpoint);
            otelTracingSettings.getHeaders().forEach(builder::addHeader);
            otelTracingSettings.getGrpcSpanExporterCustomizer().accept(builder);
            spanExporter = builder.build();
        } else if (otelTracingSettings.getOtlpEndpointType().equals(OtelTracingSettings.OtlpEndpointType.HTTP)) {
            OtlpHttpSpanExporterBuilder builder = OtlpHttpSpanExporter.builder()
                    .setInternalTelemetryVersion(InternalTelemetryVersion.LATEST)
                    .setTimeout(Duration.ofSeconds(1))
                    .setExecutorService(executorService)
                    .setMemoryMode(MemoryMode.REUSABLE_DATA)
                    .setRetryPolicy(RetryPolicy.builder().build())
                    .setEndpoint(endpoint);
            otelTracingSettings.getHeaders().forEach(builder::addHeader);
            otelTracingSettings.getHttpSpanExporterCustomizer().accept(builder);
            spanExporter = builder.build();
        } else {
            throw new IllegalStateException();
        }

        AttributesBuilder attributes = Attributes.builder();
        otelTracingSettings.getResourceAttributes().forEach(attributes::put);

        BatchSpanProcessorBuilder batchSpanProcessorBuilder = BatchSpanProcessor.builder(spanExporter)
                .setMaxQueueSize(2048)
                .setMaxExportBatchSize(512)
                .setScheduleDelay(Duration.ofMillis(5000))
                .setExporterTimeout(Duration.ofSeconds(10));
        otelTracingSettings.getBatchSpanProcessorCustomizer().accept(batchSpanProcessorBuilder);

        SdkTracerProviderBuilder skdBuilder = SdkTracerProvider.builder()
                .setSampler(otelTracingSettings.getOtleSamplerSupplier().get())
                .setResource(Resource.getDefault().merge(Resource.create(attributes.build())))
                .addSpanProcessor(batchSpanProcessorBuilder.build());
        otelTracingSettings.getSdkTracerProviderCustomizer().accept(skdBuilder);
        sdkTracerProvider = skdBuilder.build();
        log.info("OTLP tracer started with endpoint {}", endpoint);
    }

    Tracer newTracerInstance() {
        return sdkTracerProvider.get("staffix");
    }

    // for tests
    FixTracer getTracer(FixSession fixSession) {
        return onSessionCreated(null, fixSession, null, null).orElseThrow().getPluginContext().orElseThrow();
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        sessionTracers.clear();
        sdkTracerProvider.forceFlush();
        sdkTracerProvider.close();
        spanExporter.close();
        spanExporter = null;
        if (ownsExecutorService) {
            executorService.shutdown();
        }
        executorService = null;
    }

    @Override
    public boolean isStarted() {
        return spanExporter != null;
    }


    private static class FixSessionPluginImpl implements FixSessionPlugin<FixTracer, Span> {

        private final Optional<FixTracer> tracer;
        private final FixTracerImpl fixTracerImpl;
        private final Consumer<FixSessionId> sessionRemovedConsumer;
        private final String fixInstanceId;
        private final FixSessionId fixSessionId;
        private final FixField w3cTraceField;
        private final TraceContextPropagator traceContextPropagator;
        private Span messageDecodedSpan;
        private Scope spanScope;

        FixSessionPluginImpl(Tracer tracer, Consumer<FixSessionId> sessionRemovedConsumer, String fixInstanceId, FixSession fixSession,
                             OtelTracingSettings settings) {
            this.fixTracerImpl = new FixTracerImpl(tracer);
            this.tracer = Optional.of(fixTracerImpl);
            this.sessionRemovedConsumer = sessionRemovedConsumer;
            this.fixSessionId = fixSession.getFixSessionId();
            this.fixInstanceId = fixInstanceId;
            this.w3cTraceField = settings.isW3cTracePropagationEnabled()
                    ? fixSession.getFieldsRegistry().addUserDefinedField(settings.getW3cTraceFieldCode(), FieldType.STRING, FieldLocation.BODY) : null;
            this.traceContextPropagator = new TraceContextPropagator();
        }

        @Override
        public boolean isForPluginContext(Class<? extends PluginContext> pluginClass) {
            return pluginClass.equals(FixTracer.class);
        }

        @Override
        public void onDecoderSetup(FixMessageDecoder decoder, FixFieldsDecoderMapper fieldsDecoderMapper) {
            if (w3cTraceField != null) {
                fieldsDecoderMapper.mapSerdeCtxField(w3cTraceField, this::setW3cTraceFromSerdeCtx);
            }
        }

        private void setW3cTraceFromSerdeCtx(SerDe.DeserializationContext ctx) {
            String w3cTrace = StringThreadLocalSerde.instance().deserialize(ctx);
            // unfortunately we cannot change the current span context, it is immutable, fur such case OTEL api says to use a trace link
            // see https://opentelemetry.io/docs/specs/otel/trace/api/
            messageDecodedSpan.addLink(traceContextPropagator.toSpanContext(w3cTrace));
        }

        @Override
        public void onSessionDestroyed(String fixInstanceId, FixSessionId fixSessionId) {
            sessionRemovedConsumer.accept(fixSessionId);
        }

        @Override
        public void onMessageDecodingStarted(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
            messageDecodedSpan = fixTracerImpl.spanBuilder("fix-msg-decoding")
                    .setAttribute(NetworkAttributes.NETWORK_PROTOCOL_NAME, "fix")
                    .setAttribute(FixMonitoringAttributes.FIX_INSTANCE_ID, fixInstanceId)
                    .setAttribute(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                    .setAttribute(FixMonitoringAttributes.FIX_SESSION_GROUP_ID, fixSessionId.getGroup())
                    .setAttribute(FixMonitoringAttributes.FIX_MESSAGE_TYPE, messageType.code()).startSpan();
            spanScope = messageDecodedSpan.makeCurrent();
        }

        @Override
        public Span getMessageEncodingToken(MessageType messageType, long encodingStartTimeInNanos) {
            // Runs on the producing thread. Capture the span in scope here and hand it to onMessageEncodedBody
            // as the encoding token, so the W3C trace injected into the outbound message reflects the span that
            // produced the message — not whatever the I/O thread (where onMessageEncodedBody runs) has current.
            // The token is a plain span reference, safe to leave unused if the message is never encoded.
            if (w3cTraceField == null || messageType.isAdmin()) {
                return null;
            }
            Span current = fixTracerImpl.currentSpan();
            return current.getSpanContext().isValid() ? current : null;
        }

        @Override
        public void onMessageEncodedBody(MessageType messageType, ByteBuffer encodedBody, FixFieldsEncoder<?> fixFieldsEncoder, long encodingStartTimeInNanos, Span encodingToken) {
            // The token is the producing span captured in onMessageEncodingStarted, or null when there was
            // nothing to propagate (no span in scope, admin message, or propagation disabled).
            if (encodingToken != null) {
                if (fixFieldsEncoder == null) {
                    throw new IllegalStateException("FixFieldEncoder is not provided, W3CTrace cannot be encoded into message," +
                            " either disable w3cTracePropagationEnabled or do not use an asynchronous plugin wrapper");
                }
                String w3cTrace = traceContextPropagator.toW3CTrace(encodingToken);
                fixFieldsEncoder.addString(w3cTraceField, w3cTrace);
            }
        }

        @Override
        public void onMessageDecodingFinished(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
            messageDecodedSpan.addEvent("fix-msg-decoding-terminated");
        }

        @Override
        public void onMessageReceived(MessageType messageType, int payloadSize, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
            messageDecodedSpan.end();
            spanScope.close();
        }

        @Override
        public Optional<FixTracer> getPluginContext() {
            return tracer;
        }
    }
}