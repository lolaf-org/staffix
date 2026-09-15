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
package org.lolaf.staffix.tests;

import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.session.*;

import java.util.*;

public class TestingFixSessionsSettingsStore extends FixSessionsSettingsStore.AbstractFixSessionSettingsStore {

    private final TestingFixSessionsSettingsStoreSettings settings;
    private final List<FixSessionSettings> sessions;

    public TestingFixSessionsSettingsStore(TestingFixSessionsSettingsStoreSettings settings) {
        this.settings = settings;
        this.sessions = new ArrayList<>(settings.getSessions());
    }

    @Override
    public String getInstanceId() {
        return settings.getInstanceId();
    }

    @Override
    public Collection<FixSessionSettings> getSettings() {
        return sessions;
    }

    @Override
    public Optional<FixSessionSettings> find(FixSessionId fixSessionId, FixSession.FixSessionType fixSessionType) {
        return sessions.stream()
                .filter(s -> s.getFixSessionId().equals(fixSessionId) && s.getFixSessionType() == fixSessionType)
                .findFirst();
    }

    @Override
    public void onAdd(FixSessionSettings settings) {
        sessions.add(settings);
    }

    @Override
    public void onRemove(FixSessionSettings settings) {
        sessions.remove(settings);
    }

    @Override
    public void onUpdate(FixSessionSettings settings) {
        sessions.removeIf(s -> s.getFixSessionId().equals(settings.getFixSessionId()));
        sessions.add(settings);
    }

    @Override
    public Set<FixSessionSettings> load() {
        // no external backing source; the currently held settings are returned
        return new HashSet<>(sessions);
    }

    @Override
    protected void startMe() {
        // nothing to do
    }

    @Override
    protected void stopMe(Deadline stopDeadline) {
        // nothing to do
    }

    public static class TestingFixSessionsSettingsStoreFactoryImpl
            implements FixSessionsSettingsStoreSettings.FixSessionsStoreFactory<TestingFixSessionsSettingsStoreSettings> {

        @Override
        public FixSessionsSettingsStore newInstance(TestingFixSessionsSettingsStoreSettings settings) {
            return new TestingFixSessionsSettingsStore(settings);
        }

        @Override
        public Class<TestingFixSessionsSettingsStoreSettings> getSettingsClass() {
            return TestingFixSessionsSettingsStoreSettings.class;
        }
    }
}
