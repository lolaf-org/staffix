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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.http.HttpSender;
import org.lolaf.staffix.api.http.HttpSenderSettings;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

/**
 * Posts to every Alertmanager in turn. A failure is logged once per outage of each Alertmanager rather than at every
 * resend.
 */
@Slf4j
final class AlertmanagerClient implements AutoCloseable {

    private static final String ALERTS_PATH = "/api/v2/alerts";

    private final String[] endpoints;
    private final HttpSender[] senders;
    private final boolean[] failing;

    AlertmanagerClient(List<URI> alertmanagerUrls, HttpSenderSettings httpSenderSettings,
                       Function<HttpSenderSettings, HttpSender> httpSenderFactory) {
        this.endpoints = alertmanagerUrls.stream().map(AlertmanagerClient::alertsEndpoint).toArray(String[]::new);
        this.senders = new HttpSender[endpoints.length];
        for (int i = 0; i < endpoints.length; i++) {
            senders[i] = httpSenderFactory.apply(httpSenderSettings.toBuilder()
                    .endpointUrl(endpoints[i])
                    .contentType("application/json")
                    .build());
        }
        this.failing = new boolean[endpoints.length];
    }

    private static String alertsEndpoint(URI baseUrl) {
        String base = baseUrl.toString();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + ALERTS_PATH;
    }

    void post(List<PostableAlert> alerts) throws InterruptedException {
        if (alerts.isEmpty()) {
            return;
        }
        byte[] body = AlertmanagerJson.encode(alerts).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < senders.length; i++) {
            post(i, body);
        }
    }

    private void post(int index, byte[] body) throws InterruptedException {
        try {
            senders[index].send(body, body.length);
            succeeded(index);
        } catch (InterruptedIOException e) {
            throw new InterruptedException(e.getMessage());
        } catch (IOException e) {
            failed(index, e);
        }
    }

    private void succeeded(int index) {
        if (failing[index]) {
            failing[index] = false;
            log.info("Posting alerts to {} works again", endpoints[index]);
        }
    }

    private void failed(int index, IOException cause) {
        if (failing[index]) {
            log.debug("Posting alerts to {} still fails", endpoints[index], cause);
        } else {
            failing[index] = true;
            log.warn("Posting alerts to {} failed, retrying at each resend: {}", endpoints[index], cause.toString());
        }
    }

    @Override
    public void close() {
        for (HttpSender sender : senders) {
            sender.close();
        }
    }
}
