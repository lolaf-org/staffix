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

import lombok.Value;
import lombok.experimental.UtilityClass;
import org.lolaf.staffix.api.ha.LeaderElection;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.staffix.api.monitoring.FixEngineMonitoringContext;
import org.lolaf.staffix.api.monitoring.MetersContext;
import org.lolaf.staffix.api.monitoring.Timer;
import org.lolaf.staffix.ha.follower.ReplicationClient;
import org.lolaf.staffix.ha.leader.FollowerConnection;
import org.lolaf.staffix.ha.leader.ReplicationStats;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * The replication's meters. All but the ack latency timers are read from the replication's own state at each
 * export, on the exporter's thread, so they cost the session thread nothing; each reads 0 in a role it does not apply
 * to.
 */
@UtilityClass
class HaMeters {

    static final String SYNC_ACK_LATENCY = "ha.sync.ack.latency";
    static final String REPLICA_RECEIVED_WRITE_LATENCY = "ha.replica.received.write.latency";
    static final String SESSION_SYNC_ACK_LATENCY = "ha.session.sync.ack.latency";
    static final String FOLLOWER_ACK_LATENCY = "ha.follower.ack.latency";
    static final String FOLLOWER_LAG = "ha.follower.lag";

    /**
     * The timers recorded on the replication's hot paths, which the engine binds late.
     */
    @Value
    static class Timers {
        Timer syncReceivedAckLatency;
        Timer syncAppliedAckLatency;
        Timer receivedWriteLatency;
    }

    static Timers register(FixEngineMonitoringContext meters, ReplicationFixEngineHa ha, ReplicationStats stats) {
        meters.getGauge("ha.role", "The role the engine acts on: 2 leader, 1 follower, 0 unknown.", Map.of(),
                () -> role(ha.getRole()));
        meters.getGauge("ha.epoch", "The leader election's epoch, -1 when unknown.", Map.of(), () -> {
            LeaderElection election = ha.getElection();
            return election == null ? -1 : election.getEpoch();
        });
        meters.getGauge("ha.leadership.valid", "1 while this node leads and can prove it, 0 otherwise.", Map.of(), () -> {
            LeaderElection election = ha.getElection();
            return election != null && election.getRole() == Role.LEADER && election.isLeadershipValid() ? 1 : 0;
        });
        meters.getCounter("ha.log.written", "Bytes of store events this node wrote to its replication log while leading:"
                + " its rate is the replication's throughput.", Map.of(), () -> ha.getReplicationLogs().getTailOffsets());
        meters.getGauge("ha.followers.connected", "Followers streaming this leader's writes over every replication"
                + " lane.", Map.of(), () -> countFollowers(ha, FollowerConnection::isConnected));
        meters.getGauge("ha.followers.in.sync", "Followers holding every replicated session in sync.", Map.of(),
                () -> countFollowers(ha, connection -> connection.isInSyncForAll(ha.getSessions().count())));
        meters.getGauge("ha.follower.unsent", "The most bytes this leader wrote and has not sent a follower yet: a follower"
                + " falling the replication log's capacity behind is dropped, then caught up by snapshot.", Map.of(), () -> {
            long most = 0;
            for (FollowerConnection connection : ha.getFollowers().all()) {
                most = Math.max(most, connection.getUnsentBytes());
            }
            return most;
        });
        meters.getCounter("ha.sync.unacknowledged", "SYNC sends that went out without the follower's ack, the ack being"
                + " late.", Map.of("reason", "timed_out"), () -> sum(ha, ReplicatedSession::getTimedOutSends));
        meters.getCounter("ha.sync.unacknowledged", "SYNC sends that went out without the follower's ack, no follower being"
                + " in sync.", Map.of("reason", "no_follower"), () -> sum(ha, ReplicatedSession::getNoFollowerSends));
        stats.ackLatencyTimers((followerId, lane) -> meters.getTimer(FOLLOWER_ACK_LATENCY, "A follower's round trip over"
                + " a replication lane, from a batch of writes sent to its ack, sampled once per round trip, whatever the"
                + " sessions' replication modes.", followerTags(followerId, lane)));
        stats.lagTimers((followerId, lane) -> meters.getTimer(FOLLOWER_LAG, "How far a follower is behind on a"
                + " replication lane: from a write to the lane's log to the follower's ack of it, sampled once per round"
                + " trip.", followerTags(followerId, lane)));
        meters.getGauge("ha.follower.lag.pending", "Milliseconds the most behind follower has not applied a write for,"
                + " 0 when every follower is caught up: it keeps growing while a follower is stalled.", Map.of(), () -> {
            long now = System.nanoTime();
            long most = 0;
            for (FollowerConnection connection : ha.getFollowers().all()) {
                most = Math.max(most, connection.getLagNanos(now));
            }
            return TimeUnit.NANOSECONDS.toMillis(most);
        });
        meters.getCounter("ha.follower.too.slow", "Follower lanes dropped for reading the replication log so slowly that a "
                + "write waited the log full timeout for them.", Map.of(), stats::getTooSlow);
        meters.getCounter("ha.snapshots", "Session snapshots sent to followers catching up.", Map.of(), stats::getSnapshots);
        meters.getCounter("ha.snapshot.messages", "Messages copied by the snapshots sent to followers.", Map.of(),
                stats::getSnapshotMessages);
        meters.getCounter("ha.follower.send.waits", "Times the leader had writes for a follower and could not send them:"
                + " every send buffer in flight, or the connection's write limit reached. Growing steadily on a healthy"
                + " network, sendBuffers is too small.", Map.of(), stats::getSendWaits);
        meters.getCounter("ha.follower.send.buffers.allocated", "Send buffers allocated because the pool had none free."
                + " Should always be 0: any other value is a bug.", Map.of(), stats::getSendBuffersAllocated);
        meters.getGauge("ha.replica.connected", "1 while this follower streams its leader's writes.", Map.of(),
                () -> following(ha, ReplicationClient::isConnected));
        meters.getGauge("ha.replica.in.sync", "1 while this follower holds every replicated session in full.", Map.of(),
                () -> following(ha, ReplicationClient::isInSync));
        return new Timers(syncAckLatency(meters, "sync_received"), syncAckLatency(meters, "sync_applied"),
                meters.getTimer(REPLICA_RECEIVED_WRITE_LATENCY, "How long this follower took to write a read's events to"
                        + " its store after acknowledging them as received: the window in which a SYNC_RECEIVED send is"
                        + " held in its memory only.", Map.of()));
    }

    private static Timer syncAckLatency(FixEngineMonitoringContext meters, String mode) {
        return meters.getTimer(SYNC_ACK_LATENCY, "How long a SYNC send waited for the follower's ack, for the sends it"
                + " acknowledged, tagged by the session's mode.", Map.of("mode", mode));
    }

    static Timer sessionSyncAckLatency(MetersContext sessionMeters) {
        return sessionMeters.getTimer(SESSION_SYNC_ACK_LATENCY, "How long this session's SYNC sends waited for the"
                + " follower's ack back", Map.of());
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

    private static Map<String, String> followerTags(String followerId, int lane) {
        return Map.of("follower", followerId, "lane", String.valueOf(lane));
    }

    /**
     * The followers whose every lane matches, a follower with one lane down or behind not being counted.
     */
    private static long countFollowers(ReplicationFixEngineHa ha, Predicate<FollowerConnection> matching) {
        if (ha.getRole() != Role.LEADER) {
            return 0;
        }
        Map<String, Set<Integer>> matchingLanes = new HashMap<>();
        for (FollowerConnection connection : ha.getFollowers().all()) {
            if (connection.getFollowerId() != null && matching.test(connection)) {
                matchingLanes.computeIfAbsent(connection.getFollowerId(), id -> new HashSet<>()).add(connection.getLane());
            }
        }
        int lanes = ha.getReplicationLogs().count();
        return matchingLanes.values().stream().filter(matched -> matched.size() == lanes).count();
    }

    private static long sum(ReplicationFixEngineHa ha, ToLongFunction<ReplicatedSession> value) {
        long sum = 0;
        for (ReplicatedSession session : ha.getSessions().all()) {
            sum += value.applyAsLong(session);
        }
        return sum;
    }

    private static int following(ReplicationFixEngineHa ha, Predicate<ReplicationClient> matching) {
        ReplicationClient client = ha.getClient();
        return ha.getRole() == Role.FOLLOWER && client != null && matching.test(client) ? 1 : 0;
    }
}
