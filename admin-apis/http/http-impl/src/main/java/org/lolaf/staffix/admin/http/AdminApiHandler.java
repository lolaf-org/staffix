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
import java.util.stream.Collectors;

/**
 * Routes one engine's requests, under {@code /engines/{instanceId}/}, to its {@link AdminApi}. Every request
 * must carry the engine's bearer token.
 */
@Slf4j
class AdminApiHandler implements HttpHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ENGINE_SEGMENTS = 3;

    private final AdminApi adminApi;
    private final byte[] expectedAuthorization;

    AdminApiHandler(AdminApi adminApi, String apiToken) {
        this.adminApi = adminApi;
        this.expectedAuthorization = ("Bearer " + apiToken).getBytes(StandardCharsets.UTF_8);
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

    private void route(HttpExchange exchange, List<String> path) throws IOException {
        if (path.equals(List.of("status"))) {
            requireMethod(exchange, "GET");
            sendJson(exchange, EngineStatus.of(adminApi));
        } else if (path.equals(List.of("settings-stores"))) {
            requireMethod(exchange, "GET");
            sendJson(exchange, adminApi.getFixSessionsSettingsStoresInstanceIds());
        } else if (path.size() == 3 && path.get(0).equals("settings-stores") && path.get(2).equals("reload")) {
            requireMethod(exchange, "POST");
            log.info("Admin API: reload settings store {}", path.get(1));
            adminApi.reloadFixSessionsSettingsStore(path.get(1));
            sendNoContent(exchange);
        } else if (path.size() == 2 && path.get(0).equals("dictionaries")) {
            requireMethod(exchange, "GET");
            sendDictionary(exchange, path.get(1));
        } else if (path.size() == 4 && path.get(0).equals("sessions")) {
            sessionOperation(exchange, path.get(1), path.get(2), path.get(3));
        } else {
            throw new HttpProblemException(404, "No resource " + exchange.getRequestURI().getPath());
        }
    }

    private void sessionOperation(HttpExchange exchange, String group, String name, String operation) throws IOException {
        if (operation.equals("activate")) {
            requireMethod(exchange, "POST");
            FixSessionId target = initiatorTarget(group, name);
            log.info("Admin API: activate {}", target.getQualifiedName());
            adminApi.switchInitiatorSession(target);
            sendNoContent(exchange);
            return;
        }
        FixSession session = managedSession(group, name);
        FixSessionId fixSessionId = session.getFixSessionId();
        switch (operation) {
            case "settings":
                requireMethod(exchange, "GET");
                sendJson(exchange, SettingsJson.of(session.getFixSessionSettings()));
                return;
            case "logon":
                requireMethod(exchange, "POST");
                log.info("Admin API: logon {}", fixSessionId.getQualifiedName());
                adminApi.logonSession(fixSessionId);
                break;
            case "logout":
                requireMethod(exchange, "POST");
                log.info("Admin API: logout {}", fixSessionId.getQualifiedName());
                adminApi.logoutSession(fixSessionId);
                break;
            case "reset":
                requireMethod(exchange, "POST");
                ResetRequest reset = MAPPER.readValue(exchange.getRequestBody(), ResetRequest.class);
                log.info("Admin API: reset {} with {}", fixSessionId.getQualifiedName(), reset.getMode());
                adminApi.resetSession(fixSessionId, reset.getMode());
                break;
            case "seqnums":
                requireMethod(exchange, "PUT");
                setSeqNums(fixSessionId, MAPPER.readValue(exchange.getRequestBody(), SeqNumsRequest.class));
                break;
            case "messages":
                requireMethod(exchange, "POST");
                SendMessageRequest send = MAPPER.readValue(exchange.getRequestBody(), SendMessageRequest.class);
                log.info("Admin API: send to {}: {}", fixSessionId.getQualifiedName(), send.getMessage());
                adminApi.sendFixMessage(fixSessionId, send.getMessage(), send.getSeparator(), send.isPossDup());
                break;
            default:
                throw new HttpProblemException(404, "No resource " + exchange.getRequestURI().getPath());
        }
        sendNoContent(exchange);
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

    private FixSession managedSession(String group, String name) {
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

    private static void requireMethod(HttpExchange exchange, String method) {
        if (!exchange.getRequestMethod().equals(method)) {
            exchange.getResponseHeaders().set("Allow", method);
            throw new HttpProblemException(405, exchange.getRequestMethod() + " is not allowed here, use " + method);
        }
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

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
