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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.admin.AdminApiExporter;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Serves the engine's admin API over HTTP under {@code /engines/{instanceId}/}, for the staffix admin console.
 */
@Slf4j
public class HttpAdminApi implements AdminApiExporter {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpAdminApiSettings settings;
    private HttpAdminServer server;
    private String mountedInstanceId;
    private String apiToken;
    private Announcer announcer;

    HttpAdminApi(HttpAdminApiSettings settings) {
        this.settings = settings;
    }

    @Override
    public synchronized void export(AdminApi adminApi) {
        String instanceId = adminApi.getInstanceId();
        InetSocketAddress address = new InetSocketAddress(settings.getBindAddress(), settings.getPort());
        apiToken = settings.getApiToken() != null ? settings.getApiToken() : randomToken();
        try {
            server = HttpAdminServer.acquire(address, settings.getSslContext());
            server.mount(instanceId, new AdminApiHandler(adminApi, apiToken, settings.getReadOnlyApiToken()));
            mountedInstanceId = instanceId;
            log.info("Serving the admin API of instance {} on {}{}", instanceId, server.getAddress(), HttpAdminServer.basePath(instanceId));
        } catch (Exception e) {
            log.error("Failed to serve the admin API of instance {} on {}", instanceId, address, e);
            releaseServer();
            return;
        }
        if (settings.getAnnouncement() != null) {
            startAnnouncing(instanceId);
        }
    }

    private void startAnnouncing(String instanceId) {
        try {
            announcer = new Announcer(settings.getAnnouncement(), instanceId, baseUrl(instanceId), apiToken);
            announcer.start();
        } catch (Exception e) {
            log.error("Failed to announce instance {} to {}; its admin API is served but the console will not know it",
                    instanceId, settings.getAnnouncement().getUrl(), e);
        }
    }

    /**
     * Where the console reaches this engine: the advertised URL, else the bind address or, when bound to every
     * interface, the host name.
     */
    URI baseUrl(String instanceId) throws UnknownHostException {
        String root = settings.getAdvertisedUrl();
        if (root == null) {
            InetAddress bound = server.getAddress().getAddress();
            String host = bound.isAnyLocalAddress() ? InetAddress.getLocalHost().getCanonicalHostName() : bound.getHostAddress();
            if (host.contains(":")) {
                host = "[" + host + "]";
            }
            root = (settings.getSslContext() != null ? "https" : "http") + "://" + host + ":" + server.getAddress().getPort();
        }
        String encodedId = URLEncoder.encode(instanceId, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create(root.replaceAll("/+$", "") + "/engines/" + encodedId);
    }

    private static String randomToken() {
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    /**
     * The address the server is bound to, or null when it is not serving.
     */
    public synchronized InetSocketAddress getAddress() {
        return mountedInstanceId != null ? server.getAddress() : null;
    }

    synchronized String getApiToken() {
        return apiToken;
    }

    @Override
    public synchronized void shutdown(Deadline deadline) {
        releaseServer();
    }

    private void releaseServer() {
        if (announcer != null) {
            announcer.stop();
            announcer = null;
        }
        if (server != null) {
            server.release(mountedInstanceId);
            server = null;
            mountedInstanceId = null;
        }
    }

    public static class HttpAdminApiFactoryImpl implements AdminApiExporter.AdminApiExporterFactory<HttpAdminApiSettings> {

        @Override
        public Class<HttpAdminApiSettings> getSettingsClass() {
            return HttpAdminApiSettings.class;
        }

        @Override
        public AdminApiExporter newInstance(HttpAdminApiSettings settings) {
            return new HttpAdminApi(settings);
        }
    }
}
