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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.ha.SwitchoverOutcome;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Answers 200 with the outcome once the switchover ended, whichever it is: only a node that cannot switch over at
 * all is a conflict.
 */
@Slf4j
public final class HaSwitchoverRoute extends Route {

    private final AdminApi adminApi;
    private final ObjectMapper mapper;

    public HaSwitchoverRoute(AdminApi adminApi, ObjectMapper mapper) {
        super("POST", "v1/ha/switchover");
        this.adminApi = adminApi;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange exchange, List<String> path) throws IOException {
        log.info("Admin API: switch over");
        SwitchoverOutcome outcome = HaStatusRoute.ha(adminApi).switchover();
        log.info("Admin API: switchover {}", outcome);
        send(exchange, 200, "application/json", mapper.writeValueAsBytes(Map.of("outcome", outcome)));
    }
}
