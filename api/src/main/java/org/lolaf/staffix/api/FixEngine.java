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

import org.lolaf.staffix.api.session.FixSessionRegistry;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;

import java.util.List;

/**
 * Central entry point for FIX connectivity. A {@code FixEngine} owns the resources declared in its
 * {@link FixEngineBuilder} (message stores, message loggers, session plugins, application factories)
 * and manages the lifecycle of every {@link FixInitiator} and {@link FixAcceptor} created through it.
 *
 * <h2>Shutdown order</h2>
 *
 * <p>When {@link #stop(org.lolaf.ringos.Deadline)} is called, the implementation must shut down in the
 * following order:
 * <ol>
 *   <li>Stop all allocated {@link FixInitiator initiators} and {@link FixAcceptor acceptors}
 *       (draining in-flight sessions and closing network connections).</li>
 *   <li>Stop all resources defined in the {@link FixEngineBuilder}: message stores, message loggers,
 *       session plugins, and any other {@link Startable} components.</li>
 * </ol>
 *
 * <p>This ordering guarantees that sessions are fully torn down before the stores, loggers and
 * plugins they depend on are closed.
 */
public interface FixEngine extends Startable<FixEngine> {

    FixInitiator newInitiator(FixInitiatorBuilder fixInitiatorBuilder);

    FixAcceptor newAcceptor(FixAcceptorBuilder fixAcceptorBuilder);

    FixEngineBuilder getFixEngineBuilder();

    List<FixSessionsSettingsStore> getFixSessionsSettingsStores();

    /**
     * @return the registry of the sessions this engine manages, through which a session can be looked up by
     * {@link org.lolaf.staffix.api.session.FixSessionId} or by predicate. A session enters it when its
     * initiator/acceptor starts managing it - before any connection - and leaves when that control stops.
     */
    FixSessionRegistry getFixSessionRegistry();

    interface FixEngineFactory extends Factory<FixEngine, FixEngineBuilder> {

    }
}