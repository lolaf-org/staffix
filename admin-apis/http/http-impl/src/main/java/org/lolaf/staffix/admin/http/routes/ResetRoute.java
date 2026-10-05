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
import org.lolaf.staffix.admin.http.dto.ResetRequest;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.session.FixSessionId;

import java.io.IOException;

@Slf4j
public final class ResetRoute extends SessionOperationRoute {

    private final ObjectMapper mapper;

    public ResetRoute(AdminApi adminApi, SessionLookup sessions, ObjectMapper mapper) {
        super("POST", "reset", adminApi, sessions);
        this.mapper = mapper;
    }

    @Override
    void apply(FixSessionId fixSessionId, HttpExchange exchange) throws IOException {
        ResetRequest reset = mapper.readValue(exchange.getRequestBody(), ResetRequest.class);
        log.info("Admin API: reset {} with {}", fixSessionId.getQualifiedName(), reset.getMode());
        adminApi.resetSession(fixSessionId, reset.getMode());
    }
}
