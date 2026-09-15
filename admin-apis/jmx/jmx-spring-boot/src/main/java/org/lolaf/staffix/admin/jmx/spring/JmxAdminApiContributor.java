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
package org.lolaf.staffix.admin.jmx.spring;

import org.lolaf.staffix.admin.jmx.JmxAdminApiSettings;
import org.lolaf.staffix.api.admin.AdminApiExporterSettings;
import org.lolaf.staffix.spring.boot.spi.AdminApiExporterSettingsContributor;

import java.util.function.Consumer;

/**
 * Contributes the JMX admin API's settings to the engine being built, so a session can name it in
 * configuration rather than the application wiring it.
 */
public class JmxAdminApiContributor implements AdminApiExporterSettingsContributor {

    private final JmxAdminApiProps props;

    public JmxAdminApiContributor(JmxAdminApiProps props) {
        this.props = props;
    }

    @Override
    public void contribute(Consumer<AdminApiExporterSettings> sink) {
        sink.accept(JmxAdminApiSettings.builder()
                .jmxDomain(props.getDomain())
                .build());
    }
}