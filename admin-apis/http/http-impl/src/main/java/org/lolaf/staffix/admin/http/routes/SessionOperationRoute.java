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
import org.lolaf.staffix.admin.http.HttpProblemException;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.session.FixSessionId;

import java.io.IOException;
import java.util.List;

/**
 * An operation on a running session, answered 204. The engine refuses a config that stopped running, so an
 * initiator switching configs while the request runs answers 409 rather than the 400 of a request that was wrong.
 */
abstract class SessionOperationRoute extends Route {

    protected final AdminApi adminApi;
    private final SessionLookup sessions;

    SessionOperationRoute(String method, String operation, AdminApi adminApi, SessionLookup sessions) {
        super(method, "v1/sessions/{group}/{name}/" + operation);
        this.adminApi = adminApi;
        this.sessions = sessions;
    }

    abstract void apply(FixSessionId fixSessionId, HttpExchange exchange) throws IOException;

    @Override
    public final void handle(HttpExchange exchange, List<String> path) throws IOException {
        String group = param(path, "group");
        String name = param(path, "name");
        FixSessionId running = sessions.runningConfig(group, name);
        try {
            apply(running, exchange);
        } catch (IllegalArgumentException e) {
            if (!running.equals(sessions.runningConfig(group, name))) {
                throw new HttpProblemException(409, "Session " + name + " of group " + group
                        + " switched to another config while the request ran; retry");
            }
            throw e;
        }
        sendNoContent(exchange);
    }
}
