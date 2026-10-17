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
package org.lolaf.staffix.api.application;

import org.lolaf.staffix.api.Factory;
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.metadata.ComponentKind;
import org.lolaf.staffix.api.metadata.SelectedBy;

/**
 * Settings for an application factory, resolved through the {@link org.lolaf.staffix.api.Factory} SPI - the
 * plain and Spring factories are the two shipped implementations.
 */
@ComponentKind(value = "application-factory", cardinality = ComponentKind.Cardinality.AT_LEAST_ONE)
@SelectedBy(setting = "fixApplicationFactoryInstanceId", whenUnnamed = SelectedBy.WhenUnnamed.DEFAULT_INSTANCE,
        whenUnknown = SelectedBy.WhenUnknown.REFUSED)
public interface FixApplicationFactorySettings extends InstanceProvider<FixApplicationFactory> {

    @Override
    default FixApplicationFactory instance() {
        return (FixApplicationFactory) InstanceProvider.getSpiInstance(this, FixApplicationFactoryFactory.class);
    }

    /**
     * The service provider that builds an application factory from its settings class; see {@link Factory}.
     *
     * @param <S> the settings class it serves
     */
    interface FixApplicationFactoryFactory<S> extends Factory<FixApplicationFactory, S> {

    }
}