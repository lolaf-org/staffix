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
import com.sun.net.httpserver.*;
import org.lolaf.staffix.admin.http.routes.Route;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One listening server per address, shared by the engines of a JVM that export to the same port, each under
 * {@code /engines/{instanceId}/}.
 */
class HttpAdminServer {

    /**
     * Served without a token: the document holds no secret, and tools fetch it before they have one.
     */
    static final String OPENAPI_PATH = "/openapi.yaml";
    private static final byte[] OPENAPI = readOpenApi();
    private static final Map<InetSocketAddress, HttpAdminServer> SHARED = new HashMap<>();
    /**
     * The JDK server writes a response's headers and body apart, so Nagle against a client's delayed ACK holds the
     * body about 40 ms. The JDK reads it once, at the JVM's first server: if the application starts one before the
     * engine, the delay stays unless the property is set on the command line.
     */
    private static final String NO_DELAY = "sun.net.httpserver.nodelay";

    static {
        if (System.getProperty(NO_DELAY) == null) {
            System.setProperty(NO_DELAY, "true");
        }
    }

    private final InetSocketAddress requestedAddress;
    private final SSLContext sslContext;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ObjectMapper mapper;
    private int engines;

    private HttpAdminServer(InetSocketAddress requestedAddress, SSLContext sslContext) throws IOException {
        this.requestedAddress = requestedAddress;
        this.sslContext = sslContext;
        this.server = sslContext == null ? HttpServer.create(requestedAddress, 0) : httpsServer(requestedAddress, sslContext);
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "staffix-admin-http-" + requestedAddress.getPort());
            thread.setDaemon(true);
            return thread;
        });
        this.mapper = AdminApiHandler.newObjectMapper();
        server.setExecutor(executor);
        server.createContext("/", this::serveRoot);
        server.start();
    }

    private static byte[] readOpenApi() {
        try (InputStream in = HttpAdminServer.class.getResourceAsStream("openapi.yaml")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static HttpsServer httpsServer(InetSocketAddress address, SSLContext sslContext) throws IOException {
        HttpsServer httpsServer = HttpsServer.create(address, 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        return httpsServer;
    }

    /**
     * Port 0 always gets a server of its own, as two engines asking for a free port asked for two.
     */
    static synchronized HttpAdminServer acquire(InetSocketAddress address, SSLContext sslContext) throws IOException {
        HttpAdminServer shared = address.getPort() == 0 ? null : SHARED.get(address);
        if (shared == null) {
            shared = new HttpAdminServer(address, sslContext);
            if (address.getPort() != 0) {
                SHARED.put(address, shared);
            }
        } else if (!Objects.equals(shared.sslContext, sslContext)) {
            throw new IllegalStateException("Another engine already serves " + address + " with different TLS settings");
        }
        shared.engines++;
        return shared;
    }

    static String basePath(String instanceId) {
        return "/engines/" + instanceId + "/";
    }

    private void serveRoot(HttpExchange exchange) throws IOException {
        try {
            if (exchange.getRequestURI().getPath().equals(OPENAPI_PATH) && exchange.getRequestMethod().equals("GET")) {
                Route.send(exchange, 200, "application/yaml", OPENAPI);
            } else {
                AdminApiHandler.sendProblem(exchange, mapper,
                        new HttpProblemException(404, "No engine serves " + exchange.getRequestURI().getPath()));
            }
        } finally {
            exchange.close();
        }
    }

    /**
     * @throws IllegalArgumentException if another engine with the same instance id is mounted
     */
    void mount(String instanceId, HttpHandler handler) {
        synchronized (HttpAdminServer.class) {
            server.createContext(basePath(instanceId), handler);
        }
    }

    InetSocketAddress getAddress() {
        return server.getAddress();
    }

    /**
     * @param mountedInstanceId the engine to unmount, or null when it failed to mount
     */
    void release(String mountedInstanceId) {
        synchronized (HttpAdminServer.class) {
            if (mountedInstanceId != null) {
                server.removeContext(basePath(mountedInstanceId));
            }
            if (--engines == 0) {
                SHARED.remove(requestedAddress, this);
                server.stop(0);
                executor.shutdownNow();
            }
        }
    }
}
