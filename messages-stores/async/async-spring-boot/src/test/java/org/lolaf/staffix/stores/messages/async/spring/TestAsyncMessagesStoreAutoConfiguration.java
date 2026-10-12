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
package org.lolaf.staffix.stores.messages.async.spring;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TestAsyncMessagesStoreAutoConfiguration {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AsyncMessagesStoreAutoConfiguration.class));

    @Test
    void bindsTheResourceCheckIntervalAndTheAlertClearDelay() {
        runner.withPropertyValues("staffix.messages-stores-async.instances.MAIN.wraps=MAIN",
                        "staffix.messages-stores-async.instances.MAIN.async-queue-directory=target/async-store",
                        "staffix.messages-stores-async.instances.MAIN.resource-check-interval=2s",
                        "staffix.messages-stores-async.instances.MAIN.alert-clear-delay=1m")
                .run(context -> {
                    FixMessagesStoreSettings settings = contribute(context.getBean(AsyncMessagesStoreContributor.class));

                    assertThat(settings.getResourceCheckInterval()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(settings.getAlertClearDelay()).isEqualTo(Duration.ofMinutes(1));
                });
    }

    @Test
    void defaultsTheResourceCheckIntervalAndTheAlertClearDelay() {
        runner.withPropertyValues("staffix.messages-stores-async.instances.MAIN.wraps=MAIN",
                        "staffix.messages-stores-async.instances.MAIN.async-queue-directory=target/async-store")
                .run(context -> {
                    FixMessagesStoreSettings settings = contribute(context.getBean(AsyncMessagesStoreContributor.class));

                    assertThat(settings.getResourceCheckInterval()).isEqualTo(FixMessagesStoreSettings.DEFAULT_RESOURCE_CHECK_INTERVAL);
                    assertThat(settings.getAlertClearDelay()).isEqualTo(FixMessagesStoreSettings.DEFAULT_ALERT_CLEAR_DELAY);
                });
    }

    private static FixMessagesStoreSettings contribute(AsyncMessagesStoreContributor contributor) {
        Map<String, FixMessagesStoreSettings> registry = new HashMap<>();
        registry.put("MAIN", mock(FixMessagesStoreSettings.class));
        contributor.contribute(registry);
        return registry.get("MAIN");
    }
}
