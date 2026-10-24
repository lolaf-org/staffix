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

import java.util.List;

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
    ReplicatingStore replicatingStore(FixSessionSettings settings, FixSessionMessagesStore store);

    /**
     * Called once by the engine, before {@link #start()}.
     */
    void bind(EngineAccess engine);

    /**
     * What the engine lends its high availability: a standby replays the leader's writes into its sessions' stores
     * while the sessions themselves are not running.
     */
    interface EngineAccess {

        /**
         * The settings of every session the engine manages now.
         */
        List<FixSessionSettings> getFixSessionsSettings();

        /**
         * The store the session would use, opened outside any session and not started. The caller starts it, and
         * stops it before the session starts, so the session never shares its store with a replica.
         */
        FixSessionMessagesStore openStore(FixSessionSettings settings);
    }

    /**
     * A replicated session's store.
     */
    interface ReplicatingStore extends FixSessionMessagesStore {

        /**
         * Called by a {@link FixSessionSettings.HaSessionSettings.ReplicationMode#SYNC SYNC} session once a message
         * is in its store and before it goes out, from the session's thread. Returns when the standby has every write
         * made so far, when {@link FixSessionSettings.HaSessionSettings#getReplicationAckTimeout()} runs out, or at
         * once while no standby is in sync, so that a lost standby never stops trading.
         */
        void awaitReplicated();
    }

    /**
     * The service provider that builds an engine's high availability from its settings class; see {@link Factory}.
     *
     * @param <S> the settings class it serves
     */
    interface FixEngineHaFactory<S> extends Factory<FixEngineHa, S> {
    }
}
