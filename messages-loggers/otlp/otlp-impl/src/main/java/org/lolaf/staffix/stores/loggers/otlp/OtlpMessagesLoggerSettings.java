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

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.experimental.SuperBuilder;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.api.grpc.GrpcSender;
import org.lolaf.staffix.api.grpc.GrpcSenderSettings;
import org.lolaf.staffix.api.http.HttpSender;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.api.logging.AbstractFixMessageLoggerSettings;
import org.lolaf.staffix.http.jdk.JdkHttpSender;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The collector endpoint, the transport, and the batching in front of it.
 *
 * <p>gRPC needs the OkHttp or Jetty client: HTTP/2 trailers are not exposed by the JDK's.
 */
@Getter
@SuperBuilder(toBuilder = true)
public class OtlpMessagesLoggerSettings extends AbstractFixMessageLoggerSettings {

    /**
     * HTTP OTLP endpoint url
     */
    private final String otlpEndpointUrl;

    /**
     * Builds the HTTP sender the logs are published through, from the settings assembled by the logger.
     *
     * <p>Defaults to {@link JdkHttpSender}, on the JDK's own HTTP client, for one reason: it is the only
     * implementation that needs nothing on the classpath. <strong>It is not the best one.</strong> Over
     * TLS it is the most expensive of the three - about 56 KB per publish, against 13 KB for
     * {@code org.lolaf.staffix.http.okhttp.OkHttpSender} - because the JDK client allocates fresh wrap
     * and unwrap buffers per TLS operation and no caller can tune that, and it is the slowest of them
     * on both transports. <strong>For an HTTPS collector, which is what a production one is, set this to
     * {@code OkHttpSender::new}</strong> and add the {@code staffix-http-client-okhttp} module.
     * {@code JettyHttpSender::new} is cheaper still over plaintext and needs Java 17. See
     * {@code http-clients/README.md} for the full comparison.
     */
    @Builder.Default
    private final Function<HttpSenderSettings, HttpSender> httpSenderFactory = JdkHttpSender::new;

    /**
     * Everything else the HTTP sender binds: timeouts, static request headers, TLS context, proxy and
     * authenticator.
     *
     * <p>The endpoint and the content type in here are ignored: the logger sets them itself from
     * {@link #otlpEndpointUrl}, with {@code /v1/logs} appended when it is missing, and to
     * {@code application/x-protobuf}, which is what it writes.
     */
    @Builder.Default
    private final HttpSenderSettings httpSenderSettings = HttpSenderSettings.builder().build();

    /**
     * Whether to publish over OTLP/HTTP or OTLP/gRPC. HTTP unless you have a reason: it is served by
     * every client module, gRPC only by two.
     */
    @Builder.Default
    private final OtlpTransport transport = OtlpTransport.HTTP;

    /**
     * Builds the gRPC sender, when {@link #transport} is {@link OtlpTransport#GRPC}.
     *
     * <p>No default, unlike {@link #httpSenderFactory}, because there is nothing safe to default to:
     * gRPC carries its status in HTTP/2 trailers and {@code java.net.http.HttpResponse} exposes none, so
     * only {@code staffix-http-client-okhttp} and {@code staffix-http-client-jetty} can serve it. Leave
     * it unset with {@code GRPC} selected and the logger refuses to start, naming both.
     */
    @Builder.Default
    private final Function<GrpcSenderSettings, GrpcSender> grpcSenderFactory = null;

    /**
     * What the gRPC sender binds beyond its endpoint and method, which the logger sets itself.
     */
    @Builder.Default
    private final GrpcSenderSettings grpcSenderSettings = GrpcSenderSettings.builder().build();

    /**
     * Otlp resource attributes to send over the wire such as "service.name"
     */
    @Singular
    private final Map<String, String> resourceAttributes;

    /**
     * Interval to flush the logs in the logs records buffer {@link #logsBufferSize} to the OTLP endpoint.
     * You can tune this flush delay to increase the theoretical non blocking logs sending throughput/s
     * ({@link #logsBufferSize} * 1000 / {@link #logsFlushDelay})
     */
    @Builder.Default
    private final Duration logsFlushDelay = Duration.ofSeconds(1);

    /**
     * Defines how many logs can be buffered before being sent to the OTLP endpoint.
     * This should match your FIX session maximum messages throughput.
     * When the buffer is full, a task to flush the log to the OTLP endpoint will be launched
     * and the current thread will enter a busy loop wait state that will use the {@link #logsRecordsFullIdleStrategy} parameter
     * Must be a power of 2 value
     */
    @Builder.Default
    private final int logsBufferSize = 512;

    /**
     * Each buffered log need to keep a copy of the message to be logged, this defines the cached ByteBuffer size to copy
     * the messages to log, if not big enough compared ot the message to log, a new ByteBuffer instance will be created and increase memory allocation
     * So fine tune this value with your typical incoming/outgoing FIX messages payload size to minimize memory allocation.
     */
    @Builder.Default
    private final int pooledLogsByteBufferSize = 2 * 1024;
    /**
     * Idle strategy when retrying to write a log into a full logs records buffer
     */
    @Builder.Default
    private final Supplier<IdleStrategy> logsRecordsFullIdleStrategy = BackoffIdleStrategy::new;
    /**
     * Runs the flush to the collector, so it does not happen on the message path.
     */
    @Builder.Default
    private final ScheduledExecutorService logsFlushingExecutorService = null;

    /**
     * Replace /001 field delimiter in FIX messages sent to OTLP log endpoints, warning enabling this has a serious impact on performances
     * if the logger is not backed by an async messages logger
     */
    @Builder.Default
    private final Character fixMessageFieldsDelimiter = null;

    /**
     * Which OTLP transport a logger publishes over.
     */
    public enum OtlpTransport {

        /**
         * Protobuf over HTTP, POSTed to the collector's {@code /v1/logs}. Served by every client module.
         */
        HTTP,

        /**
         * Protobuf over gRPC, called against the collector's {@code LogsService/Export}. Needs a client
         * that can read HTTP/2 trailers, so {@code okhttp} or {@code jetty} and not {@code jdk}.
         */
        GRPC
    }
}