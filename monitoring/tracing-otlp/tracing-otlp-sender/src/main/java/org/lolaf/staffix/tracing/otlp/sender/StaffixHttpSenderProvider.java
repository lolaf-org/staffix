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

import io.opentelemetry.sdk.common.export.HttpSender;
import io.opentelemetry.sdk.common.export.HttpSenderConfig;
import io.opentelemetry.sdk.common.export.HttpSenderProvider;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.http.jdk.JdkHttpSender;

import java.util.function.Function;

/**
 * Makes OpenTelemetry's OTLP/HTTP exporters publish through this library's
 * {@link org.lolaf.staffix.api.http.HttpSender} rather than a client of their own.
 *
 * <p>Found by {@link java.util.ServiceLoader}. It must <em>replace</em>
 * {@code opentelemetry-exporter-sender-okhttp} rather than sit beside it: OpenTelemetry takes whichever
 * provider it finds first, so with both present the client depends on classpath order.
 *
 * <p>The client defaults to {@link JdkHttpSender} and is otherwise named by {@link #FACTORY_PROPERTY}, or
 * passed to {@link #StaffixHttpSenderProvider(Function)} when a container built it. See the module README.
 *
 * <p>Per-request headers are not bound at construction, unlike the rest of the exporter's configuration:
 * OpenTelemetry rebuilds them for every export, so they travel with each publish.
 */
public final class StaffixHttpSenderProvider implements HttpSenderProvider {

    /**
     * Name of the system property selecting which HTTP client the exporters publish through: the fully
     * qualified name of a class with a no-argument constructor implementing
     * {@code Function<HttpSenderSettings, org.lolaf.staffix.api.http.HttpSender>}.
     *
     * <p>Unset, the JDK's own HTTP client is used.
     */
    public static final String FACTORY_PROPERTY = "org.lolaf.staffix.otlp.httpSenderFactory";

    /**
     * {@code null} resolves {@link #FACTORY_PROPERTY} per call, as the ServiceLoader path does.
     */
    private final Function<HttpSenderSettings, org.lolaf.staffix.api.http.HttpSender> factory;

    /**
     * Called by {@link java.util.ServiceLoader}. The client is read from {@link #FACTORY_PROPERTY}.
     */
    public StaffixHttpSenderProvider() {
        this(null);
    }

    /**
     * A provider publishing through the given factory, for an application that builds one rather than
     * naming it. Reaches the exporter through {@code setComponentLoader}, the ServiceLoader being unable
     * to call this.
     *
     * @param factory the sender factory, or {@code null} to fall back on {@link #FACTORY_PROPERTY}
     */
    public StaffixHttpSenderProvider(
            Function<HttpSenderSettings, org.lolaf.staffix.api.http.HttpSender> factory) {
        this.factory = factory;
    }

    @SuppressWarnings("unchecked")
    private static Function<HttpSenderSettings, org.lolaf.staffix.api.http.HttpSender> resolveFactory() {
        String className = System.getProperty(FACTORY_PROPERTY);
        if (className == null || className.trim().isEmpty()) {
            return JdkHttpSender::new;
        }
        Object factory;
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> type = Class.forName(className.trim(), true,
                    loader == null ? StaffixHttpSenderProvider.class.getClassLoader() : loader);
            factory = type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException(FACTORY_PROPERTY + " names " + className
                    + ", which could not be instantiated. It must be on the classpath and have a public"
                    + " no-argument constructor.", e);
        }
        if (!(factory instanceof Function)) {
            throw new IllegalStateException(FACTORY_PROPERTY + " names " + className + ", which is a "
                    + factory.getClass().getName() + " rather than a Function<HttpSenderSettings,"
                    + " org.lolaf.staffix.api.http.HttpSender>.");
        }
        return (Function<HttpSenderSettings, org.lolaf.staffix.api.http.HttpSender>) factory;
    }

    /**
     * Builds a sender for one exporter, binding everything about the destination that cannot change.
     *
     * @param config what the exporter was configured with
     * @return a sender publishing through the configured client
     * @throws IllegalStateException if {@link #FACTORY_PROPERTY} names something that is not a usable
     *                               sender factory
     */
    @Override
    public HttpSender createSender(HttpSenderConfig config) {
        HttpSenderSettings settings = HttpSenderSettings.builder()
                .endpointUrl(config.getEndpoint().toString())
                .contentType(config.getContentType())
                .connectTimeout(config.getConnectTimeout())
                // OTel's timeout is per attempt, which is what ours means too; the retry loop is what
                // turns several of them into one publish.
                .requestTimeout(config.getTimeout())
                .sslContext(config.getSslContext())
                .proxySelector(config.getProxyOptions() == null
                        ? null : config.getProxyOptions().getProxySelector())
                .build();
        Function<HttpSenderSettings, org.lolaf.staffix.api.http.HttpSender> chosen =
                factory != null ? factory : resolveFactory();
        return new StaffixHttpSender(chosen.apply(settings), config);
    }
}
