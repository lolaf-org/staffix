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

import io.opentelemetry.proto.collector.logs.v1.ExportLogsPartialSuccess;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.grpc.GrpcReply;
import org.lolaf.staffix.api.grpc.GrpcSender;
import org.lolaf.staffix.api.grpc.GrpcStatus;
import org.lolaf.staffix.api.http.Header;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gRPC envelope around a batch of log records: what it does with a refusal, and with the
 * partial success that is otherwise completely silent.
 */
class GrpcLogsTransportTest {

    private static final byte[] BATCH = "a marshalled LogsData".getBytes(StandardCharsets.UTF_8);

    private static byte[] partialSuccess(long rejected, String message) {
        return ExportLogsServiceResponse.newBuilder()
                .setPartialSuccess(ExportLogsPartialSuccess.newBuilder()
                        .setRejectedLogRecords(rejected)
                        .setErrorMessage(message)
                        .build())
                .build()
                .toByteArray();
    }

    private static GrpcLogsTransport transportOn(ScriptedGrpcSender sender) {
        return new GrpcLogsTransport(sender, "http://collector:4317", "/logs.v1.LogsService/Export");
    }

    @Test
    void publishesABatchThroughTheSender() throws IOException {
        ScriptedGrpcSender sender = new ScriptedGrpcSender();

        transportOn(sender).send(BATCH, BATCH.length);

        assertThat(sender.sent).isEqualTo(BATCH);
    }

    @Test
    void turnsARefusalIntoAFailureTheLoggerCanReport() {
        ScriptedGrpcSender sender = new ScriptedGrpcSender();
        sender.statusCode = GrpcStatus.RESOURCE_EXHAUSTED;
        sender.statusMessage = "over quota";

        assertThatThrownBy(() -> transportOn(sender).send(BATCH, BATCH.length))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("RESOURCE_EXHAUSTED")
                .hasMessageContaining("over quota");
    }

    /**
     * The case this transport exists to make visible: the collector took the batch and threw part of it
     * away. Nothing else in this library would notice.
     */
    @Test
    void reportsHowManyRecordsAnAcceptedBatchLost() throws IOException {
        ScriptedGrpcSender sender = new ScriptedGrpcSender();
        sender.response = partialSuccess(17, "rate limited");

        GrpcLogsTransport transport = transportOn(sender);
        transport.send(BATCH, BATCH.length);

        assertThat(transport.rejectedLogRecords()).isEqualTo(17);
    }

    @Test
    void reportsNothingWhenTheCollectorKeptEveryRecord() throws IOException {
        ScriptedGrpcSender sender = new ScriptedGrpcSender();
        sender.response = partialSuccess(0, "");

        GrpcLogsTransport transport = transportOn(sender);
        transport.send(BATCH, BATCH.length);

        assertThat(transport.rejectedLogRecords()).isZero();
    }

    @Test
    void reportsNothingWhenTheCollectorAnswersWithNoPayloadAtAll() throws IOException {
        GrpcLogsTransport transport = transportOn(new ScriptedGrpcSender());

        transport.send(BATCH, BATCH.length);

        assertThat(transport.rejectedLogRecords()).isZero();
    }

    /**
     * A reply we cannot read is a diagnostic we lose, not a batch we failed to deliver.
     */
    @Test
    void doesNotFailABatchTheCollectorAcceptedWhenItsReplyIsUnreadable() throws IOException {
        ScriptedGrpcSender sender = new ScriptedGrpcSender();
        sender.response = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

        GrpcLogsTransport transport = transportOn(sender);
        transport.send(BATCH, BATCH.length);

        assertThat(transport.rejectedLogRecords()).isZero();
    }

    @Test
    void clearsTheCountBetweenBatches() throws IOException {
        ScriptedGrpcSender sender = new ScriptedGrpcSender();
        sender.response = partialSuccess(5, "rate limited");
        GrpcLogsTransport transport = transportOn(sender);
        transport.send(BATCH, BATCH.length);

        sender.response = null;
        transport.send(BATCH, BATCH.length);

        assertThat(transport.rejectedLogRecords())
                .as("a healthy flush must not inherit the last unhealthy one's count")
                .isZero();
    }

    @Test
    void closesTheSenderWithIt() {
        ScriptedGrpcSender sender = new ScriptedGrpcSender();

        transportOn(sender).close();

        assertThat(sender.closed).isTrue();
    }

    private static final class ScriptedGrpcSender implements GrpcSender {

        byte[] sent;
        byte[] response;
        int statusCode = GrpcStatus.OK;
        String statusMessage = "";
        boolean closed;

        @Override
        public GrpcReply send(byte[] data, int offset, int length, Header[] extraHeaders, GrpcReply into) {
            sent = java.util.Arrays.copyOfRange(data, offset, offset + length);
            into.status(statusCode, statusMessage);
            if (response != null) {
                System.arraycopy(response, 0, into.messageBuffer(response.length), 0, response.length);
                into.messageLength(response.length);
            }
            return into;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
