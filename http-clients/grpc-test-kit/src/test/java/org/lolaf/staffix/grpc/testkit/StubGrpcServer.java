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
package org.lolaf.staffix.grpc.testkit;

import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.Headers;
import okhttp3.Protocol;
import okio.Buffer;
import org.lolaf.staffix.api.grpc.GrpcFraming;
import org.lolaf.staffix.api.grpc.GrpcStatus;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stub gRPC endpoint, recording what each sender under test actually put on the wire.
 *
 * <p>Built on MockWebServer rather than on the {@code com.sun.net.httpserver} the HTTP test kit uses, for
 * a reason that decides the whole design: gRPC carries its outcome in HTTP/2 <strong>trailers</strong>,
 * and the JDK's bundled server speaks neither HTTP/2 nor trailers. Cleartext gRPC also needs HTTP/2 by
 * prior knowledge - there is no {@code h2c} upgrade in gRPC - which is why {@link Protocol#H2_PRIOR_KNOWLEDGE}
 * is the only protocol offered here.
 *
 * <p>By default every call is answered {@link GrpcStatus#OK} with an empty message. A test that cares
 * about a failure says so with {@link #respondWith(int, String)}, and one about retrying queues a
 * sequence with {@link #respondWithSequence(int...)} - the same shape the HTTP stub uses, so the two
 * kits read alike.
 */
public class StubGrpcServer implements AutoCloseable {

    /**
     * A method path in the shape gRPC puts on the wire, and the one OTLP logs use.
     */
    public static final String LOGS_METHOD = "/opentelemetry.proto.collector.logs.v1.LogsService/Export";

    private final MockWebServer server;
    private final List<RecordedRequest> received = new CopyOnWriteArrayList<>();
    private final Queue<Integer> scriptedStatuses = new ConcurrentLinkedQueue<>();

    private volatile int statusCode = GrpcStatus.OK;
    private volatile String statusMessage = "";
    private volatile byte[] responseMessage = new byte[0];
    private volatile boolean trailersOnly;
    private volatile boolean omitStatus;

    /**
     * Starts a server on a free loopback port, speaking cleartext HTTP/2.
     *
     * @throws IOException if no port could be bound
     */
    public StubGrpcServer() throws IOException {
        this.server = new MockWebServer();
        this.server.setProtocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE));
        this.server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                received.add(request);
                return respond();
            }
        });
        this.server.start();
    }

    /**
     * @return the address a sender should be pointed at, without a method path
     */
    public String url() {
        return "http://127.0.0.1:" + server.getPort();
    }

    /**
     * @return every call this server answered, in order
     */
    public List<RecordedRequest> received() {
        return received;
    }

    /**
     * @return the most recent call
     */
    public RecordedRequest lastReceived() {
        return received.get(received.size() - 1);
    }

    /**
     * Sets the standing answer, used for every call the scripted sequence does not cover.
     *
     * @param statusCode    the {@code grpc-status} to answer with
     * @param statusMessage the {@code grpc-message} to go with it
     */
    public void respondWith(int statusCode, String statusMessage) {
        this.statusCode = statusCode;
        this.statusMessage = statusMessage;
    }

    /**
     * Sets the payload returned with a successful call, framed as gRPC requires.
     *
     * @param message the response message, unframed
     */
    public void respondWithMessage(byte[] message) {
        this.responseMessage = message;
    }

    /**
     * Queues statuses to answer the next calls with, one each, ahead of the standing answer.
     *
     * @param statusCodes the statuses to hand out, in order
     */
    public void respondWithSequence(int... statusCodes) {
        for (int statusCode : statusCodes) {
            scriptedStatuses.add(statusCode);
        }
    }

    /**
     * Answers the next calls with a <strong>Trailers-Only</strong> response: the status arrives in the
     * headers and there are no trailers at all.
     *
     * <p>gRPC allows this for a call that fails before any message is produced, and a sender that only
     * ever looks in the trailers reads it as a call with no status - so it is worth being able to
     * provoke.
     */
    public void respondTrailersOnly() {
        this.trailersOnly = true;
    }

    /**
     * Answers the next calls with neither a trailer nor a header carrying {@code grpc-status}, which is
     * a broken peer and the one case a sender should refuse rather than interpret.
     */
    public void respondWithoutAnyStatus() {
        this.omitStatus = true;
    }

    /**
     * @return how many queued statuses are still unused, which is how a test asserts the number of
     * attempts a sender made
     */
    public int remainingScriptedResponses() {
        return scriptedStatuses.size();
    }

    @Override
    public void close() {
        server.close();
    }

    private MockResponse respond() {
        Integer scripted = scriptedStatuses.poll();
        int status = scripted == null ? statusCode : scripted;

        MockResponse.Builder response = new MockResponse.Builder()
                .code(200)
                .setHeader("content-type", "application/grpc");

        if (omitStatus) {
            // No grpc-status anywhere: neither in the headers nor in the trailers.
            return response.trailers(Headers.of()).build();
        }
        if (trailersOnly) {
            return response
                    .setHeader("grpc-status", Integer.toString(status))
                    .setHeader("grpc-message", statusMessage)
                    .trailers(Headers.of())
                    .build();
        }

        byte[] message = status == GrpcStatus.OK ? responseMessage : new byte[0];
        Buffer body = new Buffer();
        if (message.length > 0) {
            byte[] header = new byte[GrpcFraming.HEADER_LENGTH];
            GrpcFraming.writeHeader(header, 0, false, message.length);
            body.write(header);
            body.write(message);
        }
        return response
                .body(body)
                .trailers(Headers.of("grpc-status", Integer.toString(status),
                        "grpc-message", statusMessage))
                .build();
    }
}
