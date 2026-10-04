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
package org.lolaf.staffix.admin.http.spring;

import org.lolaf.staffix.admin.http.HttpAdminApiSettings;
import org.lolaf.staffix.api.admin.AdminApiExporterSettings;
import org.lolaf.staffix.spring.boot.spi.AdminApiExporterSettingsContributor;
import org.springframework.boot.ssl.SslBundles;

import java.util.function.Consumer;

/**
 * Contributes the HTTP admin API's settings to the engine being built, so a session can name it in
 * configuration rather than the application wiring it.
 */
public class HttpAdminApiContributor implements AdminApiExporterSettingsContributor {

    private final HttpAdminApiProps props;
    private final SslBundles sslBundles;

    /**
     * @param sslBundles resolves {@link HttpAdminApiProps#getSslBundle()}; may be null when none is set
     */
    public HttpAdminApiContributor(HttpAdminApiProps props, SslBundles sslBundles) {
        this.props = props;
        this.sslBundles = sslBundles;
    }

    @Override
    public void contribute(Consumer<AdminApiExporterSettings> sink) {
        sink.accept(HttpAdminApiSettings.builder()
                .bindAddress(props.getBindAddress())
                .port(props.getPort())
                .sslContext(props.getSslBundle() != null
                        ? sslBundles.getBundle(props.getSslBundle()).createSslContext()
                        : null)
                .apiToken(props.getApiToken())
                .readOnlyApiToken(props.getReadOnlyApiToken())
                .advertisedUrl(props.getAdvertisedUrl())
                .announceUrl(props.getAnnounceUrl())
                .announceUsername(props.getAnnounceUsername())
                .announcePassword(props.getAnnouncePassword())
                .announceInterval(props.getAnnounceInterval())
                .build());
    }
}
