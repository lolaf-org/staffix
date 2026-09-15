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
package org.lolaf.staffix.spring.boot.spi;

import org.lolaf.staffix.api.session.FixSessionsSettingsStoreSettings;

import java.util.Map;

/**
 * SPI implemented by per-impl Spring Boot modules (e.g. {@code staffix-sessions-settings-store-memory-spring-boot}).
 *
 * <p>Each contributor mutates a shared {@code registry} (key -> {@link FixSessionsSettingsStoreSettings}).
 * Contributors should fail on collisions on the same key.
 */
public interface FixSessionsSettingsStoreSettingsContributor {

    default int order() {
        return 0;
    }

    void contribute(Map<String, FixSessionsSettingsStoreSettings> registry);
}
