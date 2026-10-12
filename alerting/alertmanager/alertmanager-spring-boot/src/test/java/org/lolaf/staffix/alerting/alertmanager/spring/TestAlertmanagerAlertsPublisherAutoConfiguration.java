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
package org.lolaf.staffix.alerting.alertmanager.spring;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.alerting.alertmanager.AlertmanagerAlertsPublisherSettings;
import org.lolaf.staffix.api.alerting.FixAlertsPublisherSettings;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAlertmanagerAlertsPublisherAutoConfiguration {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AlertmanagerAlertsPublisherAutoConfiguration.class));

    @Test
    void bindsAnInstanceFromProperties() {
        runner.withPropertyValues(
                        "staffix.alerts-publishers-alertmanager.instances.ALERTS.urls[0]=http://am-1:9093",
                        "staffix.alerts-publishers-alertmanager.instances.ALERTS.urls[1]=http://am-2:9093",
                        "staffix.alerts-publishers-alertmanager.instances.ALERTS.resend-interval=30s",
                        "staffix.alerts-publishers-alertmanager.instances.ALERTS.static-labels.env=prod",
                        "staffix.alerts-publishers-alertmanager.instances.ALERTS.headers.Authorization=Bearer token",
                        "staffix.alerts-publishers-alertmanager.instances.ALERTS.request-timeout=3s")
                .run(context -> {
                    Map<String, FixAlertsPublisherSettings> registry = new HashMap<>();
                    context.getBean(AlertmanagerAlertsPublisherContributor.class).contribute(registry);

                    assertThat(registry).containsOnlyKeys("ALERTS");
                    AlertmanagerAlertsPublisherSettings settings = (AlertmanagerAlertsPublisherSettings) registry.get("ALERTS");
                    assertThat(settings.getInstanceId()).isEqualTo("ALERTS");
                    assertThat(settings.getAlertmanagerUrls()).containsExactly(URI.create("http://am-1:9093"), URI.create("http://am-2:9093"));
                    assertThat(settings.getResendInterval()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(settings.getStaticLabels()).containsExactly(Map.entry("env", "prod"));
                    assertThat(settings.getHttpSenderSettings().getHeaders()).containsExactly(Map.entry("Authorization", "Bearer token"));
                    assertThat(settings.getHttpSenderSettings().getRequestTimeout()).isEqualTo(Duration.ofSeconds(3));
                });
    }

    @Test
    void refusesAnInstanceWithoutUrl() {
        runner.withPropertyValues("staffix.alerts-publishers-alertmanager.instances.ALERTS.resend-interval=30s")
                .run(context -> assertThatThrownBy(() -> context.getBean(AlertmanagerAlertsPublisherContributor.class).contribute(new HashMap<>()))
                        .hasMessageContaining("staffix.alerts-publishers-alertmanager.instances.ALERTS.urls"));
    }
}
