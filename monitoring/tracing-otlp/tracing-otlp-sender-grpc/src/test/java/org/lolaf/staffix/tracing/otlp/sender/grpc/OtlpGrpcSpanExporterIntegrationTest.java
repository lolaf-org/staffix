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
package org.lolaf.staffix.tracing.otlp.sender.grpc;

import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.grpc.GrpcFraming;
import org.lolaf.staffix.api.grpc.GrpcStatus;
import org.lolaf.staffix.grpc.testkit.StubGrpcServer;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one test that proves the whole chain rather than a link of it: a real
 * {@link OtlpGrpcSpanExporter}, resolving our provider through the {@code ServiceLoader}, calling a stub
 * collector over HTTP/2.
 *
 * <p>This is what catches the failures that live between the pieces - a services file naming a class
 * that moved, a provider building a sender the exporter rejects, a mapping that loses the method name.
 * Its HTTP counterpart caught exactly one such bug the first time it ran.
 */
class OtlpGrpcSpanExporterIntegrationTest {

    private StubGrpcServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new StubGrpcServer();
        System.setProperty(StaffixGrpcSenderProvider.FACTORY_PROPERTY,
                StaffixGrpcSenderProviderTest.OkHttpFactory.class.getName());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(StaffixGrpcSenderProvider.FACTORY_PROPERTY);
        server.close();
    }

    private OtlpGrpcSpanExporter exporterOnTheStub() {
        return OtlpGrpcSpanExporter.builder()
                .setEndpoint(server.url())
                .setTimeout(Duration.ofSeconds(5))
                .build();
    }

    private CompletableResultCode exportOneSpan(OtlpGrpcSpanExporter exporter, String name) {
        SdkTracerProvider tracing = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        try {
            tracing.get("staffix-test").spanBuilder(name).startSpan().end();
            return tracing.forceFlush().join(10, TimeUnit.SECONDS);
        } finally {
            tracing.close();
        }
    }

    @Test
    void exportsASpanThroughOurProviderToTheCollector() {
        try (OtlpGrpcSpanExporter exporter = exporterOnTheStub()) {
            assertThat(exportOneSpan(exporter, "an-exported-span").isSuccess()).isTrue();
        }

        assertThat(server.received()).hasSize(1);
        assertThat(server.lastReceived().getMethod()).isEqualTo("POST");
        assertThat(server.lastReceived().getTarget())
                .isEqualTo("/opentelemetry.proto.collector.trace.v1.TraceService/Export");
        assertThat(server.lastReceived().getHeaders().get("content-type")).startsWith("application/grpc");

        byte[] framed = server.lastReceived().getBody().toByteArray();
        assertThat(framed.length).isGreaterThan(GrpcFraming.HEADER_LENGTH);
        assertThat(GrpcFraming.messageLength(framed, 0))
                .as("a marshalled ExportTraceServiceRequest, framed as gRPC requires")
                .isEqualTo(framed.length - GrpcFraming.HEADER_LENGTH);
    }

    /**
     * A failed gRPC call still answers HTTP 200, so a sender reading the HTTP status would report this
     * export as a success.
     */
    @Test
    void reportsAFailedExportRatherThanClaimingSuccess() {
        server.respondWith(GrpcStatus.INVALID_ARGUMENT, "malformed export");

        try (OtlpGrpcSpanExporter exporter = exporterOnTheStub()) {
            assertThat(exportOneSpan(exporter, "a-rejected-span").isSuccess())
                    .as("a non-OK grpc-status reaches the exporter as a failed export")
                    .isFalse();
        }

        assertThat(server.received())
                .as("INVALID_ARGUMENT is not retryable, so exactly one attempt")
                .hasSize(1);
    }
}
