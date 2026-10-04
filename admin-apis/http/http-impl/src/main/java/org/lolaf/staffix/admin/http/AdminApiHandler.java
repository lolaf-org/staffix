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

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Routes one engine's requests, under {@code /engines/{instanceId}/}, to its {@link AdminApi}. Every request
 * must carry the engine's bearer token.
 */
@Slf4j
class AdminApiHandler implements HttpHandler {

    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ENGINE_SEGMENTS = 3;

    private final AdminApi adminApi;
    private final byte[] expectedAuthorization;
    private final List<Route> routes;
    private SessionsDocument sessionsDocument;

    AdminApiHandler(AdminApi adminApi, String apiToken) {
        this.adminApi = adminApi;
        this.expectedAuthorization = ("Bearer " + apiToken).getBytes(StandardCharsets.UTF_8);
        this.routes = routes();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!isAuthorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                throw new HttpProblemException(401, "Missing or wrong bearer token");
            }
            route(exchange, pathAfterEngine(exchange));
        } catch (HttpProblemException e) {
            sendProblem(exchange, e);
        } catch (JacksonException e) {
            sendProblem(exchange, new HttpProblemException(400, "Invalid request body: " + e.getOriginalMessage()));
        } catch (IllegalArgumentException e) {
            sendProblem(exchange, new HttpProblemException(400, e.getMessage()));
        } catch (IllegalStateException e) {
            sendProblem(exchange, new HttpProblemException(409, e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Admin API request {} {} failed", exchange.getRequestMethod(), exchange.getRequestURI(), e);
            sendProblem(exchange, new HttpProblemException(500, e.toString()));
        } finally {
            exchange.close();
        }
    }

    /**
     * The routes as {@code METHOD /template}, in the OpenAPI document's path syntax.
     */
    List<String> routeTemplates() {
        return routes.stream().map(Route::toString).collect(Collectors.toList());
    }

    private boolean isAuthorized(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        return authorization != null
                && MessageDigest.isEqual(expectedAuthorization, authorization.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> pathAfterEngine(HttpExchange exchange) {
        String[] segments = exchange.getRequestURI().getRawPath().split("/");
        return Arrays.stream(segments, Math.min(ENGINE_SEGMENTS, segments.length), segments.length)
                .map(segment -> URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8))
                .collect(Collectors.toList());
    }

    private List<Route> routes() {
        return List.of(
                new Route("GET", "sessions", (exchange, path) -> sendSessions(exchange)),
                new Route("GET", "status", (exchange, path) -> {
                    Map<FixSessionId, FixSession> running = SessionsDocument.running(adminApi);
                    sendJson(exchange, EngineStatus.of(adminApi, running, sessionsDocument(running).version));
                }),
                new Route("GET", "emitters", (exchange, path) -> sendJson(exchange, EmittersJson.of(adminApi))),
                new Route("GET", "settings-stores",
                        (exchange, path) -> sendJson(exchange, adminApi.getFixSessionsSettingsStoresInstanceIds())),
                new Route("POST", "settings-stores/{storeId}/reload", (exchange, path) -> {
                    log.info("Admin API: reload settings store {}", path.get(1));
                    adminApi.reloadFixSessionsSettingsStore(path.get(1));
                    sendNoContent(exchange);
                }),
                new Route("GET", "dictionaries/{dictionaryId}", (exchange, path) -> sendDictionary(exchange, path.get(1))),
                new Route("GET", "sessions/{group}/{name}/settings", (exchange, path) ->
                        sendJson(exchange, SettingsJson.of(managedSession(path).getFixSessionSettings()))),
                sessionOperation("POST", "logon", (fixSessionId, exchange) -> {
                    log.info("Admin API: logon {}", fixSessionId.getQualifiedName());
                    adminApi.logonSession(fixSessionId);
                }),
                sessionOperation("POST", "logout", (fixSessionId, exchange) -> {
                    log.info("Admin API: logout {}", fixSessionId.getQualifiedName());
                    adminApi.logoutSession(fixSessionId);
                }),
                sessionOperation("POST", "reset", (fixSessionId, exchange) -> {
                    ResetRequest reset = MAPPER.readValue(exchange.getRequestBody(), ResetRequest.class);
                    log.info("Admin API: reset {} with {}", fixSessionId.getQualifiedName(), reset.getMode());
                    adminApi.resetSession(fixSessionId, reset.getMode());
                }),
                sessionOperation("PUT", "seqnums", (fixSessionId, exchange) ->
                        setSeqNums(fixSessionId, MAPPER.readValue(exchange.getRequestBody(), SeqNumsRequest.class))),
                sessionOperation("POST", "messages", (fixSessionId, exchange) -> {
                    SendMessageRequest send = MAPPER.readValue(exchange.getRequestBody(), SendMessageRequest.class);
                    log.info("Admin API: send to {}: {}", fixSessionId.getQualifiedName(), send.getMessage());
                    adminApi.sendFixMessage(fixSessionId, send.getMessage(), send.getSeparator(), send.isPossDup());
                }),
                new Route("POST", "sessions/{group}/{name}/activate", (exchange, path) -> {
                    FixSessionId target = initiatorTarget(path.get(1), path.get(2));
                    log.info("Admin API: activate {}", target.getQualifiedName());
                    adminApi.switchInitiatorSession(target);
                    sendNoContent(exchange);
                }));
    }

    private Route sessionOperation(String method, String operation, SessionOperation action) {
        return new Route(method, "sessions/{group}/{name}/" + operation, (exchange, path) -> {
            action.apply(managedSession(path).getFixSessionId(), exchange);
            sendNoContent(exchange);
        });
    }

    private void route(HttpExchange exchange, List<String> path) throws IOException {
        List<Route> matching = routes.stream().filter(route -> route.matches(path)).collect(Collectors.toList());
        if (matching.isEmpty()) {
            throw new HttpProblemException(404, "No resource " + exchange.getRequestURI().getPath());
        }
        Route route = matching.stream()
                .filter(candidate -> candidate.method.equals(exchange.getRequestMethod()))
                .findFirst()
                .orElseThrow(() -> methodNotAllowed(exchange, matching));
        route.action.handle(exchange, path);
    }

    private static HttpProblemException methodNotAllowed(HttpExchange exchange, List<Route> matching) {
        String allowed = matching.stream().map(route -> route.method).collect(Collectors.joining(", "));
        exchange.getResponseHeaders().set("Allow", allowed);
        return new HttpProblemException(405, exchange.getRequestMethod() + " is not allowed here, use " + allowed);
    }

    private void sendSessions(HttpExchange exchange) throws IOException {
        SessionsDocument document = sessionsDocument(SessionsDocument.running(adminApi));
        String etag = "\"" + document.version + "\"";
        exchange.getResponseHeaders().set("ETag", etag);
        if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        send(exchange, 200, "application/json", document.json);
    }

    private synchronized SessionsDocument sessionsDocument(Map<FixSessionId, FixSession> running) throws IOException {
        if (sessionsDocument == null || !sessionsDocument.describes(running)) {
            sessionsDocument = SessionsDocument.of(adminApi, running);
        }
        return sessionsDocument;
    }

    private static void sendDictionary(HttpExchange exchange, String id) throws IOException {
        Dictionaries.Dictionary dictionary = Dictionaries.get(id)
                .orElseThrow(() -> new HttpProblemException(404, "No dictionary " + id));
        String etag = "\"" + dictionary.hash + "\"";
        exchange.getResponseHeaders().set("ETag", etag);
        if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        send(exchange, 200, "application/xml", dictionary.xml);
    }

    private void setSeqNums(FixSessionId fixSessionId, SeqNumsRequest seqNums) {
        log.info("Admin API: set seqnums of {} to incoming {}, outgoing {}",
                fixSessionId.getQualifiedName(), seqNums.getIncoming(), seqNums.getOutgoing());
        if (seqNums.getIncoming() != null) {
            adminApi.setIncomingSeqNum(fixSessionId, seqNums.getIncoming());
        }
        if (seqNums.getOutgoing() != null) {
            adminApi.setOutgoingSeqNum(fixSessionId, seqNums.getOutgoing());
        }
    }

    private FixSession managedSession(List<String> path) {
        String group = path.get(1);
        String name = path.get(2);
        return adminApi.getManagedFixSessions().stream()
                .filter(session -> session.getFixSessionId().getGroup().equals(group)
                        && session.getFixSessionId().getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new HttpProblemException(404, "No running session " + name + " in group " + group));
    }

    private FixSessionId initiatorTarget(String group, String name) {
        return adminApi.getInitiatorsTargets().stream()
                .flatMap(initiator -> initiator.getTargets().stream())
                .map(FixInitiatorTarget::getFixSessionId)
                .filter(fixSessionId -> fixSessionId.getGroup().equals(group) && fixSessionId.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new HttpProblemException(404, "No initiator config " + name + " in group " + group));
    }

    private static void sendJson(HttpExchange exchange, Object body) throws IOException {
        send(exchange, 200, "application/json", MAPPER.writeValueAsBytes(body));
    }

    private static void sendNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
    }

    static void sendProblem(HttpExchange exchange, HttpProblemException problem) throws IOException {
        send(exchange, problem.getStatus(), "application/problem+json",
                MAPPER.writeValueAsBytes(new Problem(problem.getStatus(), problem.getMessage())));
    }

    static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private interface RouteAction {
        void handle(HttpExchange exchange, List<String> path) throws IOException;
    }

    private interface SessionOperation {
        void apply(FixSessionId fixSessionId, HttpExchange exchange) throws IOException;
    }

    /**
     * A template segment in braces matches any value.
     */
    private static final class Route {
        private final String method;
        private final List<String> template;
        private final RouteAction action;

        Route(String method, String template, RouteAction action) {
            this.method = method;
            this.template = List.of(template.split("/"));
            this.action = action;
        }

        boolean matches(List<String> path) {
            if (path.size() != template.size()) {
                return false;
            }
            for (int i = 0; i < path.size(); i++) {
                if (!template.get(i).startsWith("{") && !template.get(i).equals(path.get(i))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return method + " /" + String.join("/", template);
        }
    }
}
