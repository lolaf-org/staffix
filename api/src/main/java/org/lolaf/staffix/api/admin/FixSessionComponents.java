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
package org.lolaf.staffix.api.admin;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;

import java.util.List;
import java.util.Set;

/**
 * What a session's settings can name, by instance id, so an admin tool offers choices the engine can resolve: see
 * {@link FixSessionSettings#getFixApplicationFactoryInstanceId()}, {@link FixSessionSettings#getFixApplicationInstanceId()},
 * {@link FixSessionSettings#getFixMessageStoreInstanceId()}, {@link FixSessionSettings#getFixMessageLoggerInstanceId()}
 * and {@link FixSessionSettings#getFixSessionPluginsInstanceIds()}.
 */
@Value
@Builder
public class FixSessionComponents {

    @Singular
    List<ApplicationFactory> applicationFactories;
    @Singular
    List<String> messagesStores;
    @Singular
    List<String> messagesLoggers;
    @Singular
    List<SessionsPlugin> sessionsPlugins;

    @Value
    @Builder
    public static class ApplicationFactory {
        String instanceId;
        @Singular
        Set<String> applicationIds;
    }

    /**
     * {@code pluginTypes} are the keys a session's settings select this plugin under.
     */
    @Value
    @Builder
    public static class SessionsPlugin {
        String instanceId;
        @Singular
        Set<Class<? extends FixSessionsPlugin<?>>> pluginTypes;
    }
}
