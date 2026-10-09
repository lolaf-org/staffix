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

import java.io.IOException;
import java.util.List;

/**
 * A {@code GET} answered with a JSON document.
 */
abstract class JsonRoute extends Route {

    private final JsonResponseBuffer json;

    JsonRoute(String template, JsonResponseBuffer json) {
        super("GET", template);
        this.json = json;
    }

    abstract Object body(List<String> path) throws IOException;

    @Override
    public final void handle(HttpExchange exchange, List<String> path) throws IOException {
        json.send(exchange, body(path));
    }
}
