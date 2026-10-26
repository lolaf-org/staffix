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

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Tells this node whether it leads, whatever technology elects the leader. An implementation wrapping an election the
 * application already runs must not start or stop it: it belongs to the application.
 */
public interface LeaderElection extends Startable<LeaderElection> {

    Role getRole();

    /**
     * Strictly increases with every new leadership, and fences a deposed leader: replication refuses what was written
     * under an older epoch. A technology that cannot provide one cannot back this interface.
     */
    long getEpoch();

    /**
     * The current leader, empty while none is known.
     */
    Optional<String> getLeaderId();

    /**
     * Whether this node can still prove it leads, by a lease or a recent quorum contact. A leader that cannot must stop
     * its sessions before another node starts them.
     */
    boolean isLeadershipValid();

    /**
     * While leadership is valid, the {@link System#nanoTime()} instant it stops being so unless renewed; empty when
     * not valid, or for an election that cannot tell. The engine checks again at that instant rather than on its next
     * poll.
     */
    default OptionalLong getLeaseEndNanos() {
        return OptionalLong.empty();
    }

    /**
     * Where the leader accepts replication, for a technology that advertises it with the leadership. Empty falls back
     * to the endpoints configured per leader id.
     */
    default Optional<InetSocketAddress> getLeaderEndpoint() {
        return Optional.empty();
    }

    void addListener(Listener listener);

    default boolean isTransferLeadershipSupported() {
        return false;
    }

    /**
     * Hands leadership to another node without leaving the election, for a planned switchover.
     *
     * @return whether the handover was started; the outcome arrives through {@link Listener#onRoleChanged}
     * @throws UnsupportedOperationException unless {@link #isTransferLeadershipSupported()}
     */
    default boolean transferLeadership() {
        throw new UnsupportedOperationException(getClass().getName() + " cannot transfer leadership");
    }

    default boolean isCandidacySupported() {
        return false;
    }

    /**
     * Whether this node stands for election. A non-candidate keeps voting but never leads, for maintenance or while it
     * is not fit to take over.
     *
     * @throws UnsupportedOperationException unless {@link #isCandidacySupported()}
     */
    default void setCandidate(boolean candidate) {
        throw new UnsupportedOperationException(getClass().getName() + " cannot decline candidacy");
    }

    /**
     * Always true unless {@link #isCandidacySupported()}.
     */
    default boolean isCandidate() {
        return true;
    }

    enum Role {
        LEADER,
        FOLLOWER,
        /**
         * No leader is known, such as during an election or a partition.
         */
        UNKNOWN
    }

    @FunctionalInterface
    interface Listener {

        void onRoleChanged(Role previous, Role current, long epoch);
    }

    /**
     * The service provider that builds a leader election from its settings class; see {@link Factory}.
     *
     * @param <S> the settings class it serves
     */
    interface LeaderElectionFactory<S> extends Factory<LeaderElection, S> {
    }
}
