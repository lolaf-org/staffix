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
package org.lolaf.staffix.admin.http;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Value;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.logging.LogObfuscator;
import org.lolaf.staffix.api.monitoring.FixSessionMonitoringManagerSettings;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComponentsJsonTest {

    private final AdminApi adminApi = mock(AdminApi.class);

    private JsonNode emitters(List<FixMessagesLoggerSettings> loggers, List<FixSessionsPluginSettings<?>> plugins) {
        when(adminApi.getFixMessagesLoggersSettings()).thenReturn(loggers);
        when(adminApi.getFixSessionsPluginsSettings()).thenReturn(plugins);
        return ComponentsJson.of(adminApi);
    }

    @Test
    void describesEachLoggerWithItsTypeAndPlainSettingsIncludingWhatItWraps() {
        JsonNode logger = emitters(List.of(new AlphaAsyncLoggerSettings("clients", new AlphaOtlpLoggerSettings("default", "http://collector:4318"))), List.of())
                .get("messagesLoggers").get(0);

        assertThat(logger.get("type").asText()).isEqualTo("ComponentsJsonTest$AlphaAsyncLoggerSettings");
        assertThat(logger.get("instanceId").asText()).isEqualTo("clients");
        assertThat(logger.get("wrappedLoggerSettings").get("type").asText()).isEqualTo("ComponentsJsonTest$AlphaOtlpLoggerSettings");
        assertThat(logger.get("wrappedLoggerSettings").get("otlpEndpointUrl").asText()).isEqualTo("http://collector:4318");
        assertThat(logger.get("wrappedLoggerSettings").get("flushDelay").asText()).isEqualTo("PT1S");
        assertThat(logger.get("wrappedLoggerSettings").has("flushingExecutor")).isFalse();
        assertThat(logger.has("logObfuscators")).isFalse();
    }

    @Test
    void describesEveryPluginWithThePluginTypesItServesAndMasksCredentials() {
        JsonNode plugins = emitters(List.of(), List.of(new AlphaMonitoringSettings(), new AlphaThrottlingSettings(),
                new AlphaWrapperSettings(new AlphaMonitoringSettings()))).get("sessionsPlugins");

        assertThat(plugins).hasSize(3);
        assertThat(plugins.get(0).get("pluginTypes").toString()).isEqualTo("[\"FixSessionsMonitoringManager\"]");
        assertThat(plugins.get(1).get("pluginTypes").toString()).isEqualTo("[\"FixSessionsPlugin\"]");
        assertThat(plugins.get(2).get("pluginTypes").toString()).isEqualTo("[\"FixSessionsPlugin\",\"FixSessionsMonitoringManager\"]");
        assertThat(plugins.get(2).get("delegateSettings").get("type").asText()).isEqualTo("ComponentsJsonTest$AlphaMonitoringSettings");
        JsonNode monitoring = plugins.get(0);
        assertThat(monitoring.get("baseTimeUnit").asText()).isEqualTo("MICROSECONDS");
        assertThat(monitoring.get("decodingLatencyEnabled").asBoolean()).isTrue();
        assertThat(monitoring.get("headers").get("Authorization").asText()).isEqualTo(SessionSettingsDocuments.MASK);
        assertThat(monitoring.get("apiToken").asText()).isEqualTo(SessionSettingsDocuments.MASK);
        assertThat(monitoring.has("registrySupplier")).isFalse();
    }

    @Value
    static class AlphaOtlpLoggerSettings implements FixMessagesLoggerSettings {
        String instanceId;
        String otlpEndpointUrl;
        Duration flushDelay = Duration.ofSeconds(1);
        ScheduledExecutorService flushingExecutor = Executors.newSingleThreadScheduledExecutor();

        @Override
        public List<LogObfuscator> getLogObfuscators() {
            return List.of();
        }

        @Override
        public BiPredicate<MessageType, FixMessagesLogger.LogEventType> getMessageFilter() {
            return (type, event) -> false;
        }
    }

    @Value
    static class AlphaAsyncLoggerSettings implements FixMessagesLoggerSettings {
        String instanceId;
        FixMessagesLoggerSettings wrappedLoggerSettings;

        @Override
        public List<LogObfuscator> getLogObfuscators() {
            return List.of();
        }

        @Override
        public BiPredicate<MessageType, FixMessagesLogger.LogEventType> getMessageFilter() {
            return (type, event) -> false;
        }
    }

    @Value
    static class AlphaMonitoringSettings implements FixSessionMonitoringManagerSettings {
        String instanceId = "metrics";
        TimeUnit baseTimeUnit = TimeUnit.MICROSECONDS;
        boolean decodingLatencyEnabled = true;
        Map<String, String> headers = Map.of("Authorization", "Bearer alpha");
        String apiToken = "alpha-token";
        Supplier<Object> registrySupplier = Object::new;
    }

    @Value
    static class AlphaThrottlingSettings implements FixSessionsPluginSettings<FixSessionsPlugin<?>> {
        String instanceId = "throttling";
    }

    @Value
    static class AlphaWrapperSettings implements FixSessionsPluginSettings<FixSessionsPlugin<?>> {
        String instanceId = "async-metrics";
        FixSessionsPluginSettings<?> delegateSettings;

        @Override
        public Set<Class<? extends FixSessionsPlugin<?>>> getPluginTypes() {
            Set<Class<? extends FixSessionsPlugin<?>>> types = new LinkedHashSet<>(FixSessionsPluginSettings.super.getPluginTypes());
            types.addAll(delegateSettings.getPluginTypes());
            return types;
        }
    }
}
