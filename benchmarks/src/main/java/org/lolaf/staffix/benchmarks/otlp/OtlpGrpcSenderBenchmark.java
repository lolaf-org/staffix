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

import org.lolaf.staffix.api.grpc.GrpcReply;
import org.lolaf.staffix.api.grpc.GrpcSender;
import org.lolaf.staffix.api.grpc.GrpcSenderSettings;
import org.lolaf.staffix.api.http.HttpSender;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.api.http.HttpVersion;
import org.lolaf.staffix.http.jetty.JettyGrpcSender;
import org.lolaf.staffix.http.jetty.JettyHttpSender;
import org.lolaf.staffix.http.okhttp.OkHttpGrpcSender;
import org.lolaf.staffix.http.okhttp.OkHttpSender;
import org.openjdk.jmh.annotations.*;

import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * OTLP/gRPC against OTLP/HTTP, on the two clients that can serve both.
 *
 * <p>The question this answers is what gRPC costs relative to plain HTTP for the same payload on the
 * same client, which `otlp-senders.md` framed as a capability rather than an optimisation: OTLP/HTTP
 * protobuf is functionally equivalent for these payloads, so nothing here is expected to get faster.
 * What the numbers are for is knowing the size of the bill.
 *
 * <p><strong>Only `okhttp` and `jetty` appear.</strong> gRPC reports its outcome in HTTP/2 trailers and
 * {@code java.net.http.HttpResponse} exposes none, so there is no JDK gRPC sender to measure - see
 * {@code http-clients/jdk}'s README.
 *
 * <p><strong>Both arms face the same receiver</strong>, {@link StubGrpcOtlpServer}, out of process. That
 * is what makes the comparison a protocol comparison rather than a server comparison, and it is also why
 * the HTTP figures here sit above {@code OtlpHttpSenderBenchmark}'s: that one answers from a raw socket,
 * this one from MockWebServer. Compare arms within this class, not across the two.
 *
 * <p><strong>Three arms, not two.</strong> gRPC is HTTP/2 and OTLP/HTTP is conventionally HTTP/1.1, so
 * measuring only those two charges gRPC for everything HTTP/2 costs - HPACK, per-stream state, flow
 * control - which no gRPC decision can avoid but which is not what gRPC's framing adds. The third arm
 * publishes ordinary OTLP/HTTP over HTTP/2, so {@code GRPC} against {@code HTTP2} isolates the framing
 * and trailers while {@code HTTP2} against {@code HTTP} prices the version change on its own.
 *
 * <p>Senders are driven through {@link HttpSender} and {@link GrpcSender} directly, with no adapter in
 * between, so the two interfaces are measured on equal terms. The reply object is caller-owned and
 * reused, as the interface intends.
 *
 * <p><strong>Read the allocation column, not the time column.</strong> Allocation is measured as
 * {@code gc.alloc.rate.norm}, which counts only this JVM, so the receiver cannot contribute to it and
 * the figures reproduce across runs to within about 2%. The timings cannot be read the same way: they
 * include everything MockWebServer does, and MockWebServer is a test double rather than a collector. The
 * same client publishing the same payload over the same protocol measures about 30 us against
 * {@code OtlpHttpSenderBenchmark}'s raw-socket stub and about 200 us here, with error bars that reach
 * half the mean - so roughly 170 us per exchange, and most of the variance, belong to the receiver. Use
 * `OtlpHttpSenderBenchmark` for any latency question about the HTTP clients; there is no equivalent
 * gRPC figure, and getting one honestly would need a receiver built for throughput rather than for
 * tests.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class OtlpGrpcSenderBenchmark {

    /**
     * One export, from building the request to reading the outcome.
     *
     * @param state the sender and its receiver
     * @throws Exception if the publish fails
     */
    @Benchmark
    public void publish(SenderState state) throws Exception {
        state.publish();
    }

    /**
     * The client under test. Both of these implement `HttpSender` and `GrpcSender`.
     */
    public enum ClientType {
        /**
         * `http-clients/okhttp`, which serves gRPC with no jars beyond OkHttp itself.
         */
        OK_HTTP,
        /**
         * `http-clients/jetty`, which needs the HTTP/2 client transport for it.
         */
        JETTY
    }

    /**
     * The protocol the export goes out on.
     */
    public enum Wire {
        /**
         * OTLP/gRPC: length-prefixed framing, HTTP/2, status in the trailers.
         */
        GRPC,
        /**
         * OTLP/HTTP with a protobuf body over HTTP/1.1, which is what the rest of this library
         * publishes by default.
         */
        HTTP,
        /**
         * OTLP/HTTP with a protobuf body over HTTP/2, by prior knowledge on cleartext and through ALPN
         * over TLS - the same wire {@link #GRPC} runs on.
         *
         * <p>This arm exists to separate two costs the other two conflate. gRPC is HTTP/2 and OTLP/HTTP
         * is conventionally HTTP/1.1, so {@code GRPC} against {@code HTTP} charges gRPC for HPACK,
         * per-stream state and flow control that any HTTP/2 client pays. {@code GRPC} against
         * {@code HTTP2} is the honest measure of what gRPC's framing and trailers actually add.
         */
        HTTP2
    }

    /**
     * Whether TLS is in the path.
     */
    public enum Transport {
        /**
         * Cleartext. gRPC speaks HTTP/2 by prior knowledge here.
         */
        PLAINTEXT,
        /**
         * TLS against the checked-in self-signed certificate, with gRPC reaching h2 through ALPN.
         */
        TLS
    }

    @State(Scope.Benchmark)
    public static class SenderState {

        @Param({"OK_HTTP", "JETTY"})
        ClientType clientType;

        @Param({"GRPC", "HTTP", "HTTP2"})
        Wire wire;

        @Param({"PLAINTEXT", "TLS"})
        Transport transport;

        /**
         * Payload size in bytes, in the range a real OTLP export occupies.
         */
        @Param({"16384"})
        int payloadSize;

        private Process server;
        private HttpSender httpSender;
        private GrpcSender grpcSender;
        private byte[] payload;

        /**
         * The caller-owned reply the gRPC arm fills in and reuses, which is the contract `GrpcSender`
         * states and the shape a real consumer uses.
         */
        private GrpcReply reply;

        private static Process startServer(Wire wire, Transport transport) throws Exception {
            return new ProcessBuilder(
                    System.getProperty("java.home") + "/bin/java",
                    "-cp", System.getProperty("java.class.path"),
                    StubGrpcOtlpServer.class.getName(),
                    modeOf(wire),
                    transport == Transport.TLS ? "tls" : "plaintext")
                    .redirectErrorStream(true)
                    .start();
        }

        /**
         * @param wire the arm under test
         * @return the mode argument {@link StubGrpcOtlpServer} expects for it
         */
        private static String modeOf(Wire wire) {
            switch (wire) {
                case GRPC:
                    return "grpc";
                case HTTP2:
                    return "http2";
                default:
                    return "http";
            }
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
            throw new IllegalStateException("The stub OTLP server did not report a port");
        }

        private static void closeQuietly(AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            payload = new byte[payloadSize];
            new Random(42).nextBytes(payload);
            reply = new GrpcReply();

            server = startServer(wire, transport);
            String address = (transport == Transport.TLS ? "https" : "http") + "://127.0.0.1:"
                    + portOf(server);

            SSLContext sslContext = transport == Transport.TLS ? StubOtlpServer.sslContext() : null;
            if (wire == Wire.GRPC) {
                grpcSender = grpcSender(address, sslContext);
            } else {
                httpSender = httpSender(address, sslContext,
                        wire == Wire.HTTP2 ? HttpVersion.HTTP_2 : HttpVersion.HTTP_1_1);
            }
            // Establish the connection and let every layer warm before measurement starts.
            for (int i = 0; i < 50; i++) {
                publish();
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (wire == Wire.GRPC) {
                closeQuietly(grpcSender);
            } else {
                closeQuietly(httpSender);
            }
            server.destroy();
        }

        void publish() throws Exception {
            if (wire == Wire.GRPC) {
                grpcSender.send(payload, 0, payload.length, null, reply.reset());
            } else {
                httpSender.send(payload, 0, payload.length, null);
            }
        }

        private GrpcSender grpcSender(String address, SSLContext sslContext) throws Exception {
            GrpcSenderSettings settings = GrpcSenderSettings.builder()
                    .endpointUrl(address)
                    .fullMethodName(StubGrpcOtlpServer.LOGS_METHOD)
                    .sslContext(sslContext)
                    .build();
            switch (clientType) {
                case OK_HTTP:
                    return new OkHttpGrpcSender(settings, StubOtlpServer.trustManager());
                case JETTY:
                    return new JettyGrpcSender(settings);
                default:
                    throw new IllegalStateException("Unhandled client " + clientType);
            }
        }

        private HttpSender httpSender(String address, SSLContext sslContext, HttpVersion httpVersion)
                throws Exception {
            HttpSenderSettings settings = HttpSenderSettings.builder()
                    .endpointUrl(address + StubOtlpServer.PATH)
                    .sslContext(sslContext)
                    .build();
            switch (clientType) {
                case OK_HTTP:
                    return new OkHttpSender(settings, StubOtlpServer.trustManager(), httpVersion);
                case JETTY:
                    return new JettyHttpSender(settings, null, httpVersion);
                default:
                    throw new IllegalStateException("Unhandled client " + clientType);
            }
        }
    }
}
