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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Serializes each response into an array kept across requests, unless one grew it past
 * {@link #RETAINED_CAPACITY}. Not thread safe: the server serves every request on one thread.
 */
public final class JsonResponseBuffer extends ByteArrayOutputStream {

    private static final int INITIAL_CAPACITY = 32 * 1024;
    private static final int RETAINED_CAPACITY = 1024 * 1024;

    private final ObjectMapper mapper;

    public JsonResponseBuffer(ObjectMapper mapper) {
        super(INITIAL_CAPACITY);
        this.mapper = mapper;
    }

    void send(HttpExchange exchange, Object body) throws IOException {
        reset();
        mapper.writeValue(this, body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, size());
        try (OutputStream out = exchange.getResponseBody()) {
            writeTo(out);
        }
    }

    @Override
    public synchronized void reset() {
        super.reset();
        if (buf.length > RETAINED_CAPACITY) {
            buf = new byte[INITIAL_CAPACITY];
        }
    }
}
