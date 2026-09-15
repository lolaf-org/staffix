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
package org.lolaf.staffix.api.session;

import org.lolaf.staffix.api.Factory;
import org.lolaf.staffix.api.InstanceProvider;

/**
 * Settings for a store of session configurations - the thing that tells an engine which sessions exist.
 *
 * <p>Implemented by each store module (in-memory, YAML file) rather than here, and resolved through the
 * {@link org.lolaf.staffix.api.Factory} SPI, so the engine names no store implementation at compile time.
 */
public interface FixSessionsSettingsStoreSettings extends InstanceProvider<FixSessionsSettingsStore> {

    @Override
    default FixSessionsSettingsStore instance() {
        return (FixSessionsSettingsStore) InstanceProvider.getSpiInstance(this, FixSessionsStoreFactory.class);
    }


    interface FixSessionsStoreFactory<S> extends Factory<FixSessionsSettingsStore, S> {

    }
}