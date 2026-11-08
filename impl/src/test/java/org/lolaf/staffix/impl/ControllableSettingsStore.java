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
package org.lolaf.staffix.impl;

import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;
import org.lolaf.staffix.api.session.FixSessionsSettingsStoreSettings;

import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link FixSessionsSettingsStore} whose {@link #load()} result is fully controlled by the test, so the engine's
 * reconciliation against the managed settings can be exercised without a real backing source.
 */
class ControllableSettingsStore extends FixSessionsSettingsStore.AbstractFixSessionSettingsStore {

    private final String instanceId;
    private Set<FixSessionSettings> source = new HashSet<>();

    ControllableSettingsStore(String instanceId) {
        this.instanceId = instanceId;
    }

    String boundEngineId() {
        return getEngineId();
    }

    void setSource(FixSessionSettings... settings) {
        this.source = new HashSet<>(Set.of(settings));
    }

    @Override
    public String getInstanceId() {
        return instanceId;
    }

    @Override
    protected Set<FixSessionSettings> onLoad() {
        return new HashSet<>(source);
    }

    @Override
    public boolean isPersistent() {
        return false;
    }

    @Override
    protected FixSessionSettings onAdd(FixSessionSettings settings) {
        return settings;
    }

    @Override
    protected void onRemove(FixSessionSettings settings) {
        // nothing but the managed settings
    }

    @Override
    protected FixSessionSettings onUpdate(FixSessionSettings settings) {
        return settings;
    }

    @Override
    protected void startMe() {
        // nothing to do
    }

    @Override
    protected void stopMe(Deadline stopDeadline) {
        // nothing to do
    }

    static class Settings implements FixSessionsSettingsStoreSettings {

        private final ControllableSettingsStore store;

        Settings(ControllableSettingsStore store) {
            this.store = store;
        }

        @Override
        public String getInstanceId() {
            return store.getInstanceId();
        }

        @Override
        public FixSessionsSettingsStore instance() {
            return store;
        }
    }
}
