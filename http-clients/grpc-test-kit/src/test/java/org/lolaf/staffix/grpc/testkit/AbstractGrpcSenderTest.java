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

import mockwebserver3.RecordedRequest;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.grpc.*;
import org.lolaf.staffix.api.http.Header;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contract every {@link GrpcSender} implementation has to meet, whichever client is underneath.
 *
 * <p>A client module's test class extends this, implements {@link #createSender(GrpcSenderSettings)},
 * and adds only what is peculiar to that client. Only two modules can: gRPC reports its outcome in
 * HTTP/2 trailers, so a client that cannot read them cannot serve gRPC, which permanently excludes
 * {@code jdk}.
 *
 * <p>Writing the contract down here rather than per module is what keeps the implementations from
 * quietly disagreeing - the HTTP kit caught exactly that between the JDK client and the other two, where
 * one appended a per-request header that the others replaced.
 */
public abstract class AbstractGrpcSenderTest {

    protected static final byte[] PAYLOAD =
            "a protobuf encoded batch of log records".getBytes(StandardCharsets.UTF_8);

    protected StubGrpcServer server;

    /**
     * A sender on the default settings, closed after each test.
     */
    protected GrpcSender sender;

    /**
     * A reply to send with, reused across the calls of one test exactly as a real caller reuses one.
     */
    protected GrpcReply reply;

    private static byte[] messageOf(RecordedRequest request) {
        ByteString body = request.getBody();
        assertThat(body).isNotNull();
        byte[] framed = body.toByteArray();
        assertThat(framed.length)
                .as("a gRPC request body is a five byte header and then the message")
                .isGreaterThanOrEqualTo(GrpcFraming.HEADER_LENGTH);
        int length = GrpcFraming.messageLength(framed, 0);
        return Arrays.copyOfRange(framed, GrpcFraming.HEADER_LENGTH, GrpcFraming.HEADER_LENGTH + length);
    }

    /**
     * Builds the implementation under test.
     *
     * @param settings settings the sender must bind, always carrying this test's endpoint and method
     * @return a new sender, ready to call
     */
    protected abstract GrpcSender createSender(GrpcSenderSettings settings);

    /**
     * Settings pointed at this test's stub, for a test needing a differently configured sender.
     *
     * @return a builder carrying the endpoint and the method, and nothing else
     */
    protected GrpcSenderSettings.GrpcSenderSettingsBuilder settings() {
        return GrpcSenderSettings.builder()
                .endpointUrl(server.url())
                .fullMethodName(StubGrpcServer.LOGS_METHOD);
    }

    @BeforeEach
    final void setUpBase() throws IOException {
        server = new StubGrpcServer();
        sender = createSender(settings().build());
        reply = new GrpcReply();
    }

    @AfterEach
    final void tearDownBase() {
        if (sender != null) {
            sender.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void framesTheMessageAndCallsTheBoundMethod() throws IOException {
        sender.send(PAYLOAD, PAYLOAD.length, reply);

        RecordedRequest request = server.lastReceived();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getTarget()).isEqualTo(StubGrpcServer.LOGS_METHOD);
        assertThat(messageOf(request)).isEqualTo(PAYLOAD);
    }

    /**
     * Callers disagree about the leading slash - OpenTelemetry's {@code GrpcSenderConfig} omits it, the
     * wire format requires it - so a sender must accept a method name either way. This is the shape that
     * is easy to miss, because the obvious constant to test with already has the slash.
     */
    @Test
    void acceptsAMethodNameWithoutItsLeadingSlash() throws IOException {
        String withoutSlash = StubGrpcServer.LOGS_METHOD.substring(1);

        try (GrpcSender lenient = createSender(settings().fullMethodName(withoutSlash).build())) {
            lenient.send(PAYLOAD, PAYLOAD.length, reply);
        }

        assertThat(server.lastReceived().getTarget()).isEqualTo(StubGrpcServer.LOGS_METHOD);
    }

    @Test
    void sendsTheHeadersGrpcRequires() throws IOException {
        sender.send(PAYLOAD, PAYLOAD.length, reply);

        RecordedRequest request = server.lastReceived();
        assertThat(request.getHeaders().get("content-type")).startsWith("application/grpc");
        assertThat(request.getHeaders().get("te"))
                .as("a gRPC server may refuse a request that does not announce trailer support")
                .isEqualTo("trailers");
    }

    @Test
    void sendsTheRequestTimeoutAsTheGrpcDeadline() throws IOException {
        try (GrpcSender impatient = createSender(settings()
                .requestTimeout(Duration.ofSeconds(3)).build())) {
            impatient.send(PAYLOAD, PAYLOAD.length, reply);
        }

        assertThat(server.lastReceived().getHeaders().get("grpc-timeout"))
                .as("the deadline travels with the call so the server can abandon it too")
                .isNotNull();
    }

    @Test
    void sendsTheHeadersBoundAtConstruction() throws IOException {
        try (GrpcSender configured = createSender(settings()
                .header("Authorization", "Basic dXNlcjpwYXNz").build())) {
            configured.send(PAYLOAD, PAYLOAD.length, reply);
        }

        assertThat(server.lastReceived().getHeaders().get("Authorization")).isEqualTo("Basic dXNlcjpwYXNz");
    }

    @Test
    void addsThePerCallHeadersAndReplacesABoundOneOfTheSameName() throws IOException {
        try (GrpcSender configured = createSender(settings()
                .header("X-Bound", "from-settings")
                .header("X-Overridden", "from-settings").build())) {
            configured.send(PAYLOAD, 0, PAYLOAD.length,
                    new Header[]{new Header("X-Overridden", "from-call"), new Header("X-Extra", "per-call")},
                    reply);
        }

        RecordedRequest request = server.lastReceived();
        assertThat(request.getHeaders().get("X-Bound")).isEqualTo("from-settings");
        assertThat(request.getHeaders().get("X-Extra")).isEqualTo("per-call");
        assertThat(request.getHeaders().values("X-Overridden"))
                .as("a per-call header replaces the bound one rather than being sent beside it")
                .containsExactly("from-call");
    }

    @Test
    void sendsOnlyTheRequestedSliceOfTheBuffer() throws IOException {
        byte[] buffer = "XXXXa protobuf encoded batchYYYY".getBytes(StandardCharsets.UTF_8);

        sender.send(buffer, 4, 24, null, reply);

        assertThat(messageOf(server.lastReceived()))
                .isEqualTo("a protobuf encoded batch".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void reportsASuccessfulCall() throws IOException {
        GrpcReply returned = sender.send(PAYLOAD, PAYLOAD.length, reply);

        assertThat(returned).isSameAs(reply);
        assertThat(returned.isOk()).isTrue();
        assertThat(returned.getStatusCode()).isEqualTo(GrpcStatus.OK);
        assertThat(returned.getMessageLength()).isZero();
    }

    @Test
    void returnsTheResponsePayloadOfASuccessfulCall() throws IOException {
        byte[] response = "a partial success".getBytes(StandardCharsets.UTF_8);
        server.respondWithMessage(response);

        sender.send(PAYLOAD, PAYLOAD.length, reply);

        assertThat(reply.getMessageLength()).isEqualTo(response.length);
        assertThat(Arrays.copyOf(reply.getMessage(), reply.getMessageLength())).isEqualTo(response);
    }

    /**
     * The decision that shapes this interface: a failed call is the server's considered answer, not a
     * broken exchange, and both callers of a {@code GrpcSender} inspect it.
     */
    @Test
    void reportsAFailedCallAsAReplyRatherThanAnException() throws IOException {
        server.respondWith(GrpcStatus.RESOURCE_EXHAUSTED, "collector is over quota");

        GrpcReply returned = sender.send(PAYLOAD, PAYLOAD.length, reply);

        assertThat(returned.isOk()).isFalse();
        assertThat(returned.getStatusCode()).isEqualTo(GrpcStatus.RESOURCE_EXHAUSTED);
        assertThat(returned.getStatusMessage()).isEqualTo("collector is over quota");
    }

    /**
     * A failed gRPC call still answers HTTP 200, so a sender that read the HTTP status would call this a
     * success.
     */
    @Test
    void doesNotMistakeTheHttpStatusForTheCallOutcome() throws IOException {
        server.respondWith(GrpcStatus.UNAVAILABLE, "restarting");

        sender.send(PAYLOAD, PAYLOAD.length, reply);

        assertThat(reply.getStatusCode()).isEqualTo(GrpcStatus.UNAVAILABLE);
    }

    /**
     * gRPC allows a call that fails before producing any message to answer with the status in the
     * headers and no trailers at all. A sender that only ever looks in the trailers sees no status.
     */
    @Test
    void handlesATrailersOnlyResponse() throws IOException {
        server.respondWith(GrpcStatus.UNIMPLEMENTED, "no such method");
        server.respondTrailersOnly();

        sender.send(PAYLOAD, PAYLOAD.length, reply);

        assertThat(reply.getStatusCode()).isEqualTo(GrpcStatus.UNIMPLEMENTED);
        assertThat(reply.getStatusMessage()).isEqualTo("no such method");
    }

    @Test
    void refusesAResponseCarryingNoStatusAtAll() {
        server.respondWithoutAnyStatus();

        assertThatThrownBy(() -> sender.send(PAYLOAD, PAYLOAD.length, reply))
                .as("a peer that answers without a grpc-status is broken, not merely unsuccessful")
                .isInstanceOf(IOException.class);
    }

    @Test
    void compressesTheMessageWhenAskedTo() throws IOException {
        try (GrpcSender compressing = createSender(settings()
                .compression(GrpcSenderSettings.GrpcCompression.GZIP).build())) {
            compressing.send(PAYLOAD, PAYLOAD.length, reply);
        }

        RecordedRequest request = server.lastReceived();
        assertThat(request.getHeaders().get("grpc-encoding")).isEqualTo("gzip");
        byte[] framed = request.getBody().toByteArray();
        assertThat(GrpcFraming.isCompressed(framed, 0))
                .as("the frame's compressed flag says so as well as the header")
                .isTrue();
    }

    @Test
    void reusesOneConnectionAcrossSuccessiveCalls() throws IOException {
        for (int i = 0; i < 5; i++) {
            sender.send(PAYLOAD, PAYLOAD.length, reply.reset());
        }

        assertThat(server.received()).hasSize(5);
        assertThat(server.received())
                .as("HTTP/2 multiplexes, so five calls should share one connection")
                .allSatisfy(request -> assertThat(request.getConnectionIndex()).isZero());
    }

    @Test
    void sendsTheSameBufferAgainAfterItHasBeenRefilled() throws IOException {
        byte[] reused = Arrays.copyOf(PAYLOAD, PAYLOAD.length);

        sender.send(reused, reused.length, reply.reset());
        Arrays.fill(reused, (byte) 'x');
        sender.send(reused, reused.length, reply.reset());

        assertThat(messageOf(server.received().get(0))).isEqualTo(PAYLOAD);
        assertThat(messageOf(server.received().get(1))).containsOnly((byte) 'x');
    }

    @Test
    void closingTwiceIsHarmless() {
        sender.close();
        sender.close();
    }
}
