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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stub server is test infrastructure the three client modules all rest on, so the parts of it that
 * are not exercised by simply publishing through it are pinned down here - the configurable path, and
 * the scripted response queue a retry test needs.
 *
 * <p>Published through the JDK's own client rather than one of ours: this is a test of the server, and
 * routing it through a sender under test would make a failure ambiguous.
 */
class StubHttpServerTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private static HttpResponse<String> post(StubHttpServer server) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.url()))
                .POST(HttpRequest.BodyPublishers.ofString("payload"))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesTheMetricsPathByDefault() throws Exception {
        try (StubHttpServer server = new StubHttpServer()) {
            assertThat(server.url()).endsWith("/v1/metrics");

            assertThat(post(server).statusCode()).isEqualTo(200);
            assertThat(server.received()).hasSize(1);
        }
    }

    @Test
    void servesAConfiguredPathAndPointsAtItsOwnUrl() throws Exception {
        try (StubHttpServer server = new StubHttpServer("/v1/traces")) {
            assertThat(server.url()).endsWith("/v1/traces");

            assertThat(post(server).statusCode()).isEqualTo(200);
            assertThat(server.received()).hasSize(1);
        }
    }

    @Test
    void handsOutTheScriptedStatusesInOrderThenTheStandingResponse() throws Exception {
        try (StubHttpServer server = new StubHttpServer()) {
            server.respondWith(200, "the standing answer");
            server.respondWithSequence(503, 429);

            assertThat(post(server).statusCode()).isEqualTo(503);
            assertThat(post(server).statusCode()).isEqualTo(429);

            HttpResponse<String> afterTheQueueDrains = post(server);
            assertThat(afterTheQueueDrains.statusCode()).isEqualTo(200);
            assertThat(afterTheQueueDrains.body()).isEqualTo("the standing answer");
        }
    }

    @Test
    void countsDownTheScriptedResponsesSoATestCanAssertTheAttemptsMade() throws Exception {
        try (StubHttpServer server = new StubHttpServer()) {
            server.respondWithSequence(503, 503, 503);
            assertThat(server.remainingScriptedResponses()).isEqualTo(3);

            post(server);
            post(server);

            assertThat(server.remainingScriptedResponses()).isEqualTo(1);
        }
    }

    @Test
    void answersAScriptedStatusWithNoBody() throws Exception {
        try (StubHttpServer server = new StubHttpServer()) {
            server.respondWith(200, "the standing answer");
            server.respondWithSequence(503);

            assertThat(post(server).body()).isEmpty();
        }
    }

    @Test
    void recordsEveryRequestItAnsweredFromTheQueue() throws Exception {
        try (StubHttpServer server = new StubHttpServer()) {
            server.respondWithSequence(503, 200);

            post(server);
            post(server);

            assertThat(server.received()).hasSize(2);
            assertThat(server.lastReceived().body).isEqualTo("payload".getBytes());
        }
    }
}
