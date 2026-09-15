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
package org.lolaf.staffix.api.monitoring;

import org.lolaf.staffix.api.Factory;
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;

/**
 * Settings for a monitoring manager, resolved through the {@link org.lolaf.staffix.api.Factory} SPI - Micrometer
 * and OTLP are the shipped implementations.
 */
public interface FixSessionMonitoringManagerSettings extends FixSessionsPluginSettings<FixSessionsMonitoringManager> {

    @Override
    default FixSessionsMonitoringManager instance() {
        return (FixSessionsMonitoringManager) InstanceProvider.getSpiInstance(this, FixSessionMonitoringManagerFactory.class);
    }


    interface FixSessionMonitoringManagerFactory<S> extends Factory<FixSessionsMonitoringManager, S> {

    }
}

