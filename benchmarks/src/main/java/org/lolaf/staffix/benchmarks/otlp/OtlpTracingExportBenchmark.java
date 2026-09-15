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
package org.lolaf.staffix.benchmarks.otlp;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.lolaf.staffix.api.http.HttpSender;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.http.jdk.JdkHttpSender;
import org.lolaf.staffix.http.jetty.JettyHttpSender;
import org.lolaf.staffix.tracing.otlp.sender.StaffixHttpSenderProvider;
import org.openjdk.jmh.annotations.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * What one span export costs on the tracing path, published through this library's HTTP clients against
 * published through OpenTelemetry's own OkHttp sender.
 *
 * <pre>{@code
 * java -jar benchmarks/target/benchmarks.jar OtlpTracingExportBenchmark -prof gc
 * }</pre>
 *
 * <p><strong>Read this one for its allocation column and for the comparison against
 * {@code OTEL_OK_HTTP}, not as a latency result worth tuning.</strong> The tracing path exports a batch
 * every five seconds; whatever it spends per export is invisible next to the metrics path publishing
 * every step, and is not a reason to choose one client over another. That choice is measured properly in
 * {@link OtlpHttpSenderBenchmark}, over both transports.
 *
 * <p>What this measures that the sibling benchmark cannot: the cost of the adapter itself - marshalling
 * an export into a pooled buffer, the cached header array, the reused response object - against the cost
 * of OTel's own sender doing the same job its own way. That difference is in the marshalling and the
 * bookkeeping rather than in the socket, which is why plaintext alone is measured here: TLS would add
 * the same amount to every arm and lengthen the sweep for nothing.
 *
 * <p>The receiver runs in a JVM of its own ({@link StubOtlpServer}), so its allocation stays out of this
 * one's GC profile.
 */
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgsPrepend = {"-Xmx1g", "-Xms1g"})
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class OtlpTracingExportBenchmark {

    @Benchmark
    public void export(ExportState state) {
        state.export();
    }

    /**
     * Which {@code HttpSenderProvider} the exporter resolves, and which client behind it.
     */
    public enum SenderType {
        /**
         * OpenTelemetry's own OkHttp sender, the baseline this work replaces.
         */
        OTEL_OK_HTTP,
        /**
         * Our provider on the JDK client, which is what it uses when nothing is configured.
         */
        STAFFIX_JDK,
        /**
         * Our provider on OkHttp, the same underlying client as the baseline arm.
         */
        STAFFIX_OK_HTTP,
        /**
         * Our provider on the Jetty client.
         */
        STAFFIX_JETTY
    }

    /**
     * Named by {@link StaffixHttpSenderProvider#FACTORY_PROPERTY}, so each must be public with a public
     * no-argument constructor.
     */
    public static final class JdkFactory implements Function<HttpSenderSettings, HttpSender> {
        @Override
        public HttpSender apply(HttpSenderSettings settings) {
            return new JdkHttpSender(settings);
        }
    }

    /**
     * @see JdkFactory
     */
    public static final class OkHttpFactory implements Function<HttpSenderSettings, HttpSender> {
        @Override
        public HttpSender apply(HttpSenderSettings settings) {
            return new org.lolaf.staffix.http.okhttp.OkHttpSender(settings);
        }
    }

    /**
     * @see JdkFactory
     */
    public static final class JettyFactory implements Function<HttpSenderSettings, HttpSender> {
        @Override
        public HttpSender apply(HttpSenderSettings settings) {
            return new JettyHttpSender(settings);
        }
    }

    @State(Scope.Benchmark)
    public static class ExportState {

        /**
         * The provider OpenTelemetry resolves through, chosen per arm rather than by classpath order.
         */
        private static final String PROVIDER_PROPERTY =
                "io.opentelemetry.sdk.common.export.HttpSenderProvider";

        private static final String OTEL_OK_HTTP_PROVIDER =
                "io.opentelemetry.exporter.sender.okhttp.internal.OkHttpHttpSenderProvider";

        @Param({"OTEL_OK_HTTP", "STAFFIX_JDK", "STAFFIX_OK_HTTP", "STAFFIX_JETTY"})
        SenderType senderType;

        /**
         * Spans per export, matching the {@code maxExportBatchSize} {@code OtelTracing} configures.
         */
        @Param({"512"})
        int batchSize;

        private Process server;
        private OtlpHttpSpanExporter exporter;
        private List<SpanData> spans;

        private static String factoryFor(SenderType senderType) {
            switch (senderType) {
                case STAFFIX_JDK:
                    return JdkFactory.class.getName();
                case STAFFIX_OK_HTTP:
                    return OkHttpFactory.class.getName();
                case STAFFIX_JETTY:
                    return JettyFactory.class.getName();
                default:
                    throw new IllegalStateException("Unhandled sender " + senderType);
            }
        }

        /**
         * Builds a realistic batch by ending real spans and catching what the SDK produced, rather than
         * hand-rolling {@code SpanData} - which would need a test-scoped dependency this module has no
         * other use for, and would risk measuring a shape the SDK never emits.
         */
        private static List<SpanData> recordSpans(int count) {
            List<SpanData> recorded = new ArrayList<>(count);
            SpanExporter capture = new SpanExporter() {
                @Override
                public CompletableResultCode export(Collection<SpanData> spans) {
                    recorded.addAll(spans);
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode flush() {
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode shutdown() {
                    return CompletableResultCode.ofSuccess();
                }
            };
            try (SdkTracerProvider tracing = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(capture))
                    .build()) {
                for (int i = 0; i < count; i++) {
                    tracing.get("staffix-benchmark")
                            .spanBuilder("NewOrderSingle-" + i)
                            .setAttribute("fix.session", "SENDER->TARGET")
                            .setAttribute("fix.msgType", "D")
                            .startSpan()
                            .end();
                }
            }
            return recorded;
        }

        private static Process startServer() throws Exception {
            return new ProcessBuilder(
                    System.getProperty("java.home") + "/bin/java",
                    "-cp", System.getProperty("java.class.path"),
                    StubOtlpServer.class.getName(),
                    "http")
                    .redirectErrorStream(true)
                    .start();
        }

        private static int portOf(Process server) throws Exception {
            BufferedReader out = new BufferedReader(
                    new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = out.readLine()) != null) {
                if (line.startsWith("PORT ")) {
                    return Integer.parseInt(line.substring("PORT ".length()).trim());
                }
            }
            throw new IllegalStateException("The stub receiver never announced its port");
        }

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            server = startServer();
            String address = "http://127.0.0.1:" + portOf(server) + "/v1/traces";

            spans = recordSpans(batchSize);
            selectProvider();
            exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(address)
                    .setTimeout(Duration.ofSeconds(5))
                    .setMemoryMode(MemoryMode.REUSABLE_DATA)
                    .build();

            // Establish the connection and let every layer warm before measurement starts.
            for (int i = 0; i < 50; i++) {
                export();
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (exporter != null) {
                exporter.close();
            }
            System.clearProperty(PROVIDER_PROPERTY);
            System.clearProperty(StaffixHttpSenderProvider.FACTORY_PROPERTY);
            server.destroy();
        }

        void export() {
            CompletableResultCode result = exporter.export(spans).join(10, TimeUnit.SECONDS);
            if (!result.isSuccess()) {
                throw new IllegalStateException("The stub receiver rejected an export");
            }
        }

        /**
         * Both providers are on the classpath, so which one answers is a property rather than an
         * accident of ordering. Read at exporter build time, which is why this runs first.
         */
        private void selectProvider() {
            if (senderType == SenderType.OTEL_OK_HTTP) {
                System.setProperty(PROVIDER_PROPERTY, OTEL_OK_HTTP_PROVIDER);
                System.clearProperty(StaffixHttpSenderProvider.FACTORY_PROPERTY);
                return;
            }
            System.setProperty(PROVIDER_PROPERTY, StaffixHttpSenderProvider.class.getName());
            System.setProperty(StaffixHttpSenderProvider.FACTORY_PROPERTY, factoryFor(senderType));
        }
    }
}
