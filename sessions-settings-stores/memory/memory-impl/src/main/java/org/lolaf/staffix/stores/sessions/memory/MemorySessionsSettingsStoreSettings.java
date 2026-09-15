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
package org.lolaf.staffix.stores.sessions.memory;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStoreSettings;

import java.util.List;
import java.util.function.BiFunction;

/**
 * The sessions an in-memory settings store serves.
 */
@Getter
@Builder(toBuilder = true)
public class MemorySessionsSettingsStoreSettings implements FixSessionsSettingsStoreSettings {

    /**
     * Names this instance, so a session can select it by id when more than one is configured.
     */
    @Builder.Default
    private final String instanceId = DEFAULT_INSTANCE_ID;
    /**
     * Hook applied to every {@link FixSessionSettings} stored in this instance — both the ones declared via
     * {@link MemorySessionsSettingsStoreSettingsBuilder#fixSessionSetting} at construction time and the ones added at
     * runtime via {@link MemoryFixSessionsSettingsStore#onAdd}. The function receives the user-defined settings and a
     * builder pre-loaded with those settings (via {@code toBuilder()}) and returns the builder to be {@code build()}-ed.
     * Inspect the first arg to decide whether a default should be applied.
     * <p>
     * Note on collection/map fields: {@code @Singular} adders append to the user-defined entries; for maps with
     * colliding keys the last write wins, so default entries written here will override user-supplied ones unless the
     * function checks {@code existing} first.
     */
    @Builder.Default
    private final BiFunction<FixSessionSettings,
            FixSessionSettings.FixSessionSettingsBuilder<?, ?>,
            FixSessionSettings.FixSessionSettingsBuilder<?, ?>> defaultFixSessionSettings = (existing, b) -> b;
    /**
     * The sessions this store serves.
     */
    @Singular
    private List<FixSessionSettings> fixSessionSettings;
}