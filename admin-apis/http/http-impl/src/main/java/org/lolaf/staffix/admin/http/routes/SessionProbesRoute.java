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
import org.lolaf.staffix.admin.http.ProbesDocuments;
import org.lolaf.staffix.api.session.FixSessionProbeScope;

import java.io.IOException;
import java.util.List;

public final class SessionProbesRoute extends Route {

    private final ProbesDocuments probes;
    private final SessionLookup sessions;

    public SessionProbesRoute(ProbesDocuments probes, SessionLookup sessions) {
        super("GET", "v1/sessions/{group}/{name}/probes");
        this.probes = probes;
        this.sessions = sessions;
    }

    @Override
    public void handle(HttpExchange exchange, List<String> path) throws IOException {
        ProbesDocuments.ProbesDocument document = probes.get(FixSessionProbeScope.of(sessions.runningConfig(param(path, "group"), param(path, "name"))));
        if (!notModified(exchange, document.getVersion())) {
            send(exchange, 200, "application/json", document.getJson());
        }
    }
}
