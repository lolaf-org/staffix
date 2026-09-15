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

import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.api.grpc.GrpcSender;
import org.lolaf.staffix.api.grpc.GrpcSenderSettings;
import org.lolaf.staffix.api.http.HttpSender;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.spring.boot.spi.BeanRef;
import org.lolaf.staffix.spring.boot.spi.FixMessagesLoggerSettingsContributor;
import org.lolaf.staffix.stores.loggers.otlp.OtlpMessagesLoggerSettings;
import org.springframework.context.ApplicationContext;

import java.net.Authenticator;
import java.net.ProxySelector;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Contributes the OTLP message logger's settings to the engine being built, so a session can name it in
 * configuration rather than the application wiring it.
 */
public class OtlpMessagesLoggerContributor implements FixMessagesLoggerSettingsContributor {

    private final OtlpMessagesLoggerProps props;
    private final ApplicationContext ctx;

    public OtlpMessagesLoggerContributor(OtlpMessagesLoggerProps props, ApplicationContext ctx) {
        this.props = props;
        this.ctx = ctx;
    }

    @Override
    public void contribute(Map<String, FixMessagesLoggerSettings> registry) {
        props.getInstances().forEach((key, p) -> {
            if (registry.putIfAbsent(key, build(key, p)) != null) {
                throw new IllegalStateException("staffix.messages-loggers-otlp.instances." + key
                        + " collides with another logger contributor for the same key");
            }
        });
    }

    private FixMessagesLoggerSettings build(String mapKey, OtlpLoggerEntryProps p) {
        if (p.getOtlpEndpointUrl() == null) {
            throw new IllegalArgumentException("staffix.messages-loggers-otlp.instances." + mapKey + ".otlp-endpoint-url is required");
        }
        OtlpMessagesLoggerSettings.OtlpMessagesLoggerSettingsBuilder<?, ?> b = OtlpMessagesLoggerSettings.builder()
                .instanceId(mapKey)
                .otlpEndpointUrl(p.getOtlpEndpointUrl());
        if (p.getLogIncoming() != null) b.logIncoming(p.getLogIncoming());
        if (p.getLogOutgoing() != null) b.logOutgoing(p.getLogOutgoing());
        if (p.getLogEvents() != null) b.logEvents(p.getLogEvents());
        if (p.getResourceAttributes() != null) p.getResourceAttributes().forEach(b::resourceAttribute);
        if (p.getLogsFlushDelay() != null) b.logsFlushDelay(p.getLogsFlushDelay());
        if (p.getLogsBufferSize() != null) b.logsBufferSize(p.getLogsBufferSize());
        if (p.getPooledLogsByteBufferSize() != null) b.pooledLogsByteBufferSize(p.getPooledLogsByteBufferSize());
        if (p.getFixMessageFieldsDelimiter() != null) b.fixMessageFieldsDelimiter(p.getFixMessageFieldsDelimiter());

        String path = "staffix.messages-loggers-otlp.instances." + mapKey;
        if (p.getTransport() != null) b.transport(p.getTransport());
        b.httpSenderSettings(httpSenderSettings(p, path));
        b.grpcSenderSettings(grpcSenderSettings(p, path));
        BeanRef.<Function<HttpSenderSettings, HttpSender>>resolveOptional(ctx, p.getHttpSenderFactoryBean(), Function.class, path + ".http-sender-factory-bean")
                .ifPresent(b::httpSenderFactory);
        BeanRef.<Function<GrpcSenderSettings, GrpcSender>>resolveOptional(ctx, p.getGrpcSenderFactoryBean(), Function.class, path + ".grpc-sender-factory-bean")
                .ifPresent(b::grpcSenderFactory);
        BeanRef.<Supplier<IdleStrategy>>resolveOptional(ctx, p.getLogsRecordsFullIdleStrategyBean(), Supplier.class, path + ".logs-records-full-idle-strategy-bean")
                .ifPresent(b::logsRecordsFullIdleStrategy);
        BeanRef.<ScheduledExecutorService>resolveOptional(ctx, p.getLogsFlushingExecutorServiceBean(), ScheduledExecutorService.class, path + ".logs-flushing-executor-service-bean")
                .ifPresent(b::logsFlushingExecutorService);

        return b.build();
    }

    /**
     * Everything the gRPC sender binds bar the endpoint and the method, which the logger sets itself.
     *
     * <p>Populated whatever the transport is - it costs nothing when unused, and building it only for
     * {@code GRPC} would mean a property silently doing nothing when the transport is set afterwards.
     */
    private GrpcSenderSettings grpcSenderSettings(OtlpLoggerEntryProps p, String path) {
        GrpcSenderSettings.GrpcSenderSettingsBuilder grpc = GrpcSenderSettings.builder();
        if (p.getConnectTimeout() != null) grpc.connectTimeout(p.getConnectTimeout());
        if (p.getRequestTimeout() != null) grpc.requestTimeout(p.getRequestTimeout());
        if (p.getRequestHeaders() != null) p.getRequestHeaders().forEach(grpc::header);
        BeanRef.<ProxySelector>resolveOptional(ctx, p.getProxySelectorBean(), ProxySelector.class, path + ".proxy-selector-bean")
                .ifPresent(grpc::proxySelector);
        BeanRef.<Authenticator>resolveOptional(ctx, p.getAuthenticatorBean(), Authenticator.class, path + ".authenticator-bean")
                .ifPresent(grpc::authenticator);
        return grpc.build();
    }

    /**
     * Everything the HTTP sender binds bar the endpoint and the content type, which the logger sets
     * itself.
     */
    private HttpSenderSettings httpSenderSettings(OtlpLoggerEntryProps p, String path) {
        HttpSenderSettings.HttpSenderSettingsBuilder http = HttpSenderSettings.builder();
        if (p.getConnectTimeout() != null) http.connectTimeout(p.getConnectTimeout());
        if (p.getRequestTimeout() != null) http.requestTimeout(p.getRequestTimeout());
        if (p.getRequestHeaders() != null) p.getRequestHeaders().forEach(http::header);
        BeanRef.<ProxySelector>resolveOptional(ctx, p.getProxySelectorBean(), ProxySelector.class, path + ".proxy-selector-bean")
                .ifPresent(http::proxySelector);
        BeanRef.<Authenticator>resolveOptional(ctx, p.getAuthenticatorBean(), Authenticator.class, path + ".authenticator-bean")
                .ifPresent(http::authenticator);
        return http.build();
    }
}
