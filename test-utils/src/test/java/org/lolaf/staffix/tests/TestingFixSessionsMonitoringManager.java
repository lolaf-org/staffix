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
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.monitoring.FixSessionMonitoringManagerSettings;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringContext;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;

import java.util.Collection;
import java.util.Optional;

public class TestingFixSessionsMonitoringManager extends Startable.SimpleStartable<FixSessionsMonitoringManager> implements FixSessionsMonitoringManager {

    private final TestingFixSessionMonitoringManagerSettings fixSessionMonitoringManagerSettings;

    public TestingFixSessionsMonitoringManager(TestingFixSessionMonitoringManagerSettings fixSessionMonitoringManagerSettings) {
        this.fixSessionMonitoringManagerSettings = fixSessionMonitoringManagerSettings;
    }

    @Override
    public boolean matchesPluginClass(Class<? extends FixSessionsPlugin<?>> pluginClass) {
        return fixSessionMonitoringManagerSettings.getMock().matchesPluginClass(pluginClass);
    }

    @Override
    public Optional<? extends FixSessionPlugin<FixSessionsMonitoringContext, ?>> onSessionCreated(String fixInstanceId, FixSession fixSession, Collection<MessageType> incomingMessageTypes, Collection<MessageType> outgoingMessageTypes) {
        return fixSessionMonitoringManagerSettings.getMock().onSessionCreated(fixInstanceId, fixSession, incomingMessageTypes, outgoingMessageTypes);
    }

    @Override
    public String getInstanceId() {
        return fixSessionMonitoringManagerSettings.getInstanceId();
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        // nothing to do
    }

    @Override
    protected void startMe() throws StartStopException {
        // nothing to do
    }

    public static class TestingFixSessionMonitoringManagerFactory implements FixSessionMonitoringManagerSettings.FixSessionMonitoringManagerFactory<TestingFixSessionMonitoringManagerSettings> {

        @Override
        public Class<TestingFixSessionMonitoringManagerSettings> getSettingsClass() {
            return TestingFixSessionMonitoringManagerSettings.class;
        }

        @Override
        public FixSessionsMonitoringManager newInstance(TestingFixSessionMonitoringManagerSettings settings) {
            return new TestingFixSessionsMonitoringManager(settings);
        }
    }
}
