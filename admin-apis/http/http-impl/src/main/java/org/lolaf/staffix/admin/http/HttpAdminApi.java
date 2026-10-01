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

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.admin.AdminApiExporter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serves the engine's admin API over HTTP, for the staffix admin console.
 */
@Slf4j
public class HttpAdminApi implements AdminApiExporter {

    private final HttpAdminApiSettings settings;
    private HttpServer server;
    private ExecutorService executor;

    HttpAdminApi(HttpAdminApiSettings settings) {
        this.settings = settings;
    }

    @Override
    public synchronized void export(AdminApi adminApi) {
        String instanceId = adminApi.getInstanceId();
        InetSocketAddress address = new InetSocketAddress(settings.getBindAddress(), settings.getPort());
        try {
            server = createServer(address);
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "staffix-admin-http-" + instanceId);
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.start();
            log.info("Serving the admin API of instance {} on {}", instanceId, server.getAddress());
        } catch (IOException e) {
            log.error("Failed to serve the admin API of instance {} on {}", instanceId, address, e);
            server = null;
        }
    }

    private HttpServer createServer(InetSocketAddress address) throws IOException {
        if (settings.getSslContext() == null) {
            return HttpServer.create(address, 0);
        }
        HttpsServer httpsServer = HttpsServer.create(address, 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(settings.getSslContext()));
        return httpsServer;
    }

    /**
     * The address the server is bound to, or null when it is not serving.
     */
    public synchronized InetSocketAddress getAddress() {
        return server != null ? server.getAddress() : null;
    }

    @Override
    public synchronized void shutdown(Deadline deadline) {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
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
