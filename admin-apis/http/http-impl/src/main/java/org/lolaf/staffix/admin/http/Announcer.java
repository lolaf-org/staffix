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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Announces the engine to the staffix admin console, then again on every interval so a restarted console finds
 * it. A failure is logged once until the next success and never affects the engine.
 */
@Slf4j
class Announcer {

    static final String ANNOUNCE_PATH = "/api/engines/announce";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String instanceId;
    private final URI announceUri;
    private final String authorization;
    private final byte[] announcement;
    private final Duration interval;
    private final HttpClient client;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private ScheduledFuture<?> announcing;
    private boolean announced;
    private boolean failing;

    Announcer(HttpAdminApiSettings settings, String instanceId, URI baseUrl, String apiToken) throws IOException {
        this.instanceId = instanceId;
        this.announceUri = URI.create(settings.getAnnounceUrl().replaceAll("/+$", "") + ANNOUNCE_PATH);
        this.authorization = settings.getAnnounceUsername() == null ? null : "Basic " + Base64.getEncoder().encodeToString(
                (settings.getAnnounceUsername() + ":" + settings.getAnnouncePassword()).getBytes(StandardCharsets.UTF_8));
        Map<String, String> body = new LinkedHashMap<>();
        body.put("engineId", instanceId);
        body.put("baseUrl", baseUrl.toString());
        body.put("token", apiToken);
        body.put("staffixVersion", EngineInfo.STAFFIX_VERSION);
        this.announcement = MAPPER.writeValueAsBytes(body);
        this.interval = settings.getAnnounceInterval();
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.ownsScheduler = settings.getAnnounceScheduler() == null;
        this.scheduler = ownsScheduler
                ? Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "staffix-admin-announce-" + instanceId);
                    thread.setDaemon(true);
                    return thread;
                })
                : settings.getAnnounceScheduler();
    }

    synchronized void start() {
        announcing = scheduler.scheduleWithFixedDelay(this::announce, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    synchronized void stop() {
        if (announcing != null) {
            announcing.cancel(true);
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private void announce() {
        HttpRequest.Builder request = HttpRequest.newBuilder(announceUri)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(announcement));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                succeeded();
            } else {
                failed("HTTP " + response.statusCode() + " " + response.body(), null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            failed(e.toString(), null);
        } catch (RuntimeException e) {
            failed(e.toString(), e);
        }
    }

    private void succeeded() {
        if (!announced || failing) {
            log.info("Announced instance {} to {}", instanceId, announceUri);
        }
        announced = true;
        failing = false;
    }

    private void failed(String reason, Exception cause) {
        if (!failing) {
            log.warn("Failed to announce instance {} to {}, retrying every {}: {}", instanceId, announceUri, interval, reason, cause);
        }
        failing = true;
    }
}
