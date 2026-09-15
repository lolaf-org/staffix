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
package org.lolaf.staffix.tracing.otlp.sender;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.http.testkit.StubHttpServer;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one test that proves the whole chain rather than a link of it: a real
 * {@link OtlpHttpSpanExporter}, resolving our provider through the {@code ServiceLoader}, publishing a
 * real span to a stub collector.
 *
 * <p>Everything else in this module tests a piece with the pieces around it faked. This is what catches
 * the failures that only exist between them - a services file naming a class that moved, a provider that
 * builds a sender the exporter rejects, a mapping that loses the endpoint.
 */
class OtlpHttpSpanExporterIntegrationTest {

    private StubHttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new StubHttpServer("/v1/traces");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private OtlpHttpSpanExporter exporterOnTheStub() {
        return OtlpHttpSpanExporter.builder()
                .setEndpoint(server.url())
                .setTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    void exportsASpanThroughOurProviderToTheCollector() {
        try (OtlpHttpSpanExporter exporter = exporterOnTheStub()) {
            SdkTracerProvider tracing = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build();

            tracing.get("staffix-test").spanBuilder("an-exported-span").startSpan().end();
            assertThat(tracing.forceFlush().join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
            tracing.close();
        }

        assertThat(server.received()).hasSize(1);
        assertThat(server.lastReceived().method).isEqualTo("POST");
        assertThat(server.lastReceived().header("Content-Type")).isEqualTo("application/x-protobuf");
        assertThat(server.lastReceived().body)
                .as("a marshalled ExportTraceServiceRequest, so not empty")
                .isNotEmpty();
    }

    @Test
    void reportsAFailedExportRatherThanClaimingSuccess() {
        server.respondWith(400, "malformed export");

        try (OtlpHttpSpanExporter exporter = exporterOnTheStub()) {
            SdkTracerProvider tracing = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build();

            tracing.get("staffix-test").spanBuilder("a-rejected-span").startSpan().end();
            CompletableResultCode flushed = tracing.forceFlush().join(10, TimeUnit.SECONDS);

            assertThat(flushed.isSuccess())
                    .as("a 400 reaches the exporter as a failed export, not as a swallowed error")
                    .isFalse();
            tracing.close();
        }

        assertThat(server.received())
                .as("400 is not retryable, so exactly one attempt")
                .hasSize(1);
    }
}
