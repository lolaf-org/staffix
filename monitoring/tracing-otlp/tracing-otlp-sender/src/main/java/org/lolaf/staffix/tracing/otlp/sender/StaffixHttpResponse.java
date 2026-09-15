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
package org.lolaf.staffix.tracing.otlp.sender;

import io.opentelemetry.sdk.common.export.HttpResponse;

/**
 * What one exchange came back as, in the shape OTel reads it.
 *
 * <p>Immutable, so the instance describing a successful publish can be handed back again the next time
 * the collector answers the same way - which, in a healthy system, is every time. That is the whole
 * reason this is a class rather than a lambda: {@code StaffixHttpSender} keeps the last one and reuses
 * it, so a working exporter allocates nothing to describe a success it has already described.
 */
final class StaffixHttpResponse implements HttpResponse {

    static final byte[] NO_BODY = new byte[0];

    private final int statusCode;

    private final String statusMessage;

    private final byte[] responseBody;

    StaffixHttpResponse(int statusCode, String statusMessage, byte[] responseBody) {
        this.statusCode = statusCode;
        // Never null: OTel puts this straight into its export failure logs, and a client that cannot
        // report a reason phrase - the JDK one cannot - should show as absent, not as "null".
        this.statusMessage = statusMessage == null ? "" : statusMessage;
        this.responseBody = responseBody;
    }

    @Override
    public int getStatusCode() {
        return statusCode;
    }

    @Override
    public String getStatusMessage() {
        return statusMessage;
    }

    @Override
    public byte[] getResponseBody() {
        return responseBody;
    }
}
