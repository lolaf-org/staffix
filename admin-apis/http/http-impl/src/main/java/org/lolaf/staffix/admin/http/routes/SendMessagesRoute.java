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
import org.lolaf.staffix.admin.http.HttpProblemException;
import org.lolaf.staffix.admin.http.dto.SendMessageRequest;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.session.FixSessionId;

import java.io.IOException;

@Slf4j
public final class SendMessagesRoute extends SessionOperationRoute {

    private final ObjectMapper mapper;

    public SendMessagesRoute(AdminApi adminApi, SessionLookup sessions, ObjectMapper mapper) {
        super("POST", "messages", adminApi, sessions);
        this.mapper = mapper;
    }

    @Override
    void apply(FixSessionId fixSessionId, HttpExchange exchange) throws IOException {
        SendMessageRequest send = mapper.readValue(exchange.getRequestBody(), SendMessageRequest.class);
        if (send.getMessages().isEmpty()) {
            throw new HttpProblemException(400, "No message to send");
        }
        // the messages themselves are not logged: they may carry a Password(554) or a client's data
        log.info("Admin API: send {} message(s) to {}", send.getMessages().size(), fixSessionId.getQualifiedName());
        adminApi.sendFixMessages(fixSessionId, send.getMessages(), send.getSeparator(), send.isPossDup());
    }
}
