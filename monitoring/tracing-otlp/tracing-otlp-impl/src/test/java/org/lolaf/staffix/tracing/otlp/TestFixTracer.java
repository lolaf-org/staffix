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

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.codec.FixFieldsEncoder;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@Slf4j
class TestFixTracer {

    private static final List<String> collectorOutput = new CopyOnWriteArrayList<>();
    private static GenericContainer<?> otelCollector;
    private OtelTracing tracing;
    private FixSession fixSession;

    @BeforeAll
    static void setupCollector() {
        otelCollector = new GenericContainer<>(DockerImageName.parse("otel/opentelemetry-collector:0.158.0"))
                .withExposedPorts(4317, 4318)
                .withLogConsumer(outputFrame -> {
                    String message = outputFrame.getUtf8StringWithoutLineEnding();
                    collectorOutput.add(message);
                    log.debug(message);
                })
                .withCommand("--config=/etc/otel/config.yaml")
                .withCopyFileToContainer(MountableFile.forClasspathResource("config.yaml"), "/etc/otel/config.yaml")
                .waitingFor(new LogMessageWaitStrategy()
                        .withRegEx(".*Everything is ready. Begin running and processing data.*")
                        .withStartupTimeout(Duration.of(10L, ChronoUnit.SECONDS)));
        otelCollector.start();
    }

    @AfterAll
    static void shutdownCollector() {
        otelCollector.stop();
    }

    @BeforeEach
    void setup() {
        collectorOutput.clear();
        tracing = new OtelTracing(OtelTracingSettings.builder()
                .otlpEndpointType(OtelTracingSettings.OtlpEndpointType.HTTP)
                .otlpEndpointUrl("http://" + otelCollector.getHost() + ":" + otelCollector.getMappedPort(4318))
                .build());
        tracing.start();
        FixSessionId fixSessionId = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
        fixSession = mock(FixSession.class);
        when(fixSession.getFixSessionId()).thenReturn(fixSessionId);
    }

    @AfterEach
    void shutdown() {
        tracing.stop(Deadline.unlimited());
    }

    @Test
    void testTracerSendsSpans() {
        FixTracer sessionTracer = tracing.getTracer(fixSession);

        sessionTracer.spanBuilder("test-span").startSpan().end();

        await().untilAsserted(() -> assertThat(collectorOutput).anyMatch(line -> line.contains("test-span")));
    }

    @Test
    void testDisabledTracerDoesNotSendSpans() {
        FixTracer sessionTracer = tracing.getTracer(fixSession);

        sessionTracer.spanBuilder("before-disable").startSpan().end();

        await().untilAsserted(() ->
                assertThat(collectorOutput).anyMatch(line -> line.contains("before-disable")));

        collectorOutput.clear();
        sessionTracer.disable();
        assertThat(sessionTracer.isEnabled()).isFalse();

        sessionTracer.spanBuilder("while-disabled").startSpan().end();

        // give some time for any spans to arrive, then assert none did
        await()
                .pollDelay(Duration.ofSeconds(2))
                .untilAsserted(() ->
                        assertThat(collectorOutput).noneMatch(line -> line.contains("while-disabled")));
    }

    @Test
    void testReEnableTracerSendsSpansAgain() {
        FixTracer sessionTracer = tracing.getTracer(fixSession);

        sessionTracer.disable();
        sessionTracer.spanBuilder("disabled-span").startSpan().end();

        sessionTracer.enable();
        assertThat(sessionTracer.isEnabled()).isTrue();

        sessionTracer.spanBuilder("re-enabled-span").startSpan().end();

        await().untilAsserted(() ->
                assertThat(collectorOutput).anyMatch(line -> line.contains("re-enabled-span")));
        assertThat(collectorOutput).noneMatch(line -> line.contains("disabled-span"));
    }

    @Test
    void injectsW3cTraceCapturedOnProducingThreadWhenEncodingRunsOnAnotherThread() throws InterruptedException {
        MessageType businessMessage = MessageType.of("D", false);
        OtelTracing w3cTracing = new OtelTracing(OtelTracingSettings.builder()
                .otlpEndpointType(OtelTracingSettings.OtlpEndpointType.HTTP)
                .otlpEndpointUrl("http://" + otelCollector.getHost() + ":" + otelCollector.getMappedPort(4318))
                .w3cTracePropagationEnabled(true)
                .build());
        w3cTracing.start();
        try {
            FixField w3cField = FixField.of(7777, FieldType.STRING, FieldLocation.BODY);
            FieldsRegistry registry = mock(FieldsRegistry.class);
            when(registry.addUserDefinedField(anyInt(), any(), any())).thenReturn(w3cField);
            when(fixSession.getFieldsRegistry()).thenReturn(registry);

            FixSessionPlugin<FixTracer, Span> plugin = w3cTracing.onSessionCreated("engine", fixSession, List.of(), List.of()).orElseThrow();
            FixTracer tracer = plugin.getPluginContext().orElseThrow();

            // Producing thread: a span is in scope when encoding starts; capture the token.
            Span producing = tracer.spanBuilder("producer").startSpan();
            Span token;
            try (Scope ignored = producing.makeCurrent()) {
                token = plugin.getMessageEncodingToken(businessMessage, 1L);
            }
            producing.end();

            // I/O thread: no span is current here, yet the injected trace must be the producing span's.
            FixFieldsEncoder<?> encoder = mock(FixFieldsEncoder.class);
            Thread ioThread = new Thread(() ->
                    plugin.onMessageEncodedBody(businessMessage, ByteBuffer.allocate(0), encoder, 1L, token));
            ioThread.start();
            ioThread.join();

            SpanContext sc = producing.getSpanContext();
            String expectedTrace = "00-" + sc.getTraceId() + "-" + sc.getSpanId() + "-" + sc.getTraceFlags().asHex();
            verify(encoder).addString(w3cField, expectedTrace);
        } finally {
            w3cTracing.stop(Deadline.unlimited());
        }
    }

    @Test
    void doesNotInjectW3cTraceWhenNoSpanWasInScopeOnTheProducingThread() throws InterruptedException {
        MessageType businessMessage = MessageType.of("D", false);
        OtelTracing w3cTracing = new OtelTracing(OtelTracingSettings.builder()
                .otlpEndpointType(OtelTracingSettings.OtlpEndpointType.HTTP)
                .otlpEndpointUrl("http://" + otelCollector.getHost() + ":" + otelCollector.getMappedPort(4318))
                .w3cTracePropagationEnabled(true)
                .build());
        w3cTracing.start();
        try {
            FixField w3cField = FixField.of(7777, FieldType.STRING, FieldLocation.BODY);
            FieldsRegistry registry = mock(FieldsRegistry.class);
            when(registry.addUserDefinedField(anyInt(), any(), any())).thenReturn(w3cField);
            when(fixSession.getFieldsRegistry()).thenReturn(registry);

            FixSessionPlugin<FixTracer, Span> plugin = w3cTracing.onSessionCreated("engine", fixSession, List.of(), List.of()).orElseThrow();

            // No span in scope on the producing thread -> null token -> nothing to inject downstream.
            Span token = plugin.getMessageEncodingToken(businessMessage, 1L);
            assertThat(token).isNull();

            FixFieldsEncoder<?> encoder = mock(FixFieldsEncoder.class);
            plugin.onMessageEncodedBody(businessMessage, ByteBuffer.allocate(0), encoder, 1L, token);
            verifyNoInteractions(encoder);
        } finally {
            w3cTracing.stop(Deadline.unlimited());
        }
    }

    @Test
    void testSameTracerReturnedForSameSessionId() {
        FixTracer first = tracing.getTracer(fixSession);
        FixTracer second = tracing.getTracer(fixSession);

        assertThat(first).isSameAs(second);
    }

    @Test
    void testDifferentSessionsGetIndependentTracers() {

        FixSessionId session2 = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER2", "TARGET2");
        FixSession fixSession2 = mock(FixSession.class);
        when(fixSession2.getFixSessionId()).thenReturn(session2);

        FixTracer tracer1 = tracing.getTracer(fixSession);
        FixTracer tracer2 = tracing.getTracer(fixSession2);

        assertThat(tracer1).isNotSameAs(tracer2);

        tracer1.disable();
        assertThat(tracer1.isEnabled()).isFalse();
        assertThat(tracer2.isEnabled()).isTrue();
    }

}
