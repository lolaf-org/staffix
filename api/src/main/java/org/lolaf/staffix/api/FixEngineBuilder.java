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
package org.lolaf.staffix.api;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.experimental.SuperBuilder;
import org.lolaf.staffix.api.admin.AdminApiExporterSettings;
import org.lolaf.staffix.api.application.FixApplicationFactorySettings;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStoreSettings;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;

import java.util.List;

/**
 * Declares what a {@link FixEngine} owns before it is built: the session settings stores it reads sessions from,
 * and the message stores, message loggers, application factories and session plugins its sessions may name.
 *
 * <p>Each of those is a list rather than a single value because a session names the one it wants by instance id,
 * so an engine can run a file store for one counterparty and an in-memory store for another.
 */
@Getter
@SuperBuilder(toBuilder = true)
public class FixEngineBuilder implements InstanceProvider<FixEngine> {
    /**
     * Names this engine, so more than one can run in a process and be told apart in metrics and logs.
     */
    @Builder.Default
    private final String instanceId = DEFAULT_INSTANCE_ID;
    /**
     * The admin API to expose the engine through, if any. JMX is the shipped implementation.
     */
    private AdminApiExporterSettings adminApiExporter;
    /**
     * Where sessions are read from. More than one is allowed, so sessions built in code can sit beside sessions
     * loaded from files.
     */
    @Singular
    private List<FixSessionsSettingsStoreSettings> fixSessionsSettingsStores;
    /**
     * The message stores available to sessions. A list, because a session names the one it wants by instance id -
     * which is what lets one engine persist one counterparty to disk and another to memory.
     */
    @Singular
    private List<FixMessagesStoreSettings> fixMessagesStores;
    /**
     * The message loggers available to sessions, named the same way.
     */
    @Singular
    private List<FixMessagesLoggerSettings> fixMessagesLoggers;
    /**
     * The application factories available to sessions, named the same way.
     */
    @Singular
    private List<FixApplicationFactorySettings> fixApplicationFactories;
    /**
     * The session plugins available to sessions - metrics, tracing, throttling - named the same way.
     */
    @Singular
    private List<FixSessionsPluginSettings<?>> fixSessionsPlugins;

    @Override
    public FixEngine instance() {
        return InstanceProvider.getSpiInstance(this, FixEngine.FixEngineFactory.class);
    }
}