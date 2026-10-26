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
import org.lolaf.staffix.admin.http.HttpProblemException;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.ha.HaAdmin;
import org.lolaf.staffix.api.ha.HaSessionStatus;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Names the session as {@code v1/ha/sessions} lists it, by the config it runs: an initiator running a backup is named
 * after the backup, unlike in {@code v1/sessions}. An initiator and an acceptor of the same name are both re-synced.
 * The list follows the settings, so a session listed but not yet rebuilt with replication answers 409, not 400.
 */
@Slf4j
public final class HaSessionResyncRoute extends Route {

    private final AdminApi adminApi;

    public HaSessionResyncRoute(AdminApi adminApi) {
        super("POST", "v1/ha/sessions/{group}/{name}/resync");
        this.adminApi = adminApi;
    }

    @Override
    public void handle(HttpExchange exchange, List<String> path) throws IOException {
        String group = param(path, "group");
        String name = param(path, "name");
        HaAdmin ha = HaStatusRoute.ha(adminApi);
        List<HaSessionStatus> named = ha.getSessionsStatus().stream()
                .filter(session -> session.getFixSessionId().getGroup().equals(group) && session.getFixSessionId().getName().equals(name))
                .collect(Collectors.toList());
        if (named.isEmpty()) {
            throw new HttpProblemException(404, "No replicated session " + name + " in group " + group);
        }
        for (HaSessionStatus session : named) {
            log.info("Admin API: re-sync {} {}", session.getFixSessionType(), session.getFixSessionId().getQualifiedName());
            try {
                ha.resync(session.getFixSessionId(), session.getFixSessionType());
            } catch (IllegalArgumentException notReplicatedYet) {
                throw new HttpProblemException(409, notReplicatedYet.getMessage() + " until it is next built");
            }
        }
        sendNoContent(exchange);
    }
}
