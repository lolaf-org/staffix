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
import io.opentelemetry.sdk.common.export.HttpSenderProvider;
import io.opentelemetry.sdk.common.export.ProxyOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.http.Header;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.http.jdk.JdkHttpSender;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SPI entry point: what the exporters find, and what it hands the chosen client.
 */
class StaffixHttpSenderProviderTest {

    private ExecutorService executor;
    private TestHttpSenderConfig config;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        config = new TestHttpSenderConfig(executor);
        RecordingFactory.bound.clear();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(StaffixHttpSenderProvider.FACTORY_PROPERTY);
        executor.shutdownNow();
    }

    /**
     * The point of the services file, and the only way a typo in it surfaces before runtime.
     */
    @Test
    void isFoundByTheServiceLoader() {
        List<HttpSenderProvider> providers = new ArrayList<>();
        ServiceLoader.load(HttpSenderProvider.class).forEach(providers::add);

        assertThat(providers).hasAtLeastOneElementOfType(StaffixHttpSenderProvider.class);
    }

    @Test
    void publishesThroughTheJdkClientWhenNothingIsConfigured() {
        HttpSender sender = new StaffixHttpSenderProvider().createSender(config);

        assertThat(((StaffixHttpSender) sender).delegate()).isInstanceOf(JdkHttpSender.class);
        sender.shutdown();
    }

    @Test
    void publishesThroughTheClientTheFactoryPropertyNames() {
        System.setProperty(StaffixHttpSenderProvider.FACTORY_PROPERTY, RecordingFactory.class.getName());

        HttpSender sender = new StaffixHttpSenderProvider().createSender(config);

        assertThat(((StaffixHttpSender) sender).delegate()).isInstanceOf(NoopHttpSender.class);
        sender.shutdown();
    }

    /**
     * The constructor an application uses when it builds its factory rather than naming it - a Spring
     * bean, reaching the exporter through {@code setComponentLoader}. A class name is all a system
     * property can carry, so a container-built factory has no other way in.
     */
    @Test
    void publishesThroughAFactoryHandedToItDirectly() {
        List<HttpSenderSettings> bound = new ArrayList<>();
        Function<HttpSenderSettings, org.lolaf.staffix.api.http.HttpSender> factory = settings -> {
            bound.add(settings);
            return new NoopHttpSender();
        };

        HttpSender sender = new StaffixHttpSenderProvider(factory).createSender(config);

        assertThat(((StaffixHttpSender) sender).delegate()).isInstanceOf(NoopHttpSender.class);
        assertThat(bound).hasSize(1);
        sender.shutdown();
    }

    /**
     * A factory passed in wins outright: an application that went to the trouble of building one is not
     * also asking for whatever a JVM-wide property happens to say.
     */
    @Test
    void aFactoryHandedInIgnoresTheProperty() {
        System.setProperty(StaffixHttpSenderProvider.FACTORY_PROPERTY, "com.example.NoSuchFactory");
        Function<HttpSenderSettings, org.lolaf.staffix.api.http.HttpSender> factory =
                settings -> new NoopHttpSender();

        HttpSender sender = new StaffixHttpSenderProvider(factory).createSender(config);

        assertThat(((StaffixHttpSender) sender).delegate()).isInstanceOf(NoopHttpSender.class);
        sender.shutdown();
    }

    /**
     * {@code null} means "behave as the ServiceLoader path does", which keeps the no-argument
     * constructor a plain delegation rather than a second code path.
     */
    @Test
    void aNullFactoryFallsBackOnTheProperty() {
        System.setProperty(StaffixHttpSenderProvider.FACTORY_PROPERTY, RecordingFactory.class.getName());

        HttpSender sender = new StaffixHttpSenderProvider(null).createSender(config);

        assertThat(((StaffixHttpSender) sender).delegate()).isInstanceOf(NoopHttpSender.class);
        sender.shutdown();
    }

    @Test
    void bindsTheExportersDestinationOntoOurSettings() {
        System.setProperty(StaffixHttpSenderProvider.FACTORY_PROPERTY, RecordingFactory.class.getName());
        config.endpoint = URI.create("https://collector.example.com:4318/v1/traces");
        config.contentType = "application/x-protobuf";
        config.connectTimeout = Duration.ofMillis(250);
        config.timeout = Duration.ofSeconds(3);
        config.proxyOptions = ProxyOptions.create(new InetSocketAddress("proxy.example.com", 8080));

        new StaffixHttpSenderProvider().createSender(config).shutdown();

        assertThat(RecordingFactory.bound).hasSize(1);
        HttpSenderSettings settings = RecordingFactory.bound.get(0);
        assertThat(settings.getEndpointUrl()).isEqualTo("https://collector.example.com:4318/v1/traces");
        assertThat(settings.getContentType()).isEqualTo("application/x-protobuf");
        assertThat(settings.getConnectTimeout()).isEqualTo(Duration.ofMillis(250));
        assertThat(settings.getRequestTimeout())
                .as("OTel's timeout is per attempt, which is what ours means")
                .isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.getProxySelector()).isNotNull();
    }

    @Test
    void doesNotBindThePerRequestHeadersToTheSender() {
        System.setProperty(StaffixHttpSenderProvider.FACTORY_PROPERTY, RecordingFactory.class.getName());
        config.headersSupplier = () -> TestHttpSenderConfig.headers("Authorization", "Basic abc");

        new StaffixHttpSenderProvider().createSender(config).shutdown();

        assertThat(RecordingFactory.bound.get(0).getHeaders())
                .as("OTel rebuilds its headers per export by contract, so they travel with the publish")
                .isEmpty();
    }

    @Test
    void refusesAFactoryClassThatIsNotOnTheClasspath() {
        System.setProperty(StaffixHttpSenderProvider.FACTORY_PROPERTY, "com.example.NoSuchFactory");

        assertThatThrownBy(() -> new StaffixHttpSenderProvider().createSender(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(StaffixHttpSenderProvider.FACTORY_PROPERTY)
                .hasMessageContaining("com.example.NoSuchFactory");
    }

    @Test
    void refusesAFactoryClassThatIsNotASenderFactory() {
        System.setProperty(StaffixHttpSenderProvider.FACTORY_PROPERTY, String.class.getName());

        assertThatThrownBy(() -> new StaffixHttpSenderProvider().createSender(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(StaffixHttpSenderProvider.FACTORY_PROPERTY)
                .hasMessageContaining("Function");
    }

    /**
     * Loaded by name from {@link StaffixHttpSenderProvider#FACTORY_PROPERTY}, so it must be public with a
     * public no-argument constructor - which is exactly the contract the property documents.
     */
    public static final class RecordingFactory
            implements Function<HttpSenderSettings, org.lolaf.staffix.api.http.HttpSender> {

        static final List<HttpSenderSettings> bound = new ArrayList<>();

        @Override
        public org.lolaf.staffix.api.http.HttpSender apply(HttpSenderSettings settings) {
            bound.add(settings);
            return new NoopHttpSender();
        }
    }

    private static final class NoopHttpSender implements org.lolaf.staffix.api.http.HttpSender {

        @Override
        public int send(byte[] data, int offset, int length, Header[] extraHeaders) {
            return 200;
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
