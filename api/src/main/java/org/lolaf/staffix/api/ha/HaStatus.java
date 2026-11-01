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

import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.ha.LeaderElection.Role;

import java.time.Instant;
import java.util.List;

/**
 * An engine's high availability: the role it acts on, and its replication links.
 */
@NullMarked
@Value
@Builder
public class HaStatus {

    String nodeId;
    /**
     * The role the engine acts on, which trails the election's while it changes over.
     */
    Role role;
    /**
     * The election's epoch, -1 when unknown.
     */
    long epoch;
    @Nullable String leaderId;
    /**
     * Whether this node can still prove it leads; a leader that cannot stops its sessions.
     */
    boolean leadershipValid;
    /**
     * The leader election's implementation, such as {@code PrimosLeaderElection}.
     */
    String leaderElection;
    /**
     * When the engine took its role, null before it took any.
     */
    @Nullable Instant roleSince;
    /**
     * Whether the leader election can hand leadership over, for a planned switchover.
     */
    boolean transferLeadershipSupported;
    /**
     * Whether the leader election lets this node decline to stand, for maintenance.
     */
    boolean candidacySupported;
    /**
     * Whether this node stands for election.
     */
    boolean candidate;
    /**
     * The followers connected to this node, while it leads.
     */
    @Singular
    List<Follower> followers;
    /**
     * While this node leads: the followers it dropped as too slow and not back in sync since.
     */
    @Singular
    List<DroppedFollower> droppedFollowers;
    /**
     * This node's link to its leader, while it follows one.
     */
    @Nullable Leader leader;

    /**
     * A follower as its leader sees it.
     */
    @Value
    @Builder
    public static class Follower {

        /**
         * Null until the follower said.
         */
        @Nullable String nodeId;
        /**
         * The replication link, below the leader's {@code replicationLinks}: a follower has an entry per link, each
         * carrying its own share of the sessions.
         */
        int link;
        String address;
        /**
         * Streaming the leader's writes, rather than greeting or closing.
         */
        boolean connected;
        /**
         * Holds every session replicated over the link in sync, so a failover to it loses nothing acknowledged.
         */
        boolean inSync;
        /**
         * A session's snapshot is waiting or on its way.
         */
        boolean snapshotInProgress;
        /**
         * The bytes the leader wrote that it has not sent this follower yet, -1 before it started sending. Reaching
         * the leader's replication log capacity makes its writes wait, then drops the follower, which a snapshot then
         * catches up.
         */
        long unsentBytes;
        /**
         * How long the follower has been behind a write it has not applied yet, 0 when caught up: it grows while the
         * follower is stalled, which {@link #inSync}, as of its last ack, does not show.
         */
        long lagMillis;
    }

    /**
     * A follower dropped for reading the replication log so slowly that a write waited the log full timeout for it.
     */
    @Value
    @Builder
    public static class DroppedFollower {

        String nodeId;
        /**
         * The link dropped, the follower's others carrying on.
         */
        int link;
        Instant droppedAt;
        /**
         * Until when it is refused, so its resync does not add to the load that made it too slow; null once it may
         * connect.
         */
        @Nullable Instant heldOffUntil;
    }

    /**
     * A follower's link to its leader.
     */
    @Value
    @Builder
    public static class Leader {

        /**
         * Null until the leader said.
         */
        @Nullable String leaderId;
        String address;
        boolean connected;
        /**
         * Holds every replicated session in full, as of the last write applied.
         */
        boolean inSync;
    }
}
