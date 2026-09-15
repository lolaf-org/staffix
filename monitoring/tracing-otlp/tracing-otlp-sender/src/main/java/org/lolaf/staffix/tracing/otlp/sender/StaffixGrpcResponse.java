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

import io.opentelemetry.sdk.common.export.GrpcResponse;
import io.opentelemetry.sdk.common.export.GrpcStatusCode;

/**
 * What one gRPC call came back as, in the shape OTel reads it.
 *
 * <p>Immutable, so the instance describing a successful export can be handed back again the next time
 * the collector answers the same way - the same reasoning as {@link StaffixHttpResponse}.
 */
final class StaffixGrpcResponse implements GrpcResponse {

    static final byte[] NO_MESSAGE = new byte[0];

    private final GrpcStatusCode statusCode;

    private final String statusDescription;

    private final byte[] responseMessage;

    StaffixGrpcResponse(int statusCode, String statusDescription, byte[] responseMessage) {
        this.statusCode = GrpcStatusCode.fromValue(statusCode);
        this.statusDescription = statusDescription == null ? "" : statusDescription;
        this.responseMessage = responseMessage;
    }

    @Override
    public GrpcStatusCode getStatusCode() {
        return statusCode;
    }

    @Override
    public String getStatusDescription() {
        return statusDescription;
    }

    @Override
    public byte[] getResponseMessage() {
        return responseMessage;
    }
}
