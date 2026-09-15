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
package org.lolaf.staffix.http.testkit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.http.Header;
import org.lolaf.staffix.api.http.HttpResponseException;
import org.lolaf.staffix.api.http.HttpSender;
import org.lolaf.staffix.api.http.HttpSenderSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contract every {@link HttpSender} implementation has to meet, tested against a real endpoint.
 *
 * <p>A client module's own test class extends this and implements {@link #createSender(HttpSenderSettings)},
 * then adds only what is peculiar to that client. Anything asserted here is behaviour a caller is
 * entitled to rely on whichever sender it was handed, so a test belongs here rather than in a module
 * unless it is about that client's own machinery.
 *
 * <p>Each test runs against a {@link StubHttpServer} on a free port, created before and closed after.
 * {@link #settings()} is already pointed at it.
 */
public abstract class AbstractHttpSenderTest {

    protected static final byte[] PAYLOAD = "a protobuf encoded batch of metrics".getBytes(StandardCharsets.UTF_8);

    protected StubHttpServer server;

    /**
     * A sender on the default settings, closed after each test.
     */
    protected HttpSender sender;

    /**
     * Builds the implementation under test.
     *
     * @param settings settings the sender must bind, always carrying this test's endpoint
     * @return a new sender, started and ready to publish
     */
    protected abstract HttpSender createSender(HttpSenderSettings settings);

    /**
     * Whether this client can report the status line's reason phrase on a failed response.
     *
     * <p>Two of the three can. The JDK's {@link java.net.http.HttpResponse} has no accessor for the
     * reason phrase at all, so {@code JdkHttpSender} has none to pass on and overrides this to
     * {@code false}. The difference is expressed here rather than left to each test class so that it
     * stays a stated property of the client rather than a silently missing assertion.
     *
     * @return {@code true} if a failed response should carry a non-empty reason phrase
     */
    protected boolean reportsReasonPhrase() {
        return true;
    }

    /**
     * Settings pointed at this test's stub server, for a test that needs a differently configured
     * sender than {@link #sender}.
     *
     * @return a builder carrying the endpoint and nothing else
     */
    protected HttpSenderSettings.HttpSenderSettingsBuilder settings() {
        return HttpSenderSettings.builder().endpointUrl(server.url());
    }

    @BeforeEach
    final void setUpBase() throws IOException {
        server = new StubHttpServer();
        sender = createSender(settings().build());
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
    void sendsThePayloadWithTheHeadersBoundAtConstruction() throws IOException {
        try (HttpSender configured = createSender(settings()
                .header("Authorization", "Basic dXNlcjpwYXNz")
                .header("User-Agent", "Staffix")
                .build())) {
            int status = configured.send(PAYLOAD, PAYLOAD.length);

            assertThat(status).isEqualTo(200);
            StubHttpServer.RecordedRequest request = server.lastReceived();
            assertThat(request.method).isEqualTo("POST");
            assertThat(request.body).isEqualTo(PAYLOAD);
            assertThat(request.header("Authorization")).isEqualTo("Basic dXNlcjpwYXNz");
            assertThat(request.header("User-Agent")).isEqualTo("Staffix");
        }
    }

    @Test
    void sendsTheDefaultContentTypeWithNothingAppendedToIt() throws IOException {
        sender.send(PAYLOAD, PAYLOAD.length);

        assertThat(server.lastReceived().header("Content-Type"))
                .as("micrometer's own OkHttp sender appends '; charset=utf-8' here, a defect these replace")
                .isEqualTo("application/x-protobuf");
    }

    @Test
    void sendsAConfiguredContentTypeExactlyAsGiven() throws IOException {
        try (HttpSender json = createSender(settings().contentType("application/json").build())) {
            json.send(PAYLOAD, PAYLOAD.length);

            assertThat(server.lastReceived().header("Content-Type")).isEqualTo("application/json");
        }
    }

    @Test
    void sendsOnlyTheRequestedSliceOfTheBuffer() throws IOException {
        byte[] buffer = new byte[PAYLOAD.length + 16];
        System.arraycopy(PAYLOAD, 0, buffer, 8, PAYLOAD.length);

        sender.send(buffer, 8, PAYLOAD.length, null);

        assertThat(server.lastReceived().body).isEqualTo(PAYLOAD);
    }

    @Test
    void refusesASliceThatDoesNotFitTheBuffer() {
        assertThatThrownBy(() -> sender.send(PAYLOAD, 4, PAYLOAD.length, null))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void addsThePerRequestHeadersAndReplacesABoundOneOfTheSameName() throws IOException {
        try (HttpSender configured = createSender(settings().header("User-Agent", "Staffix").build())) {
            configured.send(PAYLOAD, 0, PAYLOAD.length, new Header[]{
                    new Header("Content-Encoding", "gzip"),
                    new Header("User-Agent", "Staffix-Override")});

            StubHttpServer.RecordedRequest request = server.lastReceived();
            assertThat(request.header("Content-Encoding")).isEqualTo("gzip");
            assertThat(request.headers.get("User-Agent"))
                    .as("a per-request header replaces the bound one rather than being sent beside it")
                    .containsExactly("Staffix-Override");
        }
    }

    @Test
    void returnsTheStatusOfASuccessfulResponseThatCarriesNoBody() throws IOException {
        server.respondWith(204, "");

        assertThat(sender.send(PAYLOAD, PAYLOAD.length)).isEqualTo(204);
    }

    @Test
    void reportsTheStatusAndBodyOfAFailedResponse() {
        server.respondWith(500, "the collector is unwell");

        assertThatThrownBy(() -> sender.send(PAYLOAD, PAYLOAD.length))
                .isInstanceOf(HttpResponseException.class)
                .satisfies(thrown -> {
                    HttpResponseException e = (HttpResponseException) thrown;
                    assertThat(e.getStatusCode()).isEqualTo(500);
                    assertThat(e.getBody()).isEqualTo("the collector is unwell");
                });
    }

    @Test
    void reportsTheReasonPhraseOfAFailedResponse() {
        server.respondWith(503, "try again later");

        assertThatThrownBy(() -> sender.send(PAYLOAD, PAYLOAD.length))
                .isInstanceOf(HttpResponseException.class)
                .satisfies(thrown -> {
                    HttpResponseException e = (HttpResponseException) thrown;
                    assertThat(e.getStatusCode()).isEqualTo(503);
                    if (reportsReasonPhrase()) {
                        // Not the exact wording: the phrase comes from the stub server's own table and
                        // is not something this contract should pin down across JDK versions.
                        assertThat(e.getReasonPhrase()).isNotEmpty();
                        assertThat(e.getMessage()).contains(e.getReasonPhrase());
                    } else {
                        assertThat(e.getReasonPhrase()).isNull();
                    }
                });
    }

    @Test
    void failsWhenTheCollectorAnswersPastTheRequestTimeout() {
        server.respondAfter(2000);

        try (HttpSender impatient = createSender(settings().requestTimeout(Duration.ofMillis(200)).build())) {
            assertThatThrownBy(() -> impatient.send(PAYLOAD, PAYLOAD.length)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void reusesOneConnectionAcrossSuccessivePublishes() throws IOException {
        for (int i = 0; i < 5; i++) {
            sender.send(PAYLOAD, PAYLOAD.length);
        }

        assertThat(server.received()).hasSize(5);
        assertThat(server.clientPorts())
                .as("the pooled connection should be reused rather than reopened per publish")
                .hasSize(1);
    }

    @Test
    void sendsTheSameBufferAgainAfterItHasBeenRefilled() throws IOException {
        byte[] reused = Arrays.copyOf(PAYLOAD, PAYLOAD.length);

        sender.send(reused, reused.length);
        Arrays.fill(reused, (byte) 'x');
        sender.send(reused, reused.length);

        assertThat(server.received().get(0).body).isEqualTo(PAYLOAD);
        assertThat(server.received().get(1).body).containsOnly((byte) 'x');
    }

    @Test
    void closingTwiceIsHarmless() {
        sender.close();
        sender.close();
    }
}
