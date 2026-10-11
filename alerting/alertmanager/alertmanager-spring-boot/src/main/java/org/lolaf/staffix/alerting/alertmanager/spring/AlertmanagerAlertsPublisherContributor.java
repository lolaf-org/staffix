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

import org.lolaf.staffix.alerting.alertmanager.AlertmanagerAlertsPublisherSettings;
import org.lolaf.staffix.api.alerting.FixAlertsPublisherSettings;
import org.lolaf.staffix.api.http.HttpSender;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.spring.boot.spi.BeanRef;
import org.lolaf.staffix.spring.boot.spi.FixAlertsPublisherSettingsContributor;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.ApplicationContext;

import java.net.Authenticator;
import java.net.ProxySelector;
import java.util.Map;
import java.util.function.Function;

/**
 * Contributes the Alertmanager alerts publisher's settings to the engine being built, so a session can name it in
 * configuration rather than the application wiring it.
 */
public class AlertmanagerAlertsPublisherContributor implements FixAlertsPublisherSettingsContributor {

    private static final String PREFIX = "staffix.alerts-publishers-alertmanager.instances.";

    private final AlertmanagerAlertsPublisherProps props;
    private final ApplicationContext ctx;
    private final SslBundles sslBundles;

    /**
     * @param sslBundles resolves the instances' SSL bundles; may be null when none is set
     */
    public AlertmanagerAlertsPublisherContributor(AlertmanagerAlertsPublisherProps props, ApplicationContext ctx, SslBundles sslBundles) {
        this.props = props;
        this.ctx = ctx;
        this.sslBundles = sslBundles;
    }

    @Override
    public void contribute(Map<String, FixAlertsPublisherSettings> registry) {
        props.getInstances().forEach((key, p) -> {
            if (registry.putIfAbsent(key, build(key, p)) != null) {
                throw new IllegalStateException(PREFIX + key + " collides with another alerts publisher contributor for the same key");
            }
        });
    }

    private FixAlertsPublisherSettings build(String mapKey, AlertmanagerAlertsPublisherEntryProps p) {
        String path = PREFIX + mapKey;
        if (p.getUrls().isEmpty()) {
            throw new IllegalStateException(path + ".urls must name at least one Alertmanager");
        }
        AlertmanagerAlertsPublisherSettings.AlertmanagerAlertsPublisherSettingsBuilder<?, ?> b = AlertmanagerAlertsPublisherSettings.builder()
                .instanceId(mapKey)
                .alertmanagerUrls(p.getUrls())
                .staticLabels(p.getStaticLabels())
                .httpSenderSettings(httpSenderSettings(p, path));
        if (p.getResendInterval() != null) b.resendInterval(p.getResendInterval());
        BeanRef.<Function<HttpSenderSettings, HttpSender>>resolveOptional(ctx, p.getHttpSenderFactoryBean(), Function.class, path + ".http-sender-factory-bean")
                .ifPresent(b::httpSenderFactory);
        return b.build();
    }

    private HttpSenderSettings httpSenderSettings(AlertmanagerAlertsPublisherEntryProps p, String path) {
        HttpSenderSettings.HttpSenderSettingsBuilder http = HttpSenderSettings.builder().headers(p.getHeaders());
        if (p.getConnectTimeout() != null) http.connectTimeout(p.getConnectTimeout());
        if (p.getRequestTimeout() != null) http.requestTimeout(p.getRequestTimeout());
        if (p.getSslBundle() != null) {
            if (sslBundles == null) {
                throw new IllegalStateException(path + ".ssl-bundle is set but no SSL bundles are configured");
            }
            http.sslContext(sslBundles.getBundle(p.getSslBundle()).createSslContext());
        }
        BeanRef.<ProxySelector>resolveOptional(ctx, p.getProxySelectorBean(), ProxySelector.class, path + ".proxy-selector-bean")
                .ifPresent(http::proxySelector);
        BeanRef.<Authenticator>resolveOptional(ctx, p.getAuthenticatorBean(), Authenticator.class, path + ".authenticator-bean")
                .ifPresent(http::authenticator);
        return http.build();
    }
}
