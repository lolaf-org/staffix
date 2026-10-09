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
import org.lolaf.staffix.admin.http.dto.SeqNumsRequest;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.session.FixSessionId;

import java.io.IOException;

@Slf4j
public final class SeqNumsRoute extends SessionOperationRoute {

    private final ObjectMapper mapper;

    public SeqNumsRoute(AdminApi adminApi, SessionLookup sessions, ObjectMapper mapper) {
        super("PUT", "seqnums", adminApi, sessions);
        this.mapper = mapper;
    }

    @Override
    void apply(FixSessionId fixSessionId, HttpExchange exchange) throws IOException {
        SeqNumsRequest seqNums = mapper.readValue(exchange.getRequestBody(), SeqNumsRequest.class);
        log.info("Admin API: set seqnums of {} to incoming {}, outgoing {}",
                fixSessionId.getQualifiedName(), seqNums.getIncoming(), seqNums.getOutgoing());
        if (seqNums.getIncoming() != null) {
            adminApi.setIncomingSeqNum(fixSessionId, seqNums.getIncoming());
        }
        if (seqNums.getOutgoing() != null) {
            adminApi.setOutgoingSeqNum(fixSessionId, seqNums.getOutgoing());
        }
    }
}
