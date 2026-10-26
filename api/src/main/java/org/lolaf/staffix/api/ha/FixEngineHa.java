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

import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Factory;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.alerting.Probes;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.monitoring.FixEngineMonitoringContext;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringContext;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * An engine's high availability: everything the engine calls to replicate its sessions and to follow the leader
 * election. Started and stopped with the engine.
 */
public interface FixEngineHa extends Startable<FixEngineHa>, HaAdmin {

    /**
     * The store a session uses in place of {@code store}, replicating what is written to it. Called each time the
     * session is built, which a restart repeats, and only for a session whose
     * {@link FixSessionSettings.HaSessionSettings#getReplicationMode()} is not
     * {@link FixSessionSettings.HaSessionSettings.ReplicationMode#NONE NONE}: other sessions keep their store
     * unwrapped, so replication costs them nothing.
     *
     * <p>{@code store} reports a storage failure rather than throwing it, so a write reaching it can always be
     * replicated, and every write comes from the session's own thread, one at a time. {@code probes} are the session's,
     * which the replication adds its own to.
     */
    ReplicatingStore replicatingStore(FixSessionSettings settings, FixSessionMessagesStore store, Probes probes);

    /**
     * Called in place of {@link #replicatingStore} each time a session whose replication mode is
     * {@link FixSessionSettings.HaSessionSettings.ReplicationMode#NONE NONE} is built, so one replicated until its
     * settings changed stops being. Once per build, never per message.
     */
    void notReplicated(FixSessionSettings settings);

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

        /**
         * The engine meters of the engine's session plugin with that instance id, which must be a
         * {@link org.lolaf.staffix.api.monitoring.FixEngineMonitoringManager}.
         *
         * @throws IllegalArgumentException when the engine has no such plugin
         */
        FixEngineMonitoringContext monitoring(String monitoringInstanceId);

        /**
         * The probes of the component, a {@link org.lolaf.staffix.api.ComponentProbeScope}, evaluated by the engine
         * and published to its alerts publisher of that instance id. Asked again for the same component, returns the
         * same probes, with the publisher and activity of the first call.
         *
         * @param active whether the component is active, for its probes run only while active
         * @throws IllegalArgumentException when the engine has no such alerts publisher
         */
        Probes probes(String component, String alertsPublisherInstanceId, BooleanSupplier active);

        /**
         * Runs the sessions: the initiators and acceptors the application started connect and bind. An engine with
         * high availability starts without them, so a standby never builds a session; call this once this node leads
         * and the replica's stores are closed.
         */
        void activate();

        /**
         * Drops every session at once, without a Logout, and closes their stores, for a node that no longer leads.
         * The initiators and acceptors stay started for the next {@link #activate()}.
         */
        void deactivate();

        /**
         * {@link #deactivate()} for a node handing leadership over while it still leads: logs every session out, as
         * a clean engine stop does, waiting for each until {@code deadline}, so its counterparty sees an orderly
         * Logout. The Logouts are written to the stores, so they are replicated like any write.
         */
        void deactivateWithLogout(Deadline deadline);
    }

    /**
     * A replicated session's store.
     */
    interface ReplicatingStore extends FixSessionMessagesStore {

        /**
         * Called by a {@link FixSessionSettings.HaSessionSettings.ReplicationMode#SYNC SYNC} session as a message is
         * built, before it goes out: replicates the store write its sending makes afterwards, which then only applies
         * locally. {@code message} is read from its position to its limit and left as it is.
         */
        void replicateSent(MessageType messageType, long outgoingSeqNum, ByteBuffer message);

        /**
         * Called by a {@code SYNC} session after {@link #replicateSent}, before the message goes out, from the
         * session's thread. Returns when the standby has every write replicated so far, when
         * {@link FixSessionSettings.HaSessionSettings#getReplicationAckTimeout()} runs out, or at once while no standby
         * is in sync, so that a lost standby never stops trading.
         */
        void awaitReplicated();

        /**
         * Called each time the session starts, before it connects, with its monitoring's meters, which only exist
         * from then on: the session's plugins are created after its store. Not called for a session without
         * monitoring.
         */
        void sessionMeters(FixSessionsMonitoringContext meters);
    }

    /**
     * The service provider that builds an engine's high availability from its settings class; see {@link Factory}.
     *
     * @param <S> the settings class it serves
     */
    interface FixEngineHaFactory<S> extends Factory<FixEngineHa, S> {
    }
}
