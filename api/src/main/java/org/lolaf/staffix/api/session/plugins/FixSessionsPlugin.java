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
package org.lolaf.staffix.api.session.plugins;

import org.lolaf.staffix.api.InstanceIdSupplier;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;

import java.util.Collection;
import java.util.Optional;

/**
 * Observes a session without being part of it - metrics, tracing, throttling, the Actuator endpoints.
 *
 * <p>Callbacks run on the session's own thread, on the message path, so a plugin that does real work belongs
 * behind the async wrapper rather than here. The context type parameter is what a plugin carries per session.
 */
public interface FixSessionsPlugin<C extends PluginContext> extends InstanceIdSupplier {

    /**
     * Return an instance of a FixSessionPlugin or Optional.empty if the plugin implementation is not interested
     * in listening to plugin events for the provided session, the implementation MUST return a dedicated instance of
     * the plugin for the provided session. Shared instances for multiple session are not supported.
     *
     * @param fixInstanceId        the fix instance id
     * @param fixSession           the fix session
     * @param incomingMessageTypes the expected fix session incoming message types
     * @param outgoingMessageTypes the expected fix session outgoing message types
     * @return the instance or null
     */
    Optional<? extends FixSessionPlugin<C, ?>> onSessionCreated(String fixInstanceId, FixSession fixSession,
                                                                Collection<MessageType> incomingMessageTypes, Collection<MessageType> outgoingMessageTypes);

    /**
     * Indicates if a plugin matches a generic plugin interface
     *
     * @param pluginClass the plugin class
     * @return true if the plugin implementation is compatible with the provided plugin class
     */
    default boolean matchesPluginClass(Class<? extends FixSessionsPlugin<?>> pluginClass) {
        return this.getClass().equals(pluginClass);
    }

}