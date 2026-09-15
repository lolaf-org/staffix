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
package org.lolaf.staffix.stores.loggers.otlp;

import org.lolaf.staffix.api.http.HttpSender;

import java.io.IOException;

/**
 * OTLP/HTTP: a protobuf {@code LogsData} POSTed to the collector's {@code /v1/logs}.
 *
 * <p><strong>Partial success is not reported on this transport.</strong> A collector answers an export
 * with an {@code ExportLogsServiceResponse} whose {@code partial_success} says how many records it threw
 * away, and reading it would be worth doing - but {@link HttpSender#send} returns a status and nothing
 * else, because materialising a response body on every successful publish is exactly what that interface
 * is shaped to avoid. The gRPC transport does report it, since its reply carries the payload anyway. See
 * {@link GrpcLogsTransport}.
 */
final class HttpLogsTransport implements OtlpLogsTransport {

    private final HttpSender sender;

    private final String endpoint;

    HttpLogsTransport(HttpSender sender, String endpoint) {
        this.sender = sender;
        this.endpoint = endpoint;
    }

    @Override
    public void send(byte[] data, int length) throws IOException {
        sender.send(data, length);
    }

    @Override
    public String description() {
        return "OTLP http endpoint " + endpoint;
    }

    @Override
    public void close() {
        sender.close();
    }
}
