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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A one-endpoint HTTP server on a free port, recording what each sender under test actually put on the
 * wire. Uses the JDK's own {@code com.sun.net.httpserver}, so the tests need no server dependency.
 *
 * <p>It answers in one of two ways. By default every request gets the same response, set with
 * {@link #respondWith(int, String)} - which is what a test asserting one exchange wants. A test about
 * retrying wants the answer to change between attempts instead, and that is
 * {@link #respondWithSequence(int...)}: the given statuses are handed out one per request, and the
 * standing response resumes once they run out.
 *
 * <p>Note what this server cannot do, because it decides what can be tested here: it speaks HTTP/1.1
 * only and cannot send trailers, so it cannot stand in for a gRPC endpoint. That needs a server with
 * HTTP/2, which {@code com.sun.net.httpserver} is not.
 */
public class StubHttpServer implements AutoCloseable {

    private static final String DEFAULT_PATH = "/v1/metrics";

    private static final byte[] NO_BODY = new byte[0];

    private final HttpServer server;
    private final String path;
    private final List<RecordedRequest> received = new CopyOnWriteArrayList<>();
    private final Set<Integer> clientPorts = ConcurrentHashMap.newKeySet();
    private final Queue<Integer> scriptedStatuses = new ConcurrentLinkedQueue<>();

    private volatile int statusCode = 200;
    private volatile byte[] responseBody = NO_BODY;
    private volatile long responseDelayMillis;

    /**
     * A server on the metrics path, which is what most of these tests publish to.
     *
     * @throws IOException if no port could be bound
     */
    public StubHttpServer() throws IOException {
        this(DEFAULT_PATH);
    }

    /**
     * @param path the single path this server answers on, which {@link #url()} then points at
     * @throws IOException if no port could be bound
     */
    public StubHttpServer(String path) throws IOException {
        this.path = path;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext(path, this::handle);
        this.server.start();
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /**
     * The ephemeral ports the requests arrived from. One port across several requests means the client
     * reused a single pooled connection.
     */
    public Set<Integer> clientPorts() {
        return clientPorts;
    }

    public List<RecordedRequest> received() {
        return received;
    }

    public RecordedRequest lastReceived() {
        return received.get(received.size() - 1);
    }

    /**
     * Sets the standing response, used for every request that the scripted sequence does not answer.
     *
     * @param statusCode the status to answer with
     * @param body       the response body; empty for no body at all
     */
    public void respondWith(int statusCode, String body) {
        this.statusCode = statusCode;
        this.responseBody = body.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Queues statuses to answer the next requests with, one each, ahead of the standing response.
     *
     * <p>For a test about how a sender reacts to an answer changing between attempts - a 503 then a
     * 200, say. Scripted responses carry no body; once the queue drains, the standing response set by
     * {@link #respondWith(int, String)} takes over again.
     *
     * @param statusCodes the statuses to hand out, in order
     */
    public void respondWithSequence(int... statusCodes) {
        for (int statusCode : statusCodes) {
            scriptedStatuses.add(statusCode);
        }
    }

    /**
     * @return how many queued statuses are still unused, which is how a test asserts that a sender made
     * the number of attempts it was expected to
     */
    public int remainingScriptedResponses() {
        return scriptedStatuses.size();
    }

    public void respondAfter(long delayMillis) {
        this.responseDelayMillis = delayMillis;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = readFully(exchange.getRequestBody());
        clientPorts.add(exchange.getRemoteAddress().getPort());
        received.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestHeaders(), body));
        if (responseDelayMillis > 0) {
            try {
                Thread.sleep(responseDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Integer scripted = scriptedStatuses.poll();
        int status = scripted == null ? statusCode : scripted;
        byte[] response = scripted == null ? responseBody : NO_BODY;
        // -1 is this server's way of saying there is no response body at all; a length would announce one.
        exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
        if (response.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
        exchange.close();
    }

    public static class RecordedRequest {

        public final String method;
        public final Map<String, List<String>> headers;
        public final byte[] body;

        RecordedRequest(String method, Map<String, List<String>> headers, byte[] body) {
            this.method = method;
            this.headers = headers;
            this.body = body;
        }

        public String header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }
}
