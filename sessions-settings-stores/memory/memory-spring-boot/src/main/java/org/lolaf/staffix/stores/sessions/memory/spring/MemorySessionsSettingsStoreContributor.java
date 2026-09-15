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
package org.lolaf.staffix.stores.sessions.memory.spring;

import org.lolaf.staffix.api.session.FixSessionsSettingsStoreSettings;
import org.lolaf.staffix.spring.boot.spi.FixSessionsSettingsStoreSettingsContributor;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;

import java.util.Map;

/**
 * Contributes the in-memory session settings store's settings to the engine being built, so a session can name it in
 * configuration rather than the application wiring it.
 */
public class MemorySessionsSettingsStoreContributor implements FixSessionsSettingsStoreSettingsContributor {

    private final MemorySessionsSettingsStoreProps props;

    public MemorySessionsSettingsStoreContributor(MemorySessionsSettingsStoreProps props) {
        this.props = props;
    }

    @Override
    public void contribute(Map<String, FixSessionsSettingsStoreSettings> registry) {
        props.getInstances().forEach((mapKey, ip) -> {
            MemorySessionsSettingsStoreSettings.MemorySessionsSettingsStoreSettingsBuilder builder =
                    MemorySessionsSettingsStoreSettings.builder().instanceId(mapKey);
            if (ip.getDefaults() != null) {
                builder.defaultFixSessionSettings(
                        (existing, b) -> FixSessionSettingsMapper.applyTo(ip.getDefaults(), b));
            }
            for (FixSessionSettingsProps s : ip.getSessions()) {
                builder.fixSessionSetting(FixSessionSettingsMapper.toSettings(s));
            }
            if (registry.putIfAbsent(mapKey, builder.build()) != null) {
                throw new IllegalStateException("staffix.sessions-settings-stores-memory.instances." + mapKey
                        + " collides with another sessions-settings-store contributor for the same key");
            }
        });
    }
}
