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
package org.lolaf.staffix.alerting.alertmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.alerting.Alert;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.alerting.alertmanager.TestAlertmanagerAlerts.event;
import static org.lolaf.staffix.api.alerting.AlertEvent.Type.CLEARED;
import static org.lolaf.staffix.api.alerting.AlertEvent.Type.RAISED;

class TestAlertmanagerAlertsPublisher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<JsonNode> posts = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private final HttpServer alertmanager = stubAlertmanager();
    private AlertmanagerAlertsPublisher publisher;

    private HttpServer stubAlertmanager() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/prefix/api/v2/alerts", this::record);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void record(HttpExchange exchange) throws IOException {
        posts.add(MAPPER.readTree(exchange.getRequestBody()));
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private URI alertmanagerUrl() {
        return URI.create("http://localhost:" + alertmanager.getAddress().getPort() + "/prefix/");
    }

    private void start(AlertmanagerAlertsPublisherSettings settings) {
        publisher = new AlertmanagerAlertsPublisher(settings);
        publisher.start();
    }

    @AfterEach
    void stop() {
        if (publisher != null) {
            publisher.stop(Deadline.unlimited());
        }
        alertmanager.stop(0);
    }

    @Test
    void postsARaiseAtOnceThenResendsItUntilCleared() {
        start(AlertmanagerAlertsPublisherSettings.builder()
                .alertmanagerUrl(alertmanagerUrl())
                .resendInterval(Duration.ofMillis(200))
                .build());

        publisher.publish(event(RAISED, Alert.major("down"), Instant.now()));
        await().atMost(Duration.ofSeconds(5)).until(() -> posts.size() >= 3);
        assertThat(posts).allSatisfy(post -> assertThat(post).singleElement()
                .satisfies(alert -> assertThat(alert.get("labels").get("severity").asText()).isEqualTo("major")));

        Instant clearedAt = Instant.now();
        publisher.publish(event(CLEARED, Alert.major("down"), clearedAt));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(posts.get(posts.size() - 1)).singleElement()
                .satisfies(alert -> assertThat(alert.get("endsAt").asText()).isEqualTo(clearedAt.toString())));
    }

    @Test
    void keepsPostingToTheReachableAlertmanagerWhenAnotherIsDown() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        start(AlertmanagerAlertsPublisherSettings.builder()
                .alertmanagerUrl(URI.create("http://localhost:" + closedPort))
                .alertmanagerUrl(alertmanagerUrl())
                .resendInterval(Duration.ofMillis(200))
                .build());

        publisher.publish(event(RAISED, Alert.major("down"), Instant.now()));

        await().atMost(Duration.ofSeconds(5)).until(() -> posts.size() >= 2);
    }

    @Test
    void retriesAtTheNextResendAfterAnAlertmanagerError() {
        status = 500;
        start(AlertmanagerAlertsPublisherSettings.builder()
                .alertmanagerUrl(alertmanagerUrl())
                .resendInterval(Duration.ofMillis(200))
                .build());

        publisher.publish(event(RAISED, Alert.major("down"), Instant.now()));
        await().atMost(Duration.ofSeconds(5)).until(() -> posts.size() >= 2);
        status = 200;
        int failedPosts = posts.size();

        await().atMost(Duration.ofSeconds(5)).until(() -> posts.size() > failedPosts);
        assertThat(posts.get(posts.size() - 1)).hasSize(1);
    }
}
