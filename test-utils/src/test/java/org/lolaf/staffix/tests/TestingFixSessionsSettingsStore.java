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

    public TestingFixSessionsSettingsStore(TestingFixSessionsSettingsStoreSettings settings) {
        this.settings = settings;
        settings.getSessions().forEach(this::hold);
    }

    @Override
    public String getInstanceId() {
        return settings.getInstanceId();
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
    protected Set<FixSessionSettings> onLoad() {
        // no external backing source; the currently held settings are returned
        return new HashSet<>(getSettings());
    }

    @Override
    public boolean isPersistent() {
        return false;
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
