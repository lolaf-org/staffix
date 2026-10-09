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
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.admin.AdminApi;

import java.io.IOException;
import java.util.List;

@Slf4j
public final class RemoveSessionRoute extends Route {

    private final AdminApi adminApi;
    private final SessionLookup sessions;

    public RemoveSessionRoute(AdminApi adminApi, SessionLookup sessions) {
        super("DELETE", "v1/sessions/{group}/{name}/settings");
        this.adminApi = adminApi;
        this.sessions = sessions;
    }

    @Override
    public void handle(HttpExchange exchange, List<String> path) throws IOException {
        SessionLookup.StoredSession session = sessions.storedSession(param(path, "group"), param(path, "name"));
        log.info("Admin API: remove session {}", session.fixSessionId.getQualifiedName());
        adminApi.removeFixSessionSettings(session.fixSessionId, session.type);
        sendNoContent(exchange);
    }
}
