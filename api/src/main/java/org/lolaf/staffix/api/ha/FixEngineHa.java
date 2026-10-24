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
package org.lolaf.staffix.api.ha;

import org.lolaf.staffix.api.Factory;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;

/**
 * An engine's high availability: everything the engine calls to replicate its sessions and to follow the leader
 * election. Started and stopped with the engine.
 */
public interface FixEngineHa extends Startable<FixEngineHa> {

    /**
     * The store a session uses in place of {@code store}, replicating what is written to it. Called each time the
     * session is built, which a restart repeats, and only for a session whose
     * {@link FixSessionSettings.HaSessionSettings#getReplicationMode()} is not
     * {@link FixSessionSettings.HaSessionSettings.ReplicationMode#NONE NONE}: other sessions keep their store
     * unwrapped, so replication costs them nothing.
     *
     * <p>{@code store} reports a storage failure rather than throwing it, so a write reaching it can always be
     * replicated, and every write comes from the session's own thread, one at a time.
     */
    FixSessionMessagesStore replicatingStore(FixSessionSettings settings, FixSessionMessagesStore store);

    /**
     * The service provider that builds an engine's high availability from its settings class; see {@link Factory}.
     *
     * @param <S> the settings class it serves
     */
    interface FixEngineHaFactory<S> extends Factory<FixEngineHa, S> {
    }
}
