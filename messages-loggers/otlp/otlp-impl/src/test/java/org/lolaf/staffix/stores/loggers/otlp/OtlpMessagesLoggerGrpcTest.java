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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.grpc.GrpcFraming;
import org.lolaf.staffix.api.logging.FixMessagesLogger.BatchingLogger;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.grpc.testkit.StubGrpcServer;
import org.lolaf.staffix.http.okhttp.OkHttpGrpcSender;
import org.lolaf.staffix.stores.loggers.otlp.OtlpMessagesLoggerSettings.OtlpTransport;
import org.mockito.Mockito;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The logger over OTLP/gRPC, against a stub collector.
 *
 * <p>The existing {@link TestOtlpMessagesLogger} covers the HTTP path against a real OpenTelemetry
 * collector in a container; this covers the envelope that differs, which is all gRPC changes - the
 * protobuf inside it is byte-for-byte the same, {@code LogsData} and {@code ExportLogsServiceRequest}
 * being wire-identical.
 */
class OtlpMessagesLoggerGrpcTest {

    private StubGrpcServer server;

    private OtlpMessagesLogger factory;

    /**
     * Starts a logger for one session, which is where the transport is actually built.
     */
    private BatchingLogger startLogger(OtlpMessagesLoggerSettings settings) {
        factory = new OtlpMessagesLogger(settings);
        factory.start();
        return factory.instanciateLogger("test",
                FixSessionId.of("grpcSid", FixRegularVersion.VERSION_44, "SENDER", "TARGET"),
                Mockito.mock(MessageTypeRegistry.class));
    }

    @BeforeEach
    void setUp() throws IOException {
        server = new StubGrpcServer();
    }

    @AfterEach
    void tearDown() {
        if (factory != null) {
            factory.stop(Deadline.immediate());
        }
        server.close();
    }

    private OtlpMessagesLoggerSettings.OtlpMessagesLoggerSettingsBuilder<?, ?> grpcSettings() {
        return OtlpMessagesLoggerSettings.builder()
                .otlpEndpointUrl(server.url())
                .transport(OtlpTransport.GRPC)
                .grpcSenderFactory(OkHttpGrpcSender::new);
    }

    /**
     * There is no client this logger could default to for gRPC, so a misconfiguration has to be loud.
     * The message is asserted rather than the exception type: it is the whole user experience of it.
     */
    @Test
    void refusesToBuildAGrpcLoggerWithNoClientToCallThrough() {
        assertThatThrownBy(() -> startLogger(OtlpMessagesLoggerSettings.builder()
                .otlpEndpointUrl(server.url())
                .transport(OtlpTransport.GRPC)
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grpcSenderFactory")
                .hasMessageContaining("staffix-http-client-okhttp")
                .hasMessageContaining("staffix-http-client-jetty");
    }

    @Test
    void checksTheCollectorIsReachableByCallingTheLogsExportMethod() {
        BatchingLogger logger = startLogger(grpcSettings().build());

        assertThat(logger.isUnderlyingStorageResourceAvailable()).isTrue();

        assertThat(server.received()).isNotEmpty();
        assertThat(server.lastReceived().getTarget())
                .isEqualTo("/opentelemetry.proto.collector.logs.v1.LogsService/Export");
        assertThat(server.lastReceived().getHeaders().get("content-type")).startsWith("application/grpc");

        byte[] framed = server.lastReceived().getBody().toByteArray();
        assertThat(GrpcFraming.messageLength(framed, 0))
                .as("a marshalled LogsData, framed as gRPC requires")
                .isEqualTo(framed.length - GrpcFraming.HEADER_LENGTH);
    }

    @Test
    void reportsTheCollectorUnavailableWhenItRefusesTheCall() {
        server.respondWith(org.lolaf.staffix.api.grpc.GrpcStatus.UNAVAILABLE, "restarting");

        BatchingLogger logger = startLogger(grpcSettings().build());

        assertThat(logger.isUnderlyingStorageResourceAvailable())
                .as("a refused call is not a reachable collector")
                .isFalse();
    }

    @Test
    void describesItselfByTheMethodItCalls() {
        BatchingLogger logger = startLogger(grpcSettings().build());

        assertThat(logger.getUnderlyingStorageResourceDescription())
                .contains("grpc")
                .contains("/opentelemetry.proto.collector.logs.v1.LogsService/Export");
    }
}
