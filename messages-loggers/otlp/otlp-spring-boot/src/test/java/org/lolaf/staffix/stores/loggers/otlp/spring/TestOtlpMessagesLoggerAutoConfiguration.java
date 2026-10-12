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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestOtlpMessagesLoggerAutoConfiguration {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OtlpMessagesLoggerAutoConfiguration.class));

    @Test
    void bindsTheResourceCheckIntervalAndTheAlertClearDelay() {
        runner.withPropertyValues("staffix.messages-loggers-otlp.instances.LOGS.otlp-endpoint-url=http://localhost:4318",
                        "staffix.messages-loggers-otlp.instances.LOGS.resource-check-interval=2s",
                        "staffix.messages-loggers-otlp.instances.LOGS.alert-clear-delay=1m")
                .run(context -> {
                    FixMessagesLoggerSettings settings = contribute(context.getBean(OtlpMessagesLoggerContributor.class));

                    assertThat(settings.getResourceCheckInterval()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(settings.getAlertClearDelay()).isEqualTo(Duration.ofMinutes(1));
                });
    }

    @Test
    void defaultsTheResourceCheckIntervalAndTheAlertClearDelay() {
        runner.withPropertyValues("staffix.messages-loggers-otlp.instances.LOGS.otlp-endpoint-url=http://localhost:4318")
                .run(context -> {
                    FixMessagesLoggerSettings settings = contribute(context.getBean(OtlpMessagesLoggerContributor.class));

                    assertThat(settings.getResourceCheckInterval()).isEqualTo(FixMessagesLoggerSettings.DEFAULT_RESOURCE_CHECK_INTERVAL);
                    assertThat(settings.getAlertClearDelay()).isEqualTo(FixMessagesLoggerSettings.DEFAULT_ALERT_CLEAR_DELAY);
                });
    }

    private static FixMessagesLoggerSettings contribute(OtlpMessagesLoggerContributor contributor) {
        Map<String, FixMessagesLoggerSettings> registry = new HashMap<>();
        contributor.contribute(registry);
        return registry.get("LOGS");
    }
}
