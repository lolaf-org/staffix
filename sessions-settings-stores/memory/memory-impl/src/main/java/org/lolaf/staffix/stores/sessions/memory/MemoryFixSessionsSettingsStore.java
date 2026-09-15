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

import lombok.Getter;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.session.*;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Holds session settings built in code, for an application that configures its sessions programmatically.
 */
public class MemoryFixSessionsSettingsStore extends FixSessionsSettingsStore.AbstractFixSessionSettingsStore {

    @Getter
    private final String instanceId;
    private final List<FixSessionSettings> fixSessionSettings;
    private final BiFunction<FixSessionSettings,
            FixSessionSettings.FixSessionSettingsBuilder<?, ?>,
            FixSessionSettings.FixSessionSettingsBuilder<?, ?>> defaults;

    protected MemoryFixSessionsSettingsStore(MemorySessionsSettingsStoreSettings settings) {
        this.defaults = settings.getDefaultFixSessionSettings();
        this.fixSessionSettings = settings.getFixSessionSettings().stream()
                .map(this::withDefaults)
                .collect(Collectors.toList());
        this.instanceId = settings.getInstanceId();
    }

    private FixSessionSettings withDefaults(FixSessionSettings s) {
        return defaults.apply(s, s.toBuilder()).build();
    }

    @Override
    public Collection<FixSessionSettings> getSettings() {
        return fixSessionSettings;
    }

    @Override
    public Optional<FixSessionSettings> find(FixSessionId fixSessionId, FixSession.FixSessionType fixSessionType) {
        return fixSessionSettings.stream()
                .filter(s -> s.getFixSessionId().equals(fixSessionId) && s.getFixSessionType().equals(fixSessionType))
                .findFirst();
    }

    @Override
    protected void startMe() throws StartStopException {
        // noting to do
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        // noting to do
    }

    @Override
    public Set<FixSessionSettings> load() {
        // in-memory settings have no external backing source to re-read; the currently held settings are returned
        return new HashSet<>(fixSessionSettings);
    }

    @Override
    public void onAdd(FixSessionSettings settings) {
        fixSessionSettings.add(withDefaults(settings));
    }

    @Override
    public void onRemove(FixSessionSettings settings) {
        fixSessionSettings.remove(settings);
    }

    @Override
    public void onUpdate(FixSessionSettings settings) {
        fixSessionSettings.removeIf(s -> s.getFixSessionId().equals(settings.getFixSessionId()));
        fixSessionSettings.add(withDefaults(settings));
    }

    public static class MemoryStoreFactoryImpl implements FixSessionsSettingsStoreSettings.FixSessionsStoreFactory<MemorySessionsSettingsStoreSettings> {

        @Override
        public FixSessionsSettingsStore newInstance(MemorySessionsSettingsStoreSettings settings) {
            return new MemoryFixSessionsSettingsStore(settings);
        }

        @Override
        public Class<MemorySessionsSettingsStoreSettings> getSettingsClass() {
            return MemorySessionsSettingsStoreSettings.class;
        }
    }
}