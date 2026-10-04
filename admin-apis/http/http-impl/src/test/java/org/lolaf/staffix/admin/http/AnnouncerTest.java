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
package org.lolaf.staffix.admin.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.admin.AdminApi;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnnouncerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<JsonNode> announcements = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private HttpServer console;
    private volatile int consoleStatus = 204;
    private HttpAdminApi exporter;

    @BeforeEach
    void startConsole() throws Exception {
        startConsole(HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0));
    }

    private void startConsole(HttpServer server) {
        console = server;
        console.createContext(Announcer.ANNOUNCE_PATH, exchange -> {
            announcements.add(MAPPER.readTree(exchange.getRequestBody()));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(consoleStatus, -1);
            exchange.close();
        });
        console.start();
    }

    @AfterEach
    void stop() {
        if (exporter != null) {
            exporter.shutdown(Deadline.of(Duration.ofSeconds(1)));
        }
        console.stop(0);
    }

    private void export(String instanceId) {
        export(instanceId, null);
    }

    private void export(String instanceId, ScheduledExecutorService scheduler) {
        export(announcement().scheduler(scheduler), instanceId);
    }

    private HttpAdminApiSettings.AnnouncementSettings.AnnouncementSettingsBuilder announcement() {
        return HttpAdminApiSettings.AnnouncementSettings.builder()
                .url("http://127.0.0.1:" + console.getAddress().getPort() + "/")
                .username("engine")
                .password("secret")
                .interval(Duration.ofMillis(50));
    }

    private void export(HttpAdminApiSettings.AnnouncementSettings.AnnouncementSettingsBuilder announcement, String instanceId) {
        AdminApi adminApi = mock(AdminApi.class);
        when(adminApi.getInstanceId()).thenReturn(instanceId);
        exporter = new HttpAdminApi(HttpAdminApiSettings.builder()
                .bindAddress("127.0.0.1")
                .port(0)
                .apiToken("alpha-token")
                .announcement(announcement.build())
                .build());
        exporter.export(adminApi);
    }

    @Test
    void announcesWhereAndHowToReachTheEngineOnEveryInterval() throws Exception {
        export("alpha engine");

        await().atMost(Duration.ofSeconds(5)).until(() -> announcements.size() >= 2);
        JsonNode announcement = announcements.get(0);
        String baseUrl = "http://127.0.0.1:" + exporter.getAddress().getPort() + "/engines/alpha%20engine";
        assertThat(announcement.get("engineId").asText()).isEqualTo("alpha engine");
        assertThat(announcement.get("baseUrl").asText()).isEqualTo(baseUrl);
        assertThat(announcement.get("token").asText()).isEqualTo("alpha-token");
        assertThat(authorizations.get(0)).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString("engine:secret".getBytes(StandardCharsets.UTF_8)));

        HttpResponse<Void> status = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/status"))
                .header("Authorization", "Bearer " + announcement.get("token").asText())
                .build(), HttpResponse.BodyHandlers.discarding());
        assertThat(status.statusCode()).isEqualTo(200);
    }

    @Test
    void stopsAnnouncingOnShutdown() {
        export("alpha-engine");
        await().atMost(Duration.ofSeconds(5)).until(() -> !announcements.isEmpty());

        exporter.shutdown(Deadline.of(Duration.ofSeconds(1)));
        int announced = announcements.size();

        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).until(() -> announcements.size() == announced);
    }

    @Test
    void aGivenSchedulerRunsTheAnnouncementsAndOutlivesTheEngine() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            export("alpha-engine", scheduler);
            await().atMost(Duration.ofSeconds(5)).until(() -> !announcements.isEmpty());

            exporter.shutdown(Deadline.of(Duration.ofSeconds(1)));
            int announced = announcements.size();

            assertThat(scheduler.isShutdown()).isFalse();
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).until(() -> announcements.size() == announced);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void aRefusingConsoleLeavesTheApiServedAndIsRetried() {
        consoleStatus = 401;
        export("alpha-engine");

        await().atMost(Duration.ofSeconds(5)).until(() -> announcements.size() >= 3);
        assertThat(exporter.getAddress()).isNotNull();
    }

    @Test
    void announcesToAnHttpsConsoleItIsToldToTrust() throws Exception {
        console.stop(0);
        HttpsServer httpsConsole = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        httpsConsole.setHttpsConfigurator(new HttpsConfigurator(TestTls.server()));
        startConsole(httpsConsole);
        String announceUrl = "https://127.0.0.1:" + console.getAddress().getPort();

        export(announcement().url(announceUrl).sslContext(TestTls.trusting()), "alpha-engine");

        await().atMost(Duration.ofSeconds(5)).until(() -> !announcements.isEmpty());
    }

    @Test
    void anHttpsConsoleNotTrustedIsNotAnnouncedTo() throws Exception {
        console.stop(0);
        HttpsServer httpsConsole = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        httpsConsole.setHttpsConfigurator(new HttpsConfigurator(TestTls.server()));
        startConsole(httpsConsole);

        export(announcement().url("https://127.0.0.1:" + console.getAddress().getPort()), "alpha-engine");

        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).until(announcements::isEmpty);
    }
}
