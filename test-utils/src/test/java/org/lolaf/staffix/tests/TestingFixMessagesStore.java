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

import lombok.AllArgsConstructor;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.alerting.Probes;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;

@AllArgsConstructor
public class TestingFixMessagesStore extends Startable.SimpleStartable<FixMessagesStore> implements FixMessagesStore {

    private final TestingFixMessagesStoreSettings settings;

    @Override
    protected void startMe() {
        if (settings.getOnStart() != null) {
            settings.getOnStart().run();
        }
    }

    @Override
    protected void stopMe(Deadline stopDeadline) {
        if (settings.getOnStop() != null) {
            settings.getOnStop().run();
        }
    }

    @Override
    public FixSessionMessagesStore getStore(FixSessionId fixSessionId, Probes probes) {
        if (settings.getProbe() != null) {
            probes.add(settings.getProbe());
        }
        return settings.getTestingFixSessionMessagesStore();
    }

    @Override
    public String getInstanceId() {
        return settings.getInstanceId();
    }

    public static class TestingFixMessagesStoreFactoryImpl implements FixMessagesStoreSettings.FixMessagesStoreFactory<TestingFixMessagesStoreSettings> {

        @Override
        public FixMessagesStore newInstance(TestingFixMessagesStoreSettings settings) {
            return new TestingFixMessagesStore(settings);
        }

        @Override
        public Class<TestingFixMessagesStoreSettings> getSettingsClass() {
            return TestingFixMessagesStoreSettings.class;
        }
    }
}