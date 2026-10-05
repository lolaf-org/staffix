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
package org.lolaf.staffix.admin.http.routes;

import com.sun.net.httpserver.HttpExchange;
import lombok.Getter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * One endpoint under an engine's path. A template segment in braces matches any value, read back with
 * {@link #param}.
 */
public abstract class Route {

    @Getter
    private final String method;
    private final List<String> template;

    protected Route(String method, String template) {
        this.method = method;
        this.template = template.isEmpty() ? List.of() : List.of(template.split("/"));
    }

    public abstract void handle(HttpExchange exchange, List<String> path) throws IOException;

    public boolean matches(List<String> path) {
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

    protected String param(List<String> path, String name) {
        for (int i = 0; i < template.size(); i++) {
            String segment = template.get(i);
            if (segment.length() == name.length() + 2 && segment.startsWith("{") && segment.startsWith(name, 1)) {
                return path.get(i);
            }
        }
        throw new IllegalStateException("No {" + name + "} in " + this);
    }

    /**
     * Sets the ETag, and answers 304 when the client already holds this version.
     */
    protected static boolean notModified(HttpExchange exchange, String version) throws IOException {
        String etag = "\"" + version + "\"";
        exchange.getResponseHeaders().set("ETag", etag);
        if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            exchange.sendResponseHeaders(304, -1);
            return true;
        }
        return false;
    }

    public static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    protected static void sendNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
    }

    @Override
    public String toString() {
        return method + " /" + String.join("/", template);
    }
}
