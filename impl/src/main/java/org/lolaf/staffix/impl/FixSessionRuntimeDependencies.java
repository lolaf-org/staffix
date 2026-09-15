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

import lombok.Value;
import org.lolaf.staffix.api.application.FixApplicationFactory;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.session.FixSessionRegistry;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.stores.FixMessagesStore;

import java.util.Collection;

/**
 * The store, logger, application factory and plugins one session resolved to, gathered once when the session is
 * built.
 *
 * <p>Resolved up front rather than looked up per message: each is chosen by an instance id from the session's
 * settings, and that choice cannot change while the session runs.
 */
@Value
public class FixSessionRuntimeDependencies {

    FixMessagesStore fixMessagesStore;
    FixMessagesLogger fixMessagesLogger;
    FixApplicationFactory fixApplicationFactory;
    Collection<FixSessionsPlugin<?>> fixSessionsPlugins;
    /**
     * The engine's registry, handed to the session so that {@code FixSession.getFixSessionRegistry()} can answer.
     */
    FixSessionRegistry fixSessionRegistry;

}