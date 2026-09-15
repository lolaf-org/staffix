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

import io.micrometer.core.ipc.http.HttpSender;
import io.micrometer.core.ipc.http.HttpUrlConnectionSender;
import io.micrometer.core.ipc.http.OkHttpSender;
import io.micrometer.registry.otlp.CompressionMode;
import io.micrometer.registry.otlp.OtlpHttpMetricsSender;
import io.micrometer.registry.otlp.OtlpMetricsSender;
import okhttp3.OkHttpClient;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.api.http.HttpVersion;
import org.lolaf.staffix.http.jdk.JdkHttpSender;
import org.lolaf.staffix.http.jetty.JettyHttpSender;
import org.lolaf.staffix.monitoring.micrometer.otlp.MicrometerHttpSenderAdapter;
import org.openjdk.jmh.annotations.*;

import javax.net.SocketFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Compares the {@link HttpSender} implementations an OTLP meter registry can publish through, on time
 * and on allocation. Run with the GC profiler, which is where the interesting number is:
 *
 * <pre>{@code
 * java -jar benchmarks/target/benchmarks.jar OtlpHttpSenderBenchmark -prof gc
 * }</pre>
 *
 * <p>The stub receiver runs in a JVM of its own ({@link StubOtlpServer}), so its allocation stays out
 * of this JVM's GC profile. What is measured is one publish: the sender plus the OTLP wrapper that a
 * registry puts in front of it, against a payload the size of a realistic export.
 *
 * <p>Four of the arms are this library's own senders, published through
 * {@link MicrometerHttpSenderAdapter}; the other two are micrometer's, kept as the baseline they are
 * worth being compared against.
 *
 * <p>The micrometer OkHttp arm is given a socket factory that sets {@code TCP_NODELAY}, which is not
 * what {@code new OkHttpClient()} does. Left on its default that sender writes the request head and body
 * as separate segments with Nagle enabled, and every publish stalls on the peer's 40 ms delayed-ACK
 * timer - measured here at 42 ms per publish against 0.7 ms with the option set. Benchmarking the
 * default would only measure that stall, so the option is set on the baseline arm, and
 * {@code org.lolaf.staffix.http.okhttp.OkHttpSender} sets it for itself.
 */
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgsPrepend = {"-Xmx1g", "-Xms1g"})
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class OtlpHttpSenderBenchmark {

    /**
     * Whether an arm needs the receiver to speak HTTP/2.
     *
     * @param senderType the arm
     * @return {@code true} for the HTTP/2 arms
     */
    static boolean needsHttp2Receiver(SenderType senderType) {
        return senderType == SenderType.OK_HTTP_H2 || senderType == SenderType.JETTY_H2;
    }

    @Benchmark
    public void publish(SenderState state) throws Exception {
        state.publish();
    }

    /**
     * Which {@link HttpSender} is under test.
     */
    public enum SenderType {
        /**
         * Micrometer's default, on {@link java.net.HttpURLConnection}.
         */
        HTTP_URL_CONNECTION,
        /**
         * Micrometer's OkHttp sender, given a socket factory that disables Nagle.
         */
        MICROMETER_OK_HTTP,
        /**
         * The JDK's {@link java.net.http.HttpClient}, through {@link JdkHttpSender}, on its HTTP/1.1
         * default.
         */
        JDK_HTTP_CLIENT,
        /**
         * The same, asked for HTTP/2. Against an HTTP/1.1 receiver that measures what the negotiation
         * costs when it fails: an {@code Upgrade: h2c} exchange over cleartext, an ALPN offer over TLS.
         */
        JDK_HTTP_CLIENT_H2,
        /**
         * OkHttp, through {@link org.lolaf.staffix.http.okhttp.OkHttpSender}, which disables Nagle and
         * passes the content type through unchanged.
         */
        OK_HTTP,
        /**
         * OkHttp asked for HTTP/2. Unlike {@link #JDK_HTTP_CLIENT_H2} this reaches a receiver that
         * actually speaks it, so it measures HTTP/2 rather than a refused negotiation.
         */
        OK_HTTP_H2,
        /**
         * The Jetty client, through {@link JettyHttpSender}.
         */
        JETTY,
        /**
         * The Jetty client asked for HTTP/2, through its HTTP/2 connection factory.
         */
        JETTY_H2
    }

    /**
     * Whether the stub receiver is reached over TLS.
     */
    public enum Transport {
        /**
         * Plaintext HTTP.
         */
        HTTP,
        /**
         * TLS, against the checked-in self-signed certificate.
         */
        HTTPS
    }

    @State(Scope.Benchmark)
    public static class SenderState {

        @Param({"HTTP_URL_CONNECTION", "MICROMETER_OK_HTTP", "JDK_HTTP_CLIENT", "JDK_HTTP_CLIENT_H2",
                "OK_HTTP", "OK_HTTP_H2", "JETTY", "JETTY_H2"})
        SenderType senderType;

        @Param({"HTTP", "HTTPS"})
        Transport transport;

        /**
         * Payload size in bytes, in the range a real OTLP export of a few hundred meters occupies.
         */
        @Param({"16384"})
        int payloadSize;

        private Process server;
        private HttpSender httpSender;
        private OtlpMetricsSender sender;
        private byte[] payload;
        private String address;

        private static Process startServer(Transport transport, SenderType senderType) throws Exception {
            String mode = (transport == Transport.HTTPS ? "https" : "http")
                    + (needsHttp2Receiver(senderType) ? "2" : "");
            return new ProcessBuilder(
                    System.getProperty("java.home") + "/bin/java",
                    "-cp", System.getProperty("java.class.path"),
                    StubOtlpServer.class.getName(),
                    mode)
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

            server = startServer(transport, senderType);
            address = (transport == Transport.HTTPS ? "https" : "http") + "://127.0.0.1:" + portOf(server)
                    + StubOtlpServer.PATH;

            httpSender = httpSender(StubOtlpServer.sslContext());
            sender = new OtlpHttpMetricsSender(httpSender);
            // Establish the connection and let every layer warm before measurement starts.
            for (int i = 0; i < 50; i++) {
                publish();
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (httpSender instanceof AutoCloseable) {
                closeQuietly((AutoCloseable) httpSender);
            }
            server.destroy();
        }

        void publish() throws Exception {
            sender.send(OtlpMetricsSender.Request.builder(payload)
                    .address(address)
                    .headers(Collections.singletonMap("Authorization", "Basic dXNlcjpwYXNz"))
                    .compressionMode(CompressionMode.NONE)
                    .build());
        }

        private HttpSender httpSender(SSLContext sslContext) throws Exception {
            switch (senderType) {
                case HTTP_URL_CONNECTION:
                    // HttpUrlConnectionSender takes no SSL configuration, so the stub's certificate has
                    // to be trusted globally for it to reach the HTTPS endpoint.
                    HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
                    return new HttpUrlConnectionSender();
                case MICROMETER_OK_HTTP:
                    return new OkHttpSender(new OkHttpClient.Builder()
                            .sslSocketFactory(sslContext.getSocketFactory(), StubOtlpServer.trustManager())
                            .socketFactory(new NoDelaySocketFactory())
                            .build());
                case JDK_HTTP_CLIENT:
                    return adapted(new JdkHttpSender(settings(sslContext)));
                case JDK_HTTP_CLIENT_H2:
                    return adapted(new JdkHttpSender(settings(sslContext), HttpVersion.HTTP_2, null));
                case OK_HTTP:
                    return adapted(new org.lolaf.staffix.http.okhttp.OkHttpSender(
                            settings(sslContext), StubOtlpServer.trustManager()));
                case OK_HTTP_H2:
                    return adapted(new org.lolaf.staffix.http.okhttp.OkHttpSender(
                            settings(sslContext), StubOtlpServer.trustManager(), HttpVersion.HTTP_2));
                case JETTY:
                    return adapted(new JettyHttpSender(settings(sslContext)));
                case JETTY_H2:
                    return adapted(new JettyHttpSender(settings(sslContext), null, HttpVersion.HTTP_2));
                default:
                    throw new IllegalStateException("Unhandled sender " + senderType);
            }
        }

        /**
         * The settings every one of this library's senders binds: this trial's endpoint, and the stub's
         * certificate.
         */
        private HttpSenderSettings settings(SSLContext sslContext) {
            return HttpSenderSettings.builder()
                    .endpointUrl(address)
                    .sslContext(sslContext)
                    .build();
        }

        private HttpSender adapted(org.lolaf.staffix.api.http.HttpSender sender) {
            return new MicrometerHttpSenderAdapter(sender, address);
        }

        /**
         * Hands OkHttp sockets with Nagle disabled; see this class's own documentation for why that is
         * not optional here.
         */
        private static final class NoDelaySocketFactory extends SocketFactory {

            private static Socket noDelay(Socket socket) throws IOException {
                socket.setTcpNoDelay(true);
                return socket;
            }

            @Override
            public Socket createSocket() throws IOException {
                return noDelay(new Socket());
            }

            @Override
            public Socket createSocket(String host, int port) throws IOException {
                return noDelay(new Socket(host, port));
            }

            @Override
            public Socket createSocket(String host, int port, InetAddress localAddress, int localPort)
                    throws IOException {
                return noDelay(new Socket(host, port, localAddress, localPort));
            }

            @Override
            public Socket createSocket(InetAddress host, int port) throws IOException {
                return noDelay(new Socket(host, port));
            }

            @Override
            public Socket createSocket(InetAddress host, int port, InetAddress localAddress, int localPort)
                    throws IOException {
                return noDelay(new Socket(host, port, localAddress, localPort));
            }
        }
    }
}
