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
package org.lolaf.staffix.spring.boot.actuator;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;
import org.lolaf.staffix.api.session.plugins.PluginContext;

import java.util.Collection;
import java.util.Optional;

/**
 * FixSessionsPlugin to trap Fix ession events for actuator
 */
@Slf4j
public class ActuatorMonitoringManager extends Startable.SimpleStartable<ActuatorMonitoringManager>
        implements FixSessionsPlugin<PluginContext.VoidPluginContext> {

    private final ActuatorMonitoringManagerSettings settings;
    private final ActuatorSessionsRegistry registry;

    public ActuatorMonitoringManager(ActuatorMonitoringManagerSettings settings) {
        this.settings = settings;
        this.registry = settings.getRegistry();
        if (this.registry == null) {
            throw new IllegalArgumentException("ActuatorMonitoringManagerSettings.registry must not be null");
        }
    }

    @Override
    protected void startMe() throws StartStopException {
        log.debug("Actuator monitoring manager {} started", settings.getInstanceId());
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        registry.clear();
        log.debug("Actuator monitoring manager {} stopped", settings.getInstanceId());
    }

    @Override
    public Optional<FixSessionPlugin<PluginContext.VoidPluginContext, Void>> onSessionCreated(String fixInstanceId,
                                                                                              FixSession fixSession,
                                                                                              Collection<MessageType> incomingMessageTypes,
                                                                                              Collection<MessageType> outgoingMessageTypes) {
        return Optional.of(new ActuatorSessionPlugin(registry.register(fixInstanceId, fixSession), this::onSessionDestroyed));
    }

    @Override
    public String getInstanceId() {
        return settings.getInstanceId();
    }


    private void onSessionDestroyed(String fixInstanceId, FixSessionId fixSessionId) {
        registry.unregister(fixSessionId);
    }

    public static class ActuatorMonitoringManagerFactoryImpl
            implements FixSessionsPluginSettings.FixSessionsPluginFactory<ActuatorMonitoringManagerSettings> {

        @Override
        public Class<ActuatorMonitoringManagerSettings> getSettingsClass() {
            return ActuatorMonitoringManagerSettings.class;
        }

        @Override
        public ActuatorMonitoringManager newInstance(ActuatorMonitoringManagerSettings settings) {
            return new ActuatorMonitoringManager(settings);
        }
    }
}
