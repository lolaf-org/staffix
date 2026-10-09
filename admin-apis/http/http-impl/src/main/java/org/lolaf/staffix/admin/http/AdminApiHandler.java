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
import org.lolaf.staffix.admin.http.dto.Problem;
import org.lolaf.staffix.admin.http.routes.*;
import org.lolaf.staffix.api.admin.AdminApi;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Routes one engine's requests, under {@code /engines/{instanceId}/}, to its {@link AdminApi}. Every request
 * must carry the engine's bearer token, or its read-only one for a {@code GET}.
 */
@Slf4j
public class AdminApiHandler implements HttpHandler {

    public static final String API_VERSION = "v1";
    private static final int ENGINE_SEGMENTS = 3;
    private static final Pattern VERSION_SEGMENT = Pattern.compile("v[0-9]+");

    private final byte[] fullAuthorization;
    private final byte[] readOnlyAuthorization;
    private final ObjectMapper mapper;
    private final Route[] routes;

    AdminApiHandler(AdminApi adminApi, String apiToken, String readOnlyApiToken) {
        this.fullAuthorization = bearer(apiToken);
        this.readOnlyAuthorization = readOnlyApiToken == null ? null : bearer(readOnlyApiToken);
        this.mapper = newObjectMapper();
        this.routes = routes(adminApi, mapper);
    }

    static ObjectMapper newObjectMapper() {
        return new ObjectMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
    }

    static void sendProblem(HttpExchange exchange, ObjectMapper mapper, HttpProblemException problem) throws IOException {
        Route.send(exchange, problem.getStatus(), "application/problem+json",
                mapper.writeValueAsBytes(new Problem(problem.getStatus(), problem.getMessage())));
    }

    private static Route[] routes(AdminApi adminApi, ObjectMapper mapper) {
        SessionLookup sessions = new SessionLookup(adminApi);
        SessionsDocumentCache documents = new SessionsDocumentCache(adminApi, mapper);
        JsonResponseBuffer json = new JsonResponseBuffer(mapper);
        return new Route[]{
                new EngineInfoRoute(adminApi, json),
                new SessionsRoute(documents),
                new StatusRoute(adminApi, documents, json),
                new ComponentsRoute(adminApi, json),
                new SessionComponentsRoute(adminApi, json),
                new MetersRoute(adminApi, json),
                new SessionMetersRoute(adminApi, sessions, json),
                new SessionSettingsSchemaRoute(),
                new SessionSettingsStoresRoute(adminApi, json),
                new AddSessionRoute(adminApi),
                new ReloadSessionSettingsStoreRoute(adminApi),
                new DictionaryRoute(),
                new SessionSettingsRoute(sessions),
                new UpdateSessionSettingsRoute(adminApi, sessions),
                new RemoveSessionRoute(adminApi, sessions),
                new ApplicationSettingsRoute(adminApi, sessions, json),
                new LogonRoute(adminApi, sessions),
                new LogoutRoute(adminApi, sessions),
                new ResetRoute(adminApi, sessions, mapper),
                new SeqNumsRoute(adminApi, sessions, mapper),
                new SendMessagesRoute(adminApi, sessions, mapper),
                new ActivateRoute(adminApi, sessions, mapper)
        };
    }

    private static byte[] bearer(String token) {
        return ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> pathAfterEngine(HttpExchange exchange) {
        String[] segments = exchange.getRequestURI().getRawPath().split("/");
        return Arrays.stream(segments, Math.min(ENGINE_SEGMENTS, segments.length), segments.length)
                .map(segment -> URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8))
                .collect(Collectors.toList());
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
            sendProblem(exchange, mapper, e);
        } catch (JacksonException e) {
            sendProblem(exchange, mapper, new HttpProblemException(400, "Invalid request body: " + e.getOriginalMessage()));
        } catch (IllegalArgumentException e) {
            sendProblem(exchange, mapper, new HttpProblemException(400, e.getMessage()));
        } catch (IllegalStateException e) {
            sendProblem(exchange, mapper, new HttpProblemException(409, e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Admin API request {} {} failed", exchange.getRequestMethod(), exchange.getRequestURI(), e);
            sendProblem(exchange, mapper, new HttpProblemException(500, "The engine failed to serve the request; see its log"));
        } finally {
            exchange.close();
        }
    }

    /**
     * The routes as {@code METHOD /template}, in the OpenAPI document's path syntax.
     */
    List<String> routeTemplates() {
        return Arrays.stream(routes).map(Route::toString).collect(Collectors.toList());
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

    private void route(HttpExchange exchange, List<String> path, Access access) throws IOException {
        Route route = null;
        boolean pathMatched = false;
        for (Route candidate : routes) {
            if (candidate.matches(path)) {
                pathMatched = true;
                if (candidate.getMethod().equals(exchange.getRequestMethod())) {
                    route = candidate;
                    break;
                }
            }
        }
        if (route == null) {
            if (pathMatched) {
                throw methodNotAllowed(exchange, path);
            }
            if (!path.isEmpty() && VERSION_SEGMENT.matcher(path.get(0)).matches() && !path.get(0).equals(API_VERSION)) {
                throw new HttpProblemException(404, "API version " + path.get(0) + " is not served, this engine serves " + API_VERSION);
            }
            throw new HttpProblemException(404, "No resource " + exchange.getRequestURI().getPath());
        }
        if (access == Access.READ_ONLY && !route.getMethod().equals("GET")) {
            throw new HttpProblemException(403, "The read-only token cannot " + route.getMethod() + " " + exchange.getRequestURI().getPath());
        }
        route.handle(exchange, path);
    }

    private HttpProblemException methodNotAllowed(HttpExchange exchange, List<String> path) {
        String allowed = Arrays.stream(routes).filter(route -> route.matches(path)).map(Route::getMethod).collect(Collectors.joining(", "));
        exchange.getResponseHeaders().set("Allow", allowed);
        return new HttpProblemException(405, exchange.getRequestMethod() + " is not allowed here, use " + allowed);
    }

    private enum Access {
        NONE,
        READ_ONLY,
        FULL
    }
}
