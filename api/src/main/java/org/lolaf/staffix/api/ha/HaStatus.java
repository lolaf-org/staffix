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
import org.lolaf.staffix.api.session.FixSession;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.ha.LeaderElection.Role;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * An engine's high availability: the role it acts on, and its replication connections.
 */
@NullMarked
@Value
@Builder
public class HaStatus {

    String nodeId;

    /**
     * The same on every node of a pair, so a console can group them: node ids repeat across pairs. Null when unnamed.
     */
    @Nullable String pairName;
    /**
     * The role the engine acts on, which trails the election's while it changes over.
     */
    Role role;
    /**
     * The election epoch {@link #role} was taken at, -1 while the role is unknown.
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
    String leaderElectionType;
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
    boolean electableSupported;
    /**
     * Whether this node stands for election.
     */
    boolean electable;
    /**
     * The longest a switchover can take, from waiting for a follower ready to lead to leadership leaving this node. Null
     * without high availability.
     */
    @Nullable Duration switchoverMaxDuration;
    /**
     * The instance of the monitoring plugin publishing the node's own meters. Null when it publishes none.
     */
    @Nullable String monitoringInstanceId;
    /**
     * The other nodes, by node id: while this node leads, the followers it expects, so one that is down still shows.
     */
    @Singular
    List<String> peers;
    /**
     * The followers a {@code SYNC} send waits for, this node left out; empty makes every follower one. Each is listed
     * whether connected or not, so one that is down still shows.
     */
    @Singular
    List<String> syncFollowers;
    /**
     * While this node leads: its followers' connections, one per follower and lane.
     */
    @Singular
    List<FollowerConnection> followerConnections;
    /**
     * While this node leads: the followers it dropped as too slow and not back in sync since.
     */
    @Singular
    List<DroppedFollower> droppedFollowers;
    /**
     * While this node follows: how its session settings differ from the leader's, as a failover runs its own.
     */
    @Singular
    List<SessionSettingsDifference> sessionSettingsDifferences;
    /**
     * This node's leader, over all its lanes, while it follows one.
     */
    @Nullable Leader leader;
    /**
     * While this node does not lead: the last write it holds on each lane, kept with the leader down, to choose which
     * follower to promote.
     */
    @Singular
    List<LanePosition> lanePositions;

    /**
     * One follower's connection for one lane, as its leader sees it.
     */
    @Value
    @Builder
    public static class FollowerConnection {

        /**
         * Null until the follower said.
         */
        @Nullable String nodeId;
        /**
         * The lane, each carrying its own share of the sessions over its own connection.
         */
        int lane;
        String address;
        /**
         * Streaming the leader's writes, rather than greeting or closing.
         */
        boolean connected;
        /**
         * Holds every session of the lane in sync: a failover to it loses nothing acknowledged if it is also a
         * {@link #isSyncFollower() sync follower}.
         */
        boolean inSync;
        /**
         * One of the {@link HaStatus#getSyncFollowers() followers a SYNC send waits for}; a disaster-recovery copy is
         * not, and may lack what a {@code SYNC} send acknowledged.
         */
        boolean syncFollower;
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
         * The offset in the lane's replication log of the last write the follower applied, -1 before it applied one of
         * this leadership's: only the gap between two followers of a lane means anything, in bytes of that log.
         */
        long appliedOffset;
        /**
         * How long the follower has been behind a write it has not applied yet, 0 when caught up: it grows while the
         * follower is stalled, which {@link #inSync}, as of its last ack, does not show.
         */
        long lagMillis;
        /**
         * What encrypts and authenticates the lane: the cipher, its key size and, for a stream cipher, its MAC. Null
         * while the lane is not connected or replicates in clear.
         */
        @Nullable String cipher;
    }

    /**
     * The last write a follower holds on one lane. Two followers' positions compare only at the same epoch and
     * leadership, the offset being in the log of the leadership that sent it.
     */
    @Value
    @Builder
    public static class LanePosition {

        int lane;
        /**
         * 0 before the follower applied any write.
         */
        long epoch;
        /**
         * The random id its leader drew on starting to lead; 0 before the follower applied any write.
         */
        long leadership;
        /**
         * The write's offset in the lane's replication log, -1 for none since the lane last connected without resuming.
         */
        long offset;
    }

    /**
     * A follower dropped for reading the replication log so slowly that a write waited the log full timeout for it.
     */
    @Value
    @Builder
    public static class DroppedFollower {

        String nodeId;
        /**
         * The lane dropped, the follower's others carrying on.
         */
        int lane;
        Instant droppedAt;
        /**
         * Until when it is refused, so its resync does not add to the load that made it too slow; null once it may
         * connect.
         */
        @Nullable Instant heldOffUntil;
    }

    /**
     * A session whose settings are not the same on this node and on the leader.
     */
    @Value
    @Builder
    public static class SessionSettingsDifference {

        String store;
        FixSession.FixSessionType fixSessionType;
        String fixSessionId;
        Kind kind;

        public enum Kind {
            MISSING_HERE, EXTRA_HERE, DIFFERENT
        }
    }

    /**
     * The leader as its follower sees it, over all lanes.
     */
    @Value
    @Builder
    public static class Leader {

        /**
         * Null until the leader said.
         */
        @Nullable String leaderId;
        String address;
        /**
         * Every lane is streaming.
         */
        boolean connected;
        /**
         * Holds every replicated session in full, as of the last write applied.
         */
        boolean inSync;
        /**
         * What encrypts and authenticates every lane: the cipher, its key size and, for a stream cipher, its MAC. Null
         * while a lane is not connected or replicates in clear.
         */
        @Nullable String cipher;
    }
}
