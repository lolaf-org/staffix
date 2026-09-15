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

import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringContext;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.PluginContext;
import org.lolaf.staffix.api.time.UTCTime;

import java.util.Optional;
import java.util.function.BiConsumer;

final class ActuatorSessionPlugin implements FixSessionPlugin<PluginContext.VoidPluginContext, Void> {

    private final ActuatorSessionStats stats;
    private final BiConsumer<String, FixSessionId> onSessionDestroyed;

    ActuatorSessionPlugin(ActuatorSessionStats stats,
                          BiConsumer<String, FixSessionId> onSessionDestroyed) {
        this.stats = stats;
        this.onSessionDestroyed = onSessionDestroyed;
    }

    @Override
    public boolean isForPluginContext(Class<? extends PluginContext> pluginClass) {
        return pluginClass.equals(FixSessionsMonitoringContext.class);
    }

    @Override
    public Optional<PluginContext.VoidPluginContext> getPluginContext() {
        return Optional.empty();
    }

    @Override
    public void onLogon() {
        stats.onLogon();
    }

    @Override
    public void onLogout() {
        stats.onLogout();
    }

    @Override
    public void onMessageReceived(MessageType messageType, int payloadSize, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        stats.onMessageReceived(payloadSize, localReceiveTime);
    }

    @Override
    public void onMessageSent(MessageType messageType, int payloadSize, long localSendingTimeInNanos, UTCTime localSendingTime) {
        stats.onMessageSent(payloadSize, localSendingTime);
    }

    @Override
    public void onSessionDestroyed(String fixInstanceId, FixSessionId fixSessionId) {
        onSessionDestroyed.accept(fixInstanceId, fixSessionId);
    }
}
