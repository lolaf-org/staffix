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

import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.Headers;
import okhttp3.Protocol;
import okio.Buffer;
import org.lolaf.staffix.api.grpc.GrpcFraming;

import javax.net.ServerSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;

/**
 * A receiver that answers both OTLP/gRPC and OTLP/HTTP, run out of process by
 * {@link OtlpGrpcSenderBenchmark}.
 *
 * <p><strong>Why this is not {@link StubOtlpServer}.</strong> That one writes a fixed HTTP/1.1 response
 * onto a raw socket, which is all the HTTP senders need and costs nothing. gRPC cannot be served that
 * way: it requires HTTP/2 - by prior knowledge on cleartext, since gRPC has no {@code h2c} upgrade - and
 * it reports its outcome in <strong>trailers</strong>, which means framing HTTP/2 and speaking HPACK.
 * MockWebServer already does both, so it is used here rather than hand-rolled.
 *
 * <p><strong>Why it also serves plain HTTP, over both versions.</strong> The benchmark's whole purpose is
 * to compare the two protocols, and a comparison against two different servers measures the servers as
 * much as the protocols. Every arm therefore faces this one implementation, and only the protocol under
 * test differs. The {@code http2} mode exists because gRPC is HTTP/2 and OTLP/HTTP is conventionally
 * HTTP/1.1, so comparing only those two confounds the cost of gRPC with the cost of HTTP/2; the third
 * arm publishes OTLP/HTTP over HTTP/2 and separates them.
 * That makes the HTTP numbers here <em>not</em> comparable with {@code OtlpHttpSenderBenchmark}'s, which
 * runs against the cheaper raw-socket stub - they are comparable with the gRPC arm beside them, which is
 * the point.
 *
 * <p><strong>Nagle is disabled on the accepted sockets</strong>, and that is not a detail. A gRPC response
 * is three writes - HEADERS, DATA, then the TRAILERS carrying {@code grpc-status} - so with Nagle left on
 * the server, the later ones wait on the client's delayed-ACK timer and every call costs about 20 ms.
 * Measured that way gRPC looks some 500x slower than HTTP, which is an artefact of this stub rather than
 * anything about the protocol; the HTTP arm never shows it because its whole response is one small write.
 * Both this library's gRPC senders already set {@code TCP_NODELAY} on their side.
 *
 * <p><strong>Its recorded-request queue is drained continuously</strong>, which a benchmark needs and a
 * test does not. MockWebServer records every exchange into an unbounded queue whatever the dispatcher
 * does, and each entry holds that request's body; a run publishing a 16 KB payload for tens of seconds
 * therefore piles up hundreds of megabytes in this process and the receiver's own GC starts dominating
 * the client's timings. It shows up as latency that gets worse and noisier the longer the run, which is
 * the opposite of what more iterations should do.
 *
 * <p>Like {@link StubOtlpServer} it runs in its own JVM and prints {@code PORT <n>} on standard output
 * once bound. That is not a convenience: {@code gc.alloc.rate.norm} counts allocation in the benchmark's
 * own JVM, so a receiver sharing it would be charged to every measurement, and MockWebServer allocates
 * far more per exchange than the client under test does.
 */
public final class StubGrpcOtlpServer {

    /**
     * A method path in the shape gRPC puts on the wire, and the one OTLP logs use.
     */
    public static final String LOGS_METHOD = "/opentelemetry.proto.collector.logs.v1.LogsService/Export";

    private StubGrpcOtlpServer() {
    }

    /**
     * @param args {@code grpc}, {@code http} or {@code http2} as the first argument, and {@code tls} as
     *             an optional second
     * @throws Exception if the server or its TLS context cannot be built
     */
    public static void main(String[] args) throws Exception {
        boolean grpc = args.length > 0 && args[0].equals("grpc");
        // Both gRPC and the OTLP/HTTP-over-h2 arm need HTTP/2; only gRPC needs the framed response.
        boolean http2 = grpc || (args.length > 0 && args[0].equals("http2"));
        boolean tls = args.length > 1 && args[1].equals("tls");

        MockWebServer server = new MockWebServer();
        server.setServerSocketFactory(new NoDelayServerSocketFactory());
        if (tls) {
            server.useHttps(StubOtlpServer.sslContext().getSocketFactory());
            // Over TLS the protocol is settled by ALPN, so the h2 arms offer it and the h1 arm does not.
            server.setProtocols(http2
                    ? Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
                    : Collections.singletonList(Protocol.HTTP_1_1));
        } else if (http2) {
            // Cleartext HTTP/2 is HTTP/2 from the first byte; there is no upgrade handshake to
            // negotiate, for gRPC or for the OTLP/HTTP arm that matches it.
            server.setProtocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE));
        }

        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                // Built per call rather than once: a MockResponse carries its body as a Buffer, and
                // handing the same one out repeatedly would serve it drained. The cost lands in this
                // JVM, which is measured by nobody.
                return grpc ? grpcResponse() : httpResponse();
            }
        });

        server.start(InetAddress.getByName("127.0.0.1"), 0);
        startRequestDrain(server);
        System.out.println("PORT " + server.getPort());
        System.out.flush();
        Thread.currentThread().join();
    }

    /**
     * Throws away the recorded requests as they arrive, so the queue holding them cannot grow.
     *
     * @param server the server to drain
     */
    private static void startRequestDrain(MockWebServer server) {
        Thread drain = new Thread(() -> {
            try {
                while (true) {
                    server.takeRequest();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "stub-otlp-drain");
        drain.setDaemon(true);
        drain.start();
    }

    /**
     * A successful export: HTTP 200, an empty {@code ExportResponse} in one gRPC frame, and
     * {@code grpc-status: 0} in the trailers.
     *
     * <p>An OTLP export response carrying no {@code partial_success} is an empty protobuf message, so
     * the frame is its five-byte header and nothing after it.
     */
    private static MockResponse grpcResponse() {
        byte[] header = new byte[GrpcFraming.HEADER_LENGTH];
        GrpcFraming.writeHeader(header, 0, false, 0);
        Buffer body = new Buffer();
        body.write(header);
        return new MockResponse.Builder()
                .code(200)
                .setHeader("content-type", "application/grpc")
                .body(body)
                .trailers(Headers.of("grpc-status", "0", "grpc-message", ""))
                .build();
    }

    /**
     * A successful export over plain HTTP: 200 with an empty body, which is what a collector answers
     * and what {@link StubOtlpServer} writes. Served for both the {@code http} and the {@code http2}
     * arms - they differ only in the HTTP version underneath, which is the whole point of having both.
     */
    private static MockResponse httpResponse() {
        return new MockResponse.Builder().code(200).build();
    }

    /**
     * Hands out server sockets whose accepted connections have Nagle disabled; see this class's own
     * documentation for why measuring without it is measuring the wrong thing.
     */
    private static final class NoDelayServerSocketFactory extends ServerSocketFactory {

        @Override
        public ServerSocket createServerSocket() throws IOException {
            return new NoDelayServerSocket();
        }

        @Override
        public ServerSocket createServerSocket(int port) throws IOException {
            return new NoDelayServerSocket(port, 50, null);
        }

        @Override
        public ServerSocket createServerSocket(int port, int backlog) throws IOException {
            return new NoDelayServerSocket(port, backlog, null);
        }

        @Override
        public ServerSocket createServerSocket(int port, int backlog, InetAddress address)
                throws IOException {
            return new NoDelayServerSocket(port, backlog, address);
        }
    }

    private static final class NoDelayServerSocket extends ServerSocket {

        NoDelayServerSocket() throws IOException {
            super();
        }

        NoDelayServerSocket(int port, int backlog, InetAddress address) throws IOException {
            super(port, backlog, address);
        }

        @Override
        public Socket accept() throws IOException {
            Socket socket = super.accept();
            socket.setTcpNoDelay(true);
            return socket;
        }
    }
}
