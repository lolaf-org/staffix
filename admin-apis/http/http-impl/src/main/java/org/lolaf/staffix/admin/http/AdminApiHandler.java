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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.admin.FixAcceptorSessions;
import org.lolaf.staffix.api.admin.FixInitiatorTargets;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Routes one engine's requests, under {@code /engines/{instanceId}/}, to its {@link AdminApi}. Every request
 * must carry the engine's bearer token, or its read-only one for a {@code GET}.
 */
@Slf4j
class AdminApiHandler implements HttpHandler {

    static final String API_VERSION = "v1";
    static final ObjectMapper MAPPER = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final int ENGINE_SEGMENTS = 3;
    private static final Pattern VERSION_SEGMENT = Pattern.compile("v[0-9]+");

    private final AdminApi adminApi;
    private final byte[] fullAuthorization;
    private final byte[] readOnlyAuthorization;
    private final List<Route> routes;
    private SessionsDocument sessionsDocument;

    AdminApiHandler(AdminApi adminApi, String apiToken, String readOnlyApiToken) {
        this.adminApi = adminApi;
        this.fullAuthorization = bearer(apiToken);
        this.readOnlyAuthorization = readOnlyApiToken == null ? null : bearer(readOnlyApiToken);
        this.routes = routes();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            Access access = access(exchange);
            if (access == Access.NONE) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                throw new HttpProblemException(401, "Missing or wrong bearer token");
            }
            route(exchange, pathAfterEngine(exchange), access);
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
            sendProblem(exchange, new HttpProblemException(500, "The engine failed to serve the request; see its log"));
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

    private static byte[] bearer(String token) {
        return ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    private Access access(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null) {
            return Access.NONE;
        }
        byte[] authorization = header.getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(fullAuthorization, authorization)) {
            return Access.FULL;
        }
        return readOnlyAuthorization != null && MessageDigest.isEqual(readOnlyAuthorization, authorization)
                ? Access.READ_ONLY : Access.NONE;
    }

    private static List<String> pathAfterEngine(HttpExchange exchange) {
        String[] segments = exchange.getRequestURI().getRawPath().split("/");
        return Arrays.stream(segments, Math.min(ENGINE_SEGMENTS, segments.length), segments.length)
                .map(segment -> URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8))
                .collect(Collectors.toList());
    }

    private List<Route> routes() {
        return List.of(
                new Route("GET", "", (exchange, path) -> sendJson(exchange,
                        new EngineInfo(adminApi.getInstanceId(), EngineInfo.STAFFIX_VERSION, List.of(API_VERSION)))),
                new Route("GET", "v1/sessions", (exchange, path) -> sendSessions(exchange)),
                new Route("GET", "v1/status", (exchange, path) -> {
                    Map<FixSessionId, FixSession> running = SessionsDocument.running(adminApi);
                    sendJson(exchange, EngineStatus.of(adminApi, running, sessionsDocument(running).version));
                }),
                new Route("GET", "v1/components", (exchange, path) -> sendJson(exchange, ComponentsJson.of(adminApi))),
                new Route("GET", "v1/settings-stores", (exchange, path) -> sendJson(exchange,
                        adminApi.getFixSessionsSettingsStoresInstanceIds().stream().map(SettingsStore::new).collect(Collectors.toList()))),
                new Route("POST", "v1/settings-stores/{storeId}/reload", (exchange, path) -> {
                    log.info("Admin API: reload settings store {}", path.get(2));
                    adminApi.reloadFixSessionsSettingsStore(path.get(2));
                    sendNoContent(exchange);
                }),
                new Route("GET", "v1/dictionaries/{dictionaryId}", (exchange, path) -> sendDictionary(exchange, path.get(2))),
                new Route("GET", "v1/sessions/{group}/{name}/settings", (exchange, path) ->
                        sendJson(exchange, SettingsJson.of(runningSession(path).getFixSessionSettings()))),
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
                    // the message itself is not logged: it may carry a Password(554) or a client's data
                    log.info("Admin API: send a message of {} characters to {}", send.getMessage().length(), fixSessionId.getQualifiedName());
                    adminApi.sendFixMessage(fixSessionId, send.getMessage(), send.getSeparator(), send.isPossDup());
                }),
                new Route("POST", "v1/sessions/{group}/{name}/activate", (exchange, path) -> {
                    ActivateRequest activate = MAPPER.readValue(exchange.getRequestBody(), ActivateRequest.class);
                    FixSessionId target = initiatorConfig(path.get(2), path.get(3), activate.getConfig());
                    log.info("Admin API: activate {}", target.getQualifiedName());
                    adminApi.switchInitiatorSession(target);
                    sendNoContent(exchange);
                }));
    }

    /**
     * The engine refuses a config that stopped running, so an initiator switching configs while the request runs
     * answers 409 rather than the 400 of a request that was wrong.
     */
    private Route sessionOperation(String method, String operation, SessionOperation action) {
        return new Route(method, "v1/sessions/{group}/{name}/" + operation, (exchange, path) -> {
            String group = path.get(2);
            String name = path.get(3);
            FixSessionId running = runningConfig(group, name);
            try {
                action.apply(running, exchange);
            } catch (IllegalArgumentException e) {
                if (!running.equals(runningConfig(group, name))) {
                    throw new HttpProblemException(409, "Session " + name + " of group " + group
                            + " switched to another config while the request ran; retry");
                }
                throw e;
            }
            sendNoContent(exchange);
        });
    }

    private void route(HttpExchange exchange, List<String> path, Access access) throws IOException {
        List<Route> matching = routes.stream().filter(route -> route.matches(path)).collect(Collectors.toList());
        if (matching.isEmpty()) {
            if (!path.isEmpty() && VERSION_SEGMENT.matcher(path.get(0)).matches() && !path.get(0).equals(API_VERSION)) {
                throw new HttpProblemException(404, "API version " + path.get(0) + " is not served, this engine serves " + API_VERSION);
            }
            throw new HttpProblemException(404, "No resource " + exchange.getRequestURI().getPath());
        }
        Route route = matching.stream()
                .filter(candidate -> candidate.method.equals(exchange.getRequestMethod()))
                .findFirst()
                .orElseThrow(() -> methodNotAllowed(exchange, matching));
        if (access == Access.READ_ONLY && !route.method.equals("GET")) {
            throw new HttpProblemException(403, "The read-only token cannot " + route.method + " " + exchange.getRequestURI().getPath());
        }
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

    /**
     * A session is named after its main config, as in {@code sessions}; an initiator may be running a backup.
     */
    private FixSessionId runningConfig(String group, String name) {
        FixInitiatorTargets initiator = initiator(group, name);
        if (initiator != null) {
            return initiator.getActiveFixSessionId();
        }
        FixSessionId acceptorSession = acceptorSession(group, name);
        if (acceptorSession == null) {
            throw noSession(group, name);
        }
        return acceptorSession;
    }

    private FixSession runningSession(List<String> path) {
        FixSessionId running = runningConfig(path.get(2), path.get(3));
        return adminApi.getManagedFixSessions().stream()
                .filter(session -> session.getFixSessionId().equals(running))
                .findFirst()
                .orElseThrow(() -> new HttpProblemException(404, "Session " + path.get(3) + " of group " + path.get(2) + " is not running"));
    }

    private FixSessionId initiatorConfig(String group, String name, String config) {
        FixInitiatorTargets initiator = initiator(group, name);
        if (initiator == null) {
            if (acceptorSession(group, name) == null) {
                throw noSession(group, name);
            }
            throw new HttpProblemException(409, "Session " + name + " of group " + group + " is an acceptor's, it has a single config");
        }
        return initiator.getTargets().stream()
                .map(FixInitiatorTarget::getFixSessionId)
                .filter(fixSessionId -> fixSessionId.getName().equals(config))
                .findFirst()
                .orElseThrow(() -> new HttpProblemException(404, "Session " + name + " of group " + group + " has no config " + config));
    }

    private FixInitiatorTargets initiator(String group, String name) {
        for (FixInitiatorTargets initiator : adminApi.getInitiatorsTargets()) {
            if (isNamed(initiator.getTargets().get(0).getFixSessionId(), group, name)) {
                return initiator;
            }
        }
        return null;
    }

    private FixSessionId acceptorSession(String group, String name) {
        for (FixAcceptorSessions acceptor : adminApi.getAcceptorsSessions()) {
            for (FixSessionId fixSessionId : acceptor.getFixSessionIds()) {
                if (isNamed(fixSessionId, group, name)) {
                    return fixSessionId;
                }
            }
        }
        return null;
    }

    private static boolean isNamed(FixSessionId fixSessionId, String group, String name) {
        return fixSessionId.getGroup().equals(group) && fixSessionId.getName().equals(name);
    }

    private static HttpProblemException noSession(String group, String name) {
        return new HttpProblemException(404, "No session " + name + " in group " + group);
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

    private enum Access {
        NONE,
        READ_ONLY,
        FULL
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
            this.template = template.isEmpty() ? List.of() : List.of(template.split("/"));
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
