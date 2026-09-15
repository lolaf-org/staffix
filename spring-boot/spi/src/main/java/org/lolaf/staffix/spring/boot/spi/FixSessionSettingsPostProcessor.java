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

import org.lolaf.staffix.api.session.FixSessionSettings;

/**
 * SPI implemented by Spring Boot modules that need to amend every {@link FixSessionSettings}
 * loaded from any {@code FixSessionsSettingsStore} before the engine is built (and for sessions
 * added at runtime).
 *
 * <p>Typical use: a session-attached plugin (e.g. actuator monitoring) auto-injects its
 * {@code fixSessionPluginsInstanceId} entry so users do not have to repeat it in every session
 * config. Implementations should be idempotent and skip work when the user has already mapped
 * the relevant plugin class — that gives users a per-session opt-out.
 *
 * <p>Lower {@link #order()} runs first; auto-injectors that should yield to user-supplied
 * post-processors should return a higher value.
 */
public interface FixSessionSettingsPostProcessor {

    default int order() {
        return 0;
    }

    FixSessionSettings postProcess(FixSessionSettings settings);
}
