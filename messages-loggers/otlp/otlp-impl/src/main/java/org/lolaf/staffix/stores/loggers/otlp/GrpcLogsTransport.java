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

import com.google.protobuf.InvalidProtocolBufferException;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsPartialSuccess;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.grpc.GrpcReply;
import org.lolaf.staffix.api.grpc.GrpcSender;
import org.lolaf.staffix.api.grpc.GrpcStatus;

import java.io.IOException;

/**
 * OTLP/gRPC: the same protobuf, framed and called against
 * {@code /opentelemetry.proto.collector.logs.v1.LogsService/Export}.
 *
 * <p>Two things are true here that are not on the HTTP transport. The first is that a non-OK outcome
 * arrives as a status rather than an exception, so it is turned into one here - the logger above wants a
 * failed flush to look the same whichever transport it used.
 *
 * <p>The second is that <strong>partial success is visible</strong>. A collector that accepted a batch
 * but threw some of it away says so in {@code ExportLogsServiceResponse.partial_success}, and that reply
 * is already in hand because gRPC returns a message. Losing half your log records to a collector's rate
 * limiter is otherwise completely silent, so it is logged at warn.
 *
 * <p>The reply is kept and reused across flushes rather than allocated per batch: a logger flushes on a
 * schedule from one thread, which is exactly the case the caller-owned reply exists for.
 */
@Slf4j
final class GrpcLogsTransport implements OtlpLogsTransport {

    private final GrpcSender sender;
    private final String endpoint;
    private final String fullMethodName;
    private final GrpcReply reply;
    private long rejectedLogRecords;

    GrpcLogsTransport(GrpcSender sender, String endpoint, String fullMethodName) {
        this.sender = sender;
        this.endpoint = endpoint;
        this.fullMethodName = fullMethodName;
        this.reply = new GrpcReply();
    }

    @Override
    public void send(byte[] data, int length) throws IOException {
        sender.send(data, length, reply.reset());
        if (!reply.isOk()) {
            throw new IOException("The OTLP collector at " + endpoint + " refused the batch: "
                    + GrpcStatus.nameOf(reply.getStatusCode())
                    + (reply.getStatusMessage().isEmpty() ? "" : " - " + reply.getStatusMessage()));
        }
        reportPartialSuccess();
    }

    /**
     * @return how many records the collector rejected while still accepting the batch, or 0 when it
     * rejected none and when the reply could not be read. Returned rather than only logged so that a
     * test can assert it without reaching into a logging framework.
     */
    long rejectedLogRecords() {
        return rejectedLogRecords;
    }

    @Override
    public String description() {
        return "OTLP grpc endpoint " + endpoint + fullMethodName;
    }

    @Override
    public void close() {
        sender.close();
    }

    /**
     * A successful export can still have dropped records. Nothing else in this library would notice.
     */
    private void reportPartialSuccess() {
        rejectedLogRecords = 0;
        if (reply.getMessageLength() == 0) {
            return;
        }
        try {
            ExportLogsServiceResponse response = ExportLogsServiceResponse.parseFrom(
                    java.nio.ByteBuffer.wrap(reply.getMessage(), 0, reply.getMessageLength()));
            if (!response.hasPartialSuccess()) {
                return;
            }
            ExportLogsPartialSuccess partial = response.getPartialSuccess();
            rejectedLogRecords = partial.getRejectedLogRecords();
            if (partial.getRejectedLogRecords() > 0) {
                log.warn("The OTLP collector at {} accepted the batch but rejected {} log records{}",
                        endpoint, partial.getRejectedLogRecords(),
                        partial.getErrorMessage().isEmpty() ? "" : ": " + partial.getErrorMessage());
            }
        } catch (InvalidProtocolBufferException e) {
            // Diagnostic only: a reply we cannot read must not fail a batch the collector accepted.
            log.debug("Could not read the OTLP export response from {}", endpoint, e);
        }
    }
}
