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
package org.lolaf.staffix.alerting.alertmanager;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.experimental.SuperBuilder;
import org.jspecify.annotations.NullMarked;
import org.lolaf.staffix.api.alerting.AbstractFixAlertsPublisherSettings;
import org.lolaf.staffix.api.http.HttpSender;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.http.jdk.JdkHttpSender;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Where and how often the ongoing alerts are posted. Each alert is posted with an {@code endsAt} of four resend
 * intervals ahead, so an engine that stops posting has its alerts resolved by Alertmanager on its own.
 */
@NullMarked
@Getter
@SuperBuilder(toBuilder = true)
public class AlertmanagerAlertsPublisherSettings extends AbstractFixAlertsPublisherSettings {

    /**
     * Base URLs, {@code /api/v2/alerts} is appended. Every alert is posted to each one, as Prometheus does for a
     * highly available Alertmanager.
     */
    @Singular
    private final List<URI> alertmanagerUrls;
    /**
     * How often every ongoing alert is posted again, one minute by default as in Prometheus. It also bounds how long an
     * alert outlives an engine that stopped posting: four intervals.
     */
    @Builder.Default
    private final Duration resendInterval = Duration.ofMinutes(1);
    /**
     * Added to every alert, for example the environment. A label of the alert itself wins over one of these.
     */
    @Singular
    private final Map<String, String> staticLabels;
    @Builder.Default
    private final Function<HttpSenderSettings, HttpSender> httpSenderFactory = JdkHttpSender::new;
    /**
     * Timeouts, TLS, proxy and headers such as an {@code Authorization}, shared by every Alertmanager. Its endpoint
     * and content type are ignored: each sender gets its Alertmanager's URL and JSON.
     */
    @Builder.Default
    private final HttpSenderSettings httpSenderSettings = HttpSenderSettings.builder().build();
}
