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

package org.lolaf.staffix.api.alerting;

import org.lolaf.staffix.api.Factory;
import org.lolaf.staffix.api.InstanceProvider;

/**
 * Settings for an alerts publisher, resolved through the {@link Factory} SPI.
 */
public interface FixAlertsPublisherSettings extends InstanceProvider<FixAlertsPublisher> {

    @Override
    @SuppressWarnings("unchecked")
    default FixAlertsPublisher instance() {
        return (FixAlertsPublisher) InstanceProvider.getSpiInstance(this, FixAlertsPublisherFactory.class);
    }

    /**
     * The service provider that builds an alerts publisher from its settings class; see {@link Factory}.
     *
     * @param <S> the settings class it serves
     */
    interface FixAlertsPublisherFactory<S> extends Factory<FixAlertsPublisher, S> {

    }
}
