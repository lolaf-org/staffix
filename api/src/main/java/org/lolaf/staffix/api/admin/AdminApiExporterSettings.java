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
package org.lolaf.staffix.api.admin;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.lolaf.staffix.api.InstanceProvider;

/**
 * Settings for an admin API, resolved through the {@link org.lolaf.staffix.api.Factory} SPI - JMX is the shipped
 * implementation.
 */
@Getter
@SuperBuilder(toBuilder = true)
public class AdminApiExporterSettings implements InstanceProvider<AdminApiExporter> {

    /**
     * Names this instance, so a session can select it by id when more than one is configured.
     */
    @Builder.Default
    private final String instanceId = DEFAULT_INSTANCE_ID;

    @SuppressWarnings("unchecked")
    @Override
    public AdminApiExporter instance() {
        return (AdminApiExporter) InstanceProvider.getSpiInstance(this, AdminApiExporter.AdminApiExporterFactory.class);
    }
}
