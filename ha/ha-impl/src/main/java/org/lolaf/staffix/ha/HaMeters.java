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
package org.lolaf.staffix.ha;

import lombok.experimental.UtilityClass;
import org.lolaf.staffix.api.ha.LeaderElection;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.staffix.api.monitoring.FixEngineMonitoringContext;
import org.lolaf.staffix.api.monitoring.Timer;
import org.lolaf.staffix.ha.follower.ReplicationReceiver;
import org.lolaf.staffix.ha.leader.FollowerLink;
import org.lolaf.staffix.ha.leader.ReplicationStats;

import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * The replication's meters. All but the {@code SYNC} ack latency are read from the replication's own state at each
 * export, on the exporter's thread, so they cost the session thread nothing; each reads 0 in a role it does not apply
 * to.
 */
@UtilityClass
class HaMeters {

    static final String SYNC_ACK_LATENCY = "ha.sync.ack.latency";

    /**
     * @return the timer of the {@code SYNC} sends' wait for the follower's ack
     */
    static Timer register(FixEngineMonitoringContext meters, ReplicationFixEngineHa ha, ReplicationStats stats) {
        meters.getGauge("ha.role", "The role the engine acts on: 2 leader, 1 follower, 0 unknown.", Map.of(),
                () -> role(ha.getFollowedRole()));
        meters.getGauge("ha.epoch", "The leader election's epoch, -1 when unknown.", Map.of(), () -> {
            LeaderElection election = ha.getElection();
            return election == null ? -1 : election.getEpoch();
        });
        meters.getGauge("ha.leadership.valid", "1 while this node leads and can prove it, 0 otherwise.", Map.of(), () -> {
            LeaderElection election = ha.getElection();
            return election != null && election.getRole() == Role.LEADER && election.isLeadershipValid() ? 1 : 0;
        });
        meters.getCounter("ha.log.written", "Bytes of store events this node wrote to its replication log while leading:"
                + " its rate is the replication's throughput.", Map.of(), () -> ha.getReplicationLog().getTailOffset());
        meters.getGauge("ha.followers.connected", "Followers streaming this leader's writes.", Map.of(),
                () -> count(ha, FollowerLink::isConnected));
        meters.getGauge("ha.followers.in.sync", "Followers holding every replicated session in sync.", Map.of(),
                () -> count(ha, link -> link.isInSyncForAll(ha.getSessions().count())));
        meters.getGauge("ha.follower.unsent", "The most bytes this leader wrote and has not sent a follower yet: a follower"
                + " falling the replication log's capacity behind is dropped, then caught up by snapshot.", Map.of(), () -> {
            long most = 0;
            for (FollowerLink link : ha.getFollowers().all()) {
                most = Math.max(most, link.getUnsentBytes());
            }
            return most;
        });
        meters.getCounter("ha.sync.unacknowledged", "SYNC sends that went out without the follower's ack, the ack being"
                + " late.", Map.of("reason", "timed_out"), () -> sum(ha, ReplicatedSession::getTimedOutSends));
        meters.getCounter("ha.sync.unacknowledged", "SYNC sends that went out without the follower's ack, no follower being"
                + " in sync.", Map.of("reason", "no_follower"), () -> sum(ha, ReplicatedSession::getNoFollowerSends));
        meters.getCounter("ha.follower.laps", "Followers dropped for falling the replication log's capacity behind.",
                Map.of(), stats::getLaps);
        meters.getCounter("ha.snapshots", "Session snapshots sent to followers catching up.", Map.of(), stats::getSnapshots);
        meters.getCounter("ha.snapshot.messages", "Messages copied by the snapshots sent to followers.", Map.of(),
                stats::getSnapshotMessages);
        meters.getGauge("ha.replica.connected", "1 while this follower streams its leader's writes.", Map.of(),
                () -> following(ha, ReplicationReceiver::isConnected));
        meters.getGauge("ha.replica.in.sync", "1 while this follower holds every replicated session in full.", Map.of(),
                () -> following(ha, ReplicationReceiver::isInSync));
        return meters.getTimer(SYNC_ACK_LATENCY, "How long a SYNC send waited for the follower's ack, for the sends it"
                + " acknowledged.", Map.of());
    }

    private static int role(Role role) {
        switch (role) {
            case LEADER:
                return 2;
            case FOLLOWER:
                return 1;
            default:
                return 0;
        }
    }

    private static int count(ReplicationFixEngineHa ha, Predicate<FollowerLink> matching) {
        if (ha.getFollowedRole() != Role.LEADER) {
            return 0;
        }
        int count = 0;
        for (FollowerLink link : ha.getFollowers().all()) {
            if (matching.test(link)) {
                count++;
            }
        }
        return count;
    }

    private static long sum(ReplicationFixEngineHa ha, ToLongFunction<ReplicatedSession> value) {
        long sum = 0;
        for (ReplicatedSession session : ha.getSessions().all()) {
            sum += value.applyAsLong(session);
        }
        return sum;
    }

    private static int following(ReplicationFixEngineHa ha, Predicate<ReplicationReceiver> matching) {
        ReplicationReceiver receiver = ha.getReceiver();
        return ha.getFollowedRole() == Role.FOLLOWER && receiver != null && matching.test(receiver) ? 1 : 0;
    }
}
