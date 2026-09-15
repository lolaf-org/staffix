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
import io.opentelemetry.sdk.common.export.GrpcSenderProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.grpc.GrpcReply;
import org.lolaf.staffix.api.grpc.GrpcSenderSettings;
import org.lolaf.staffix.api.http.Header;
import org.lolaf.staffix.http.okhttp.OkHttpGrpcSender;

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
 * The gRPC SPI entry point: what the exporters find, and what it refuses to do without.
 */
class StaffixGrpcSenderProviderTest {

    private ExecutorService executor;
    private TestGrpcSenderConfig config;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        config = new TestGrpcSenderConfig(executor);
        RecordingFactory.bound.clear();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(StaffixGrpcSenderProvider.FACTORY_PROPERTY);
        executor.shutdownNow();
    }

    @Test
    void isFoundByTheServiceLoader() {
        List<GrpcSenderProvider> providers = new ArrayList<>();
        ServiceLoader.load(GrpcSenderProvider.class).forEach(providers::add);

        assertThat(providers).hasAtLeastOneElementOfType(StaffixGrpcSenderProvider.class);
    }

    /**
     * The message is the entire user experience of this misconfiguration, so it is asserted rather than
     * merely the exception type: it has to name the property and both modules that can satisfy it, and
     * say why the obvious third one cannot.
     */
    @Test
    void refusesToBuildASenderWhenNoClientIsNamed() {
        assertThatThrownBy(() -> new StaffixGrpcSenderProvider().createSender(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(StaffixGrpcSenderProvider.FACTORY_PROPERTY)
                .hasMessageContaining("staffix-http-client-okhttp")
                .hasMessageContaining("staffix-http-client-jetty")
                .hasMessageContaining("trailers");
    }

    @Test
    void callsThroughTheClientTheFactoryPropertyNames() {
        System.setProperty(StaffixGrpcSenderProvider.FACTORY_PROPERTY, RecordingFactory.class.getName());

        GrpcSender sender = new StaffixGrpcSenderProvider().createSender(config);

        assertThat(RecordingFactory.bound).hasSize(1);
        sender.shutdown();
    }

    /**
     * The constructor an application uses when it builds its factory rather than naming it - a Spring
     * bean, reaching the exporter through {@code setComponentLoader}. A class name is all a system
     * property can carry, so a container-built factory has no other way in.
     */
    @Test
    void callsThroughAFactoryHandedToItDirectly() {
        List<GrpcSenderSettings> bound = new ArrayList<>();
        Function<GrpcSenderSettings, org.lolaf.staffix.api.grpc.GrpcSender> factory = settings -> {
            bound.add(settings);
            return new OkHttpGrpcSender(settings);
        };

        GrpcSender sender = new StaffixGrpcSenderProvider(factory).createSender(config);

        assertThat(bound).hasSize(1);
        sender.shutdown();
    }

    /**
     * And it does so without the property, which on this provider is otherwise mandatory - so a
     * container-configured application needs no {@code -D} at all.
     */
    @Test
    void aFactoryHandedInReplacesTheMandatoryProperty() {
        Function<GrpcSenderSettings, org.lolaf.staffix.api.grpc.GrpcSender> factory =
                OkHttpGrpcSender::new;

        GrpcSender sender = new StaffixGrpcSenderProvider(factory).createSender(config);

        assertThat(System.getProperty(StaffixGrpcSenderProvider.FACTORY_PROPERTY)).isNull();
        sender.shutdown();
    }

    /**
     * {@code null} means "behave as the ServiceLoader path does" - including the refusal that makes a
     * missing client a startup failure rather than silently dropped spans.
     */
    @Test
    void aNullFactoryStillRefusesWhenNoClientIsNamed() {
        StaffixGrpcSenderProvider provider = new StaffixGrpcSenderProvider(null);

        assertThatThrownBy(() -> provider.createSender(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(StaffixGrpcSenderProvider.FACTORY_PROPERTY);
    }

    @Test
    void bindsTheExportersDestinationAndMethodOntoOurSettings() {
        System.setProperty(StaffixGrpcSenderProvider.FACTORY_PROPERTY, RecordingFactory.class.getName());
        config.connectTimeout = Duration.ofMillis(250);
        config.timeout = Duration.ofSeconds(3);

        new StaffixGrpcSenderProvider().createSender(config).shutdown();

        GrpcSenderSettings settings = RecordingFactory.bound.get(0);
        assertThat(settings.getEndpointUrl()).isEqualTo("http://127.0.0.1:4317");
        assertThat(settings.getFullMethodName())
                .isEqualTo("/opentelemetry.proto.collector.trace.v1.TraceService/Export");
        assertThat(settings.getConnectTimeout()).isEqualTo(Duration.ofMillis(250));
        assertThat(settings.getRequestTimeout())
                .as("OTel's timeout is per attempt, which is what ours means")
                .isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.getCompression()).isEqualTo(GrpcSenderSettings.GrpcCompression.NONE);
    }

    @Test
    void doesNotBindThePerRequestHeadersToTheSender() {
        System.setProperty(StaffixGrpcSenderProvider.FACTORY_PROPERTY, RecordingFactory.class.getName());
        config.headersSupplier = () -> TestGrpcSenderConfig.headers("Authorization", "Basic abc");

        new StaffixGrpcSenderProvider().createSender(config).shutdown();

        assertThat(RecordingFactory.bound.get(0).getHeaders())
                .as("OTel rebuilds its headers per export by contract, so they travel with the call")
                .isEmpty();
    }

    @Test
    void refusesAFactoryClassThatIsNotOnTheClasspath() {
        System.setProperty(StaffixGrpcSenderProvider.FACTORY_PROPERTY, "com.example.NoSuchFactory");

        assertThatThrownBy(() -> new StaffixGrpcSenderProvider().createSender(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(StaffixGrpcSenderProvider.FACTORY_PROPERTY)
                .hasMessageContaining("com.example.NoSuchFactory");
    }

    @Test
    void refusesAFactoryClassThatIsNotASenderFactory() {
        System.setProperty(StaffixGrpcSenderProvider.FACTORY_PROPERTY, String.class.getName());

        assertThatThrownBy(() -> new StaffixGrpcSenderProvider().createSender(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Function");
    }

    /**
     * A real client module, named the way a deployment would name one. Proves the documented contract is
     * satisfiable by something that actually exists, not only by a test double.
     */
    @Test
    void acceptsAFactoryBackedByARealClientModule() {
        System.setProperty(StaffixGrpcSenderProvider.FACTORY_PROPERTY, OkHttpFactory.class.getName());

        GrpcSender sender = new StaffixGrpcSenderProvider().createSender(config);

        assertThat(sender).isNotNull();
        sender.shutdown();
    }

    /**
     * Loaded by name, so public with a public no-argument constructor - the contract the property
     * documents.
     */
    public static final class RecordingFactory
            implements Function<GrpcSenderSettings, org.lolaf.staffix.api.grpc.GrpcSender> {

        static final List<GrpcSenderSettings> bound = new ArrayList<>();

        @Override
        public org.lolaf.staffix.api.grpc.GrpcSender apply(GrpcSenderSettings settings) {
            bound.add(settings);
            return new NoopGrpcSender();
        }
    }

    /**
     * @see RecordingFactory
     */
    public static final class OkHttpFactory
            implements Function<GrpcSenderSettings, org.lolaf.staffix.api.grpc.GrpcSender> {

        @Override
        public org.lolaf.staffix.api.grpc.GrpcSender apply(GrpcSenderSettings settings) {
            return new OkHttpGrpcSender(settings);
        }
    }

    private static final class NoopGrpcSender implements org.lolaf.staffix.api.grpc.GrpcSender {

        @Override
        public GrpcReply send(byte[] data, int offset, int length, Header[] extraHeaders, GrpcReply into) {
            return into;
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
