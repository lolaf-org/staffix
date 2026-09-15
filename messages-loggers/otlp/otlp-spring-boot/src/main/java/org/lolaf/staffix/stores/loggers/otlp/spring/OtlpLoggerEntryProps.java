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
package org.lolaf.staffix.stores.loggers.otlp.spring;

import lombok.Data;
import org.lolaf.staffix.stores.loggers.otlp.OtlpMessagesLoggerSettings;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One configured instance of the OTLP message logger: its instance id and the settings a session naming that id gets.
 */
@Data
@ConfigurationPropertiesSource
public class OtlpLoggerEntryProps {
    /**
     * Whether messages received are logged.
     */
    private Boolean logIncoming;
    /**
     * Whether messages sent are logged.
     */
    private Boolean logOutgoing;
    /**
     * Whether session events - connects, logons, logouts, disconnects - are logged alongside the messages,
     * which is what makes a log readable as a story rather than a stream.
     */
    private Boolean logEvents;

    /**
     * The collector's address.
     */
    private String otlpEndpointUrl;
    /**
     * Attributes attached to everything this process exports, such as service name and environment.
     */
    private Map<String, String> resourceAttributes = new LinkedHashMap<>();
    /**
     * Interval to flush the logs in the logs records buffer {@link #logsBufferSize} to the OTLP endpoint. You
     * can tune this flush delay to increase the theoretical non blocking logs sending throughput/s ({@link
     * #logsBufferSize} * 1000 / {@link #logsFlushDelay})
     */
    private Duration logsFlushDelay;
    /**
     * Defines how many logs can be buffered before being sent to the OTLP endpoint. This should match your FIX
     * session maximum messages throughput. When the buffer is full, a task to flush the log to the OTLP
     * endpoint will be launched and the current thread will enter a busy loop wait state that will use the
     * {@link #logsRecordsFullIdleStrategyBean} strategy. Must be a power of 2 value.
     */
    private Integer logsBufferSize;
    /**
     * Each buffered log need to keep a copy of the message to be logged, this defines the cached ByteBuffer
     * size to copy the messages to log, if not big enough compared ot the message to log, a new ByteBuffer
     * instance will be created and increase memory allocation So fine tune this value with your typical
     * incoming/outgoing FIX messages payload size to minimize memory allocation.
     */
    private Integer pooledLogsByteBufferSize;
    /**
     * Timeout for establishing a connection to the endpoint, one second by default.
     */
    private Duration connectTimeout;
    /**
     * Timeout for a whole exchange, ten seconds by default.
     */
    private Duration requestTimeout;
    /**
     * Extra HTTP headers on each push to the collector, typically an API key.
     */
    private Map<String, String> requestHeaders = new LinkedHashMap<>();
    /**
     * Replace /001 field delimiter in FIX messages sent to OTLP log endpoints, warning enabling this has a
     * serious impact on performances if the logger is not backed by an async messages logger
     */
    private Character fixMessageFieldsDelimiter;

    /**
     * Spring bean name of {@code Supplier<org.lolaf.ringos.idling.IdleStrategy>} used when the logs records buffer is full.
     */
    private String logsRecordsFullIdleStrategyBean;
    /**
     * Spring bean name of {@link java.util.concurrent.ScheduledExecutorService} for flushing buffered logs.
     */
    private String logsFlushingExecutorServiceBean;
    /**
     * Spring bean name of {@link java.net.ProxySelector} for the OTLP HTTP client.
     */
    private String proxySelectorBean;
    /**
     * Spring bean name of {@link java.net.Authenticator} for the OTLP HTTP client.
     */
    private String authenticatorBean;
    /**
     * Spring bean name of a {@code Function<HttpSenderSettings, HttpSender>} building the HTTP sender.
     * Defaults to the JDK client, which needs no dependency but is the most expensive of them over TLS;
     * point this at {@code OkHttpSender::new} for an HTTPS collector.
     *
     * <p>Also where the {@code HttpVersion} is chosen, there being no property for it: it is a
     * constructor argument on each sender rather than a settings field.
     */
    private String httpSenderFactoryBean;

    /**
     * Whether this logger publishes over OTLP/HTTP or OTLP/gRPC. Defaults to HTTP.
     */
    private OtlpMessagesLoggerSettings.OtlpTransport transport;

    /**
     * Name of the bean holding the {@code Function<GrpcSenderSettings, GrpcSender>} building the gRPC sender. Required
     * when {@link #transport} is {@code GRPC} and meaningless otherwise: gRPC needs a client that can
     * read HTTP/2 trailers, so one backed by {@code staffix-http-client-okhttp} or
     * {@code staffix-http-client-jetty}. There is no default, because the JDK client cannot serve gRPC.
     */
    private String grpcSenderFactoryBean;
}
