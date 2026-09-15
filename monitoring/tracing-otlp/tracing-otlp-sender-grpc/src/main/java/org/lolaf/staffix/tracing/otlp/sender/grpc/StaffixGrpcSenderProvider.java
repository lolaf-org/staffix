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
package org.lolaf.staffix.tracing.otlp.sender.grpc;

import io.opentelemetry.sdk.common.export.GrpcSender;
import io.opentelemetry.sdk.common.export.GrpcSenderConfig;
import io.opentelemetry.sdk.common.export.GrpcSenderProvider;
import org.lolaf.staffix.api.grpc.GrpcSenderSettings;
import org.lolaf.staffix.tracing.otlp.sender.StaffixOtelGrpcSender;

import java.util.function.Function;

/**
 * Makes OpenTelemetry's OTLP/gRPC exporters call through this library's
 * {@link org.lolaf.staffix.api.grpc.GrpcSender} rather than a client of their own.
 *
 * <p>Found by {@link java.util.ServiceLoader}, and in a module of its own because a
 * {@code META-INF/services} file is classpath-global: registering it beside the HTTP provider would hand
 * a {@code GrpcSenderProvider} to everyone who wanted only OTLP/HTTP.
 *
 * <p>{@link #FACTORY_PROPERTY} is mandatory, there being no client safe to default to - gRPC needs HTTP/2
 * trailers, which {@code java.net.http.HttpResponse} does not expose. Unset, this refuses while the
 * exporter is built, so a misconfiguration stops startup rather than dropping every span. A container-built
 * factory goes to {@link #StaffixGrpcSenderProvider(Function)} instead; see the module README.
 */
public final class StaffixGrpcSenderProvider implements GrpcSenderProvider {

    /**
     * Name of the system property selecting which client the exporters call through: the fully
     * qualified name of a class with a no-argument constructor implementing
     * {@code Function<GrpcSenderSettings, org.lolaf.staffix.api.grpc.GrpcSender>}.
     *
     * <p>There is no default. See the class javadoc for why.
     */
    public static final String FACTORY_PROPERTY = "org.lolaf.staffix.otlp.grpcSenderFactory";

    /**
     * {@code null} resolves {@link #FACTORY_PROPERTY} per call, as the ServiceLoader path does.
     */
    private final Function<GrpcSenderSettings, org.lolaf.staffix.api.grpc.GrpcSender> factory;

    /**
     * Called by {@link java.util.ServiceLoader}. The client is read from {@link #FACTORY_PROPERTY},
     * which is mandatory on this path.
     */
    public StaffixGrpcSenderProvider() {
        this(null);
    }

    /**
     * A provider calling through the given factory, for an application that builds one rather than naming
     * it. Reaches the exporter through {@code setComponentLoader}.
     *
     * @param factory the sender factory, or {@code null} to fall back on {@link #FACTORY_PROPERTY}
     */
    public StaffixGrpcSenderProvider(
            Function<GrpcSenderSettings, org.lolaf.staffix.api.grpc.GrpcSender> factory) {
        this.factory = factory;
    }

    @SuppressWarnings("unchecked")
    private static Function<GrpcSenderSettings, org.lolaf.staffix.api.grpc.GrpcSender> resolveFactory() {
        String className = System.getProperty(FACTORY_PROPERTY);
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalStateException("OTLP/gRPC needs an HTTP client that can read HTTP/2 trailers,"
                    + " and none is configured. Set -D" + FACTORY_PROPERTY + " to a factory backed by"
                    + " staffix-http-client-okhttp or staffix-http-client-jetty, and put that module on"
                    + " the classpath. The JDK client cannot serve gRPC: java.net.http.HttpResponse"
                    + " exposes no trailers at all.");
        }
        Object factory;
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> type = Class.forName(className.trim(), true,
                    loader == null ? StaffixGrpcSenderProvider.class.getClassLoader() : loader);
            factory = type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException(FACTORY_PROPERTY + " names " + className
                    + ", which could not be instantiated. It must be on the classpath and have a public"
                    + " no-argument constructor.", e);
        }
        if (!(factory instanceof Function)) {
            throw new IllegalStateException(FACTORY_PROPERTY + " names " + className + ", which is a "
                    + factory.getClass().getName() + " rather than a Function<GrpcSenderSettings,"
                    + " org.lolaf.staffix.api.grpc.GrpcSender>.");
        }
        return (Function<GrpcSenderSettings, org.lolaf.staffix.api.grpc.GrpcSender>) factory;
    }

    /**
     * Builds a sender for one exporter, binding everything about the destination that cannot change.
     *
     * @param config what the exporter was configured with
     * @return a sender calling through the configured client
     * @throws IllegalStateException if {@link #FACTORY_PROPERTY} is unset, or names something that is
     *                               not a usable sender factory
     */
    @Override
    public GrpcSender createSender(GrpcSenderConfig config) {
        GrpcSenderSettings settings = GrpcSenderSettings.builder()
                .endpointUrl(config.getEndpoint().toString())
                .fullMethodName(config.getFullMethodName())
                .connectTimeout(config.getConnectTimeout())
                // OTel's timeout is per attempt, which is what ours means too; the retry loop is what
                // turns several of them into one export.
                .requestTimeout(config.getTimeout())
                .sslContext(config.getSslContext())
                .compression(config.getCompressor() == null
                        ? GrpcSenderSettings.GrpcCompression.NONE
                        : GrpcSenderSettings.GrpcCompression.GZIP)
                .build();
        Function<GrpcSenderSettings, org.lolaf.staffix.api.grpc.GrpcSender> chosen =
                factory != null ? factory : resolveFactory();
        return new StaffixOtelGrpcSender(chosen.apply(settings), config);
    }
}
