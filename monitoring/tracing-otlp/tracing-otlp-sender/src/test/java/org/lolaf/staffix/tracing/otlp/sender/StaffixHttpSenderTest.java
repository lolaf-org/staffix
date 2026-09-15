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

import io.opentelemetry.sdk.common.export.Compressor;
import io.opentelemetry.sdk.common.export.HttpResponse;
import io.opentelemetry.sdk.common.export.MessageWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.http.Header;
import org.lolaf.staffix.api.http.HttpResponseException;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.http.jdk.JdkHttpSender;
import org.lolaf.staffix.http.testkit.StubHttpServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The adapter and its retry loop. Two kinds of test here: the ones asserting what reaches the wire run a
 * real {@link JdkHttpSender} against {@link StubHttpServer}, and the ones about failure handling use a
 * scripted delegate, because a stub server cannot be made to throw a {@link SocketTimeoutException} on
 * demand.
 *
 * <p>No test sleeps to let a retry happen. The backoff is milliseconds by configuration
 * ({@link TestHttpSenderConfig}) and every assertion waits on the callback that says the publish is done.
 */
class StaffixHttpSenderTest {

    private static final byte[] PAYLOAD = "a batch of spans".getBytes(StandardCharsets.UTF_8);

    private static final Compressor GZIP = new Compressor() {
        @Override
        public String getEncoding() {
            return "gzip";
        }

        @Override
        public OutputStream compress(OutputStream out) throws IOException {
            return new GZIPOutputStream(out);
        }
    };

    private StubHttpServer server;
    private ExecutorService executor;
    private TestHttpSenderConfig config;
    private Outcomes outcomes;

    private static MessageWriter writing(byte[] payload) {
        return new MessageWriter() {
            @Override
            public void writeMessage(OutputStream out) throws IOException {
                out.write(payload);
            }

            @Override
            public int getContentLength() {
                return payload.length;
            }
        };
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[512];
            int read;
            while ((read = in.read(chunk)) != -1) {
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        server = new StubHttpServer("/v1/traces");
        // Single-threaded, as OtelTracing builds it: a sender that sleeps its backoff would show up here.
        executor = Executors.newSingleThreadExecutor();
        config = new TestHttpSenderConfig(executor);
        outcomes = new Outcomes();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        server.close();
    }

    private StaffixHttpSender senderOnTheStub() {
        return new StaffixHttpSender(
                new JdkHttpSender(HttpSenderSettings.builder().endpointUrl(server.url()).build()), config);
    }

    private StaffixHttpSender senderOn(org.lolaf.staffix.api.http.HttpSender delegate) {
        return new StaffixHttpSender(delegate, config);
    }

    @Test
    void publishesThePayloadWithTheConfiguredHeaders() throws Exception {
        config.headersSupplier = () -> TestHttpSenderConfig.headers("Authorization", "Basic abc");

        StaffixHttpSender sender = senderOnTheStub();
        sender.send(writing(PAYLOAD), outcomes::response, outcomes::error);

        assertThat(outcomes.awaitResponse().getStatusCode()).isEqualTo(200);
        assertThat(server.lastReceived().body).isEqualTo(PAYLOAD);
        assertThat(server.lastReceived().header("Authorization")).isEqualTo("Basic abc");
        sender.shutdown();
    }

    @Test
    void compressesThePayloadAndSaysSoInTheHeaders() throws Exception {
        config.compressor = GZIP;

        StaffixHttpSender sender = senderOnTheStub();
        sender.send(writing(PAYLOAD), outcomes::response, outcomes::error);

        assertThat(outcomes.awaitResponse().getStatusCode()).isEqualTo(200);
        assertThat(server.lastReceived().header("Content-Encoding")).isEqualTo("gzip");
        assertThat(gunzip(server.lastReceived().body)).isEqualTo(PAYLOAD);
        sender.shutdown();
    }

    @Test
    void retriesARetryableStatusAndSendsTheSameBytesAgain() throws Exception {
        server.respondWithSequence(503);

        StaffixHttpSender sender = senderOnTheStub();
        sender.send(writing(PAYLOAD), outcomes::response, outcomes::error);

        assertThat(outcomes.awaitResponse().getStatusCode()).isEqualTo(200);
        assertThat(server.received()).hasSize(2);
        assertThat(server.received().get(0).body).isEqualTo(PAYLOAD);
        assertThat(server.received().get(1).body)
                .as("a retry must resend the payload, not a buffer somebody else has refilled")
                .isEqualTo(PAYLOAD);
        sender.shutdown();
    }

    @Test
    void reportsANonRetryableStatusThroughOnResponseWithoutRetrying() throws Exception {
        server.respondWith(400, "malformed export");

        StaffixHttpSender sender = senderOnTheStub();
        sender.send(writing(PAYLOAD), outcomes::response, outcomes::error);

        HttpResponse response = outcomes.awaitResponse();
        assertThat(response.getStatusCode()).isEqualTo(400);
        assertThat(new String(response.getResponseBody(), StandardCharsets.UTF_8))
                .isEqualTo("malformed export");
        assertThat(server.received())
                .as("a 400 will not become a 200 by being repeated")
                .hasSize(1);
        assertThat(outcomes.errors).isEmpty();
        sender.shutdown();
    }

    @Test
    void givesUpAtTheAttemptLimitAndReportsTheLastStatus() throws Exception {
        server.respondWith(503, "still unwell");

        StaffixHttpSender sender = senderOnTheStub();
        sender.send(writing(PAYLOAD), outcomes::response, outcomes::error);

        assertThat(outcomes.awaitResponse().getStatusCode()).isEqualTo(503);
        assertThat(server.received())
                .as("three attempts, which is what the test policy allows")
                .hasSize(3);
        sender.shutdown();
    }

    @Test
    void retriesATransportFailureThePolicyConsidersTransient() throws Exception {
        ScriptedDelegate delegate = new ScriptedDelegate();
        delegate.failWith(new SocketTimeoutException("first attempt timed out"));

        StaffixHttpSender sender = senderOn(delegate);
        sender.send(writing(PAYLOAD), outcomes::response, outcomes::error);

        assertThat(outcomes.awaitResponse().getStatusCode()).isEqualTo(200);
        assertThat(delegate.calls.get()).isEqualTo(2);
        sender.shutdown();
    }

    @Test
    void reportsATransportFailureThePolicyRejectsThroughOnErrorWithoutRetrying() throws Exception {
        ScriptedDelegate delegate = new ScriptedDelegate();
        delegate.failWith(new IOException("not a transient failure"));

        StaffixHttpSender sender = senderOn(delegate);
        sender.send(writing(PAYLOAD), outcomes::response, outcomes::error);

        assertThat(outcomes.awaitError()).isInstanceOf(IOException.class).hasMessage("not a transient failure");
        assertThat(delegate.calls.get()).isEqualTo(1);
        assertThat(outcomes.responses).isEmpty();
        sender.shutdown();
    }

    @Test
    void reportsARuntimeFailureThroughOnErrorAndStillReleasesTheBuffer() throws Exception {
        ScriptedDelegate delegate = new ScriptedDelegate();
        delegate.failWith(new IllegalStateException("the client is broken"));
        PayloadBuffers buffers = new PayloadBuffers(64, 2);

        StaffixHttpSender sender = new StaffixHttpSender(
                delegate, config, buffers, new RetrySchedule(config.retryPolicy, () -> 0.0));
        sender.send(writing(PAYLOAD), outcomes::response, outcomes::error);

        assertThat(outcomes.awaitError()).isInstanceOf(IllegalStateException.class);
        assertThat(buffers.pooled())
                .as("a client that throws must not also cost us the buffer")
                .isEqualTo(1);
        sender.shutdown();
    }

    @Test
    void keepsEachPayloadIntactWhenAnExportIsRetriedAcrossAnother() throws Exception {
        byte[] second = "an entirely different batch".getBytes(StandardCharsets.UTF_8);
        ScriptedDelegate delegate = new ScriptedDelegate();
        delegate.failWith(new SocketTimeoutException("first attempt timed out"));
        Outcomes secondOutcomes = new Outcomes();

        StaffixHttpSender sender = senderOn(delegate);
        sender.send(writing(PAYLOAD), outcomes::response, outcomes::error);
        sender.send(writing(second), secondOutcomes::response, secondOutcomes::error);

        assertThat(outcomes.awaitResponse().getStatusCode()).isEqualTo(200);
        assertThat(secondOutcomes.awaitResponse().getStatusCode()).isEqualTo(200);
        assertThat(delegate.sent)
                .as("every payload that reached the delegate is one of the two, uncorrupted")
                .allSatisfy(sent -> assertThat(Arrays.equals(sent, PAYLOAD) || Arrays.equals(sent, second))
                        .isTrue());
        assertThat(delegate.sent).filteredOn(sent -> Arrays.equals(sent, PAYLOAD)).hasSize(2);
        sender.shutdown();
    }

    @Test
    void reusesTheResponseObjectWhileTheStatusRepeats() throws Exception {
        StaffixHttpSender sender = senderOnTheStub();

        sender.send(writing(PAYLOAD), outcomes::response, outcomes::error);
        HttpResponse first = outcomes.awaitResponse();
        sender.send(writing(PAYLOAD), outcomes::response, outcomes::error);
        HttpResponse second = outcomes.awaitResponse();

        assertThat(second).isSameAs(first);
        sender.shutdown();
    }

    @Test
    void shutdownClosesTheDelegateAndLeavesTheBorrowedExecutorRunning() {
        ScriptedDelegate delegate = new ScriptedDelegate();
        StaffixHttpSender sender = senderOn(delegate);

        assertThat(sender.shutdown().isSuccess()).isTrue();

        assertThat(delegate.closed).isTrue();
        assertThat(executor.isShutdown())
                .as("the executor belongs to the config, not to this sender")
                .isFalse();
        assertThat(sender.shutdown().isSuccess()).as("shutdown is idempotent").isTrue();
    }

    /**
     * An exporter is not obliged to supply an executor - {@code OtlpHttpSpanExporter.builder()} does not
     * - and publishing on the calling thread is not an acceptable fallback, because with a
     * {@code SimpleSpanProcessor} that thread is the application's, ending a span.
     */
    @Test
    void publishesOnAThreadOfItsOwnWhenTheExporterSuppliedNoExecutor() throws Exception {
        config.executorService = null;

        StaffixHttpSender sender = senderOnTheStub();
        sender.send(writing(PAYLOAD), outcomes::response, outcomes::error);

        assertThat(outcomes.awaitResponse().getStatusCode()).isEqualTo(200);
        assertThat(server.lastReceived().body).isEqualTo(PAYLOAD);
        assertThat(sender.shutdown().isSuccess()).isTrue();
    }

    @Test
    void refusesToSendOnceShutDown() throws Exception {
        StaffixHttpSender sender = senderOnTheStub();
        sender.shutdown();

        sender.send(writing(PAYLOAD), outcomes::response, outcomes::error);

        assertThat(outcomes.awaitError()).isInstanceOf(IOException.class);
        assertThat(server.received()).isEmpty();
    }

    /**
     * Collects what the sender reported, so an assertion can wait for a publish to finish rather than
     * guess how long it takes.
     */
    private static final class Outcomes {

        final List<HttpResponse> responses = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        private final BlockingQueue<Object> completions = new ArrayBlockingQueue<>(16);

        void response(HttpResponse response) {
            responses.add(response);
            completions.add(response);
        }

        void error(Throwable error) {
            errors.add(error);
            completions.add(error);
        }

        HttpResponse awaitResponse() throws InterruptedException {
            Object completion = await();
            assertThat(completion).as("expected a response, got %s", completion).isInstanceOf(HttpResponse.class);
            return (HttpResponse) completion;
        }

        Throwable awaitError() throws InterruptedException {
            Object completion = await();
            assertThat(completion).as("expected an error, got %s", completion).isInstanceOf(Throwable.class);
            return (Throwable) completion;
        }

        private Object await() throws InterruptedException {
            Object completion = completions.poll(10, TimeUnit.SECONDS);
            assertThat(completion).as("the publish never completed").isNotNull();
            return completion;
        }
    }

    /**
     * A delegate that fails its first calls with scripted throwables and succeeds afterwards, recording a
     * copy of every payload it was given.
     */
    private static final class ScriptedDelegate implements org.lolaf.staffix.api.http.HttpSender {

        final List<byte[]> sent = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        final BlockingQueue<Throwable> failures = new ArrayBlockingQueue<>(8);
        volatile boolean closed;

        void failWith(Throwable failure) {
            failures.add(failure);
        }

        @Override
        public int send(byte[] data, int offset, int length, Header[] extraHeaders) throws IOException {
            calls.incrementAndGet();
            sent.add(Arrays.copyOfRange(data, offset, offset + length));
            Throwable failure = failures.poll();
            if (failure instanceof HttpResponseException) {
                throw (HttpResponseException) failure;
            }
            if (failure instanceof IOException) {
                throw (IOException) failure;
            }
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            return 200;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
