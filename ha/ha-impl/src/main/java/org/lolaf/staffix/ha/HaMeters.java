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
import org.lolaf.replicos.FollowersView;
import org.lolaf.replicos.LeaderView;
import org.lolaf.replicos.ReplicationNode;
import org.lolaf.replicos.spi.LatencyRecorder;
import org.lolaf.replicos.spi.ReplicationMetrics;
import org.lolaf.replicos.wire.CloseCounts;
import org.lolaf.replicos.wire.CloseReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
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
    static final String FOLLOWER_CATCH_UP = "ha.follower.catch.up";
    static final String LOG_FULL_WAIT = "ha.log.full.wait";

    /**
     * The timers recorded on the replication's hot paths, which the engine binds late.
     */
    @Value
    static class Timers {
        Timer syncReceivedAckLatency;
        Timer syncAppliedAckLatency;
        Timer receivedWriteLatency;
        /**
         * By lane.
         */
        List<Timer> logFullWaits;
        ReplicationMetrics followerTimers;
    }

    static Timers register(FixEngineMonitoringContext meters, ReplicationFixEngineHa ha) {
        registerRoleMeters(meters, ha);
        registerLeaderMeters(meters, ha);
        registerFollowerMeters(meters, ha);
        return new Timers(syncAckLatency(meters, "sync_received"), syncAckLatency(meters, "sync_applied"),
                meters.getTimer(REPLICA_RECEIVED_WRITE_LATENCY, "How long this follower took to write a read's events to"
                        + " its store after acknowledging them as received: the window in which a SYNC_RECEIVED send is"
                        + " held in its memory only.", Map.of()), logFullWaits(meters, ha.getNode().getLanes()),
                followerTimers(meters));
    }

    /**
     * Records into {@code timer}, in nanoseconds.
     */
    static LatencyRecorder recorder(Timer timer) {
        return durationInNanos -> timer.record(durationInNanos, TimeUnit.NANOSECONDS);
    }

    private static List<Timer> logFullWaits(FixEngineMonitoringContext meters, int lanes) {
        List<Timer> timers = new ArrayList<>(lanes);
        for (int lane = 0; lane < lanes; lane++) {
            timers.add(meters.getTimer(LOG_FULL_WAIT, "How long a write waited for a follower a whole replication log"
                    + " behind, before the log full timeout drops it: the time the writing session thread was held.",
                    Map.of("lane", String.valueOf(lane))));
        }
        return timers;
    }

    private static ReplicationMetrics followerTimers(FixEngineMonitoringContext meters) {
        return new ReplicationMetrics() {
            @Override
            public LatencyRecorder followerAckLatency(String followerId, int lane) {
                return recorder(meters.getTimer(FOLLOWER_ACK_LATENCY, "A follower's round trip over a replication lane,"
                        + " from a batch of writes sent to its ack, sampled once per round trip, whatever the sessions'"
                        + " replication modes.", followerTags(followerId, lane)));
            }

            @Override
            public LatencyRecorder followerLag(String followerId, int lane) {
                return recorder(meters.getTimer(FOLLOWER_LAG, "How far a follower is behind on a replication lane:"
                        + " from a write to the lane's log to the follower's ack of it, sampled once per round trip.",
                        followerTags(followerId, lane)));
            }

            @Override
            public LatencyRecorder followerCatchUp(String followerId, int lane) {
                return recorder(meters.getTimer(FOLLOWER_CATCH_UP, "How long a follower took, from connecting a"
                        + " replication lane, to hold every session of the lane in sync, snapshots included: how long a"
                        + " failover to it would have lost writes. Once per connection.",
                        followerTags(followerId, lane)));
            }
        };
    }

    private static void registerRoleMeters(FixEngineMonitoringContext meters, ReplicationFixEngineHa ha) {
        meters.getGauge("ha.role", "The role the engine acts on: 2 leader, 1 follower, 0 unknown.", Map.of(),
                () -> role(ha.getRole()));
        meters.getGauge("ha.epoch", "The leader election's epoch, -1 when unknown.", Map.of(), () -> {
            LeaderElection election = ha.getElection();
            return election == null ? -1 : election.getEpoch();
        });
        meters.getGauge("ha.leadership.valid", "1 while this node leads and can prove it, 0 otherwise.", Map.of(),
                () -> ha.isLeadershipValid() ? 1 : 0);
    }

    private static void registerLeaderMeters(FixEngineMonitoringContext meters, ReplicationFixEngineHa ha) {
        ReplicationNode node = ha.getNode();
        FollowersView followers = node.followers();
        meters.getCounter("ha.log.written", "Bytes of store events this node wrote to its replication log while leading:"
                + " its rate is the replication's throughput.", Map.of(), node::getLogBytesWritten);
        meters.getGauge("ha.followers.connected", "Followers streaming this leader's writes over every replication"
                + " lane.", Map.of(), () -> whileLeading(ha, FollowersView::countConnected));
        meters.getGauge("ha.followers.in.sync", "Followers holding every replicated session in sync.", Map.of(),
                () -> whileLeading(ha, FollowersView::countInSync));
        meters.getGauge("ha.follower.unsent", "The most bytes this leader wrote and has not sent a follower yet: a follower"
                + " falling the replication log's capacity behind is dropped, then caught up by snapshot.", Map.of(),
                followers::getMaxUnsentBytes);
        meters.getCounter("ha.sync.unacknowledged", "SYNC sends that went out without the follower's ack, the ack being"
                + " late.", Map.of("reason", "timed_out"), () -> sum(ha, ReplicatedSession::getTimedOutSends));
        meters.getCounter("ha.sync.unacknowledged", "SYNC sends that went out without the follower's ack, no follower being"
                + " in sync.", Map.of("reason", "no_follower"), () -> sum(ha, ReplicatedSession::getNoFollowerSends));
        meters.getGauge("ha.follower.lag.pending", "Milliseconds the most behind follower has not applied a write for,"
                + " 0 when every follower is caught up: it keeps growing while a follower is stalled.", Map.of(),
                () -> TimeUnit.NANOSECONDS.toMillis(followers.getMaxLagNanos()));
        meters.getCounter("ha.follower.too.slow", "Follower lanes dropped for reading the replication log so slowly that a "
                + "write waited the log full timeout for them.", Map.of(), followers::getDroppedTooSlow);
        meters.getCounter("ha.snapshots", "Snapshots sent to followers catching up: of a session, or of the session settings once per connection.", Map.of(), followers::getSnapshots);
        meters.getCounter("ha.snapshot.messages", "Messages and session settings copied by the snapshots sent to followers.", Map.of(),
                followers::getSnapshotRecords);
        meters.getCounter("ha.follower.send.waits", "Times the leader had writes for a follower and could not send them:"
                + " every send buffer in flight, or the connection's write limit reached. Growing steadily on a healthy"
                + " network, sendBuffers is too small.", Map.of(), followers::getSendWaits);
        meters.getCounter("ha.follower.send.buffers.allocated", "Send buffers allocated because the pool had none free."
                + " Should always be 0: any other value is a bug.", Map.of(), followers::getSendBuffersAllocated);
        meters.getCounter("ha.log.full.waits", "Writes that found the replication log full and waited for a follower a"
                + " whole log behind: a follower falling behind before the log full timeout drops it.", Map.of(),
                followers::getLogFullWaits);
        registerCloses(meters, "ha.follower.closes", "Follower lane connections closed, by the reason either side gave,"
                + " or disconnected without one.", followers::getCloses);
    }

    private static void registerFollowerMeters(FixEngineMonitoringContext meters, ReplicationFixEngineHa ha) {
        meters.getGauge("ha.replica.connected", "1 while this follower streams its leader's writes.", Map.of(),
                () -> following(ha, LeaderView::isConnected));
        meters.getGauge("ha.replica.in.sync", "1 while this follower holds every replicated session in full.", Map.of(),
                () -> following(ha, LeaderView::isInSync));
        LeaderView leader = ha.getNode().leader();
        meters.getCounter("ha.replica.reconnects", "Replication lane connections made again to a leader after one"
                + " dropped.", Map.of(), leader::getReconnects);
        meters.getCounter("ha.replica.received", "Bytes received from leaders: its rate is the replication's throughput"
                + " on this follower.", Map.of(), leader::getBytesReceived);
        meters.getCounter("ha.replica.received.events", "Store events received from leaders, outside snapshots.",
                Map.of(), leader::getRecordsReceived);
        meters.getCounter("ha.replica.snapshots", "Snapshots received to catch up: of a session, or of the session"
                + " settings.", Map.of(), leader::getSnapshots);
        meters.getCounter("ha.replica.snapshot.messages", "Messages and session settings the snapshots received copied.",
                Map.of(), leader::getSnapshotRecords);
        registerCloses(meters, "ha.replica.closes", "Replication lane connections to a leader closed, by the reason either"
                + " side gave, or disconnected without one.", leader::getCloses);
    }

    /**
     * One counter per close reason the protocol defines, read from the node's counts at each export.
     */
    private static void registerCloses(FixEngineMonitoringContext meters, String id, String description,
                                       Supplier<Map<String, Long>> closes) {
        List<String> reasons = new ArrayList<>();
        for (CloseReason reason : CloseReason.values()) {
            reasons.add(reason.name());
        }
        reasons.add(CloseCounts.DISCONNECTED);
        for (String reason : reasons) {
            meters.getCounter(id, description, Map.of("reason", reason.toLowerCase(Locale.ROOT)),
                    () -> closes.get().getOrDefault(reason, 0L));
        }
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

    private static int whileLeading(ReplicationFixEngineHa ha, ToIntFunction<FollowersView> count) {
        return ha.getRole() == Role.LEADER ? count.applyAsInt(ha.getNode().followers()) : 0;
    }

    private static long sum(ReplicationFixEngineHa ha, ToLongFunction<ReplicatedSession> value) {
        long sum = 0;
        for (ReplicatedSession session : ha.getSessions().all()) {
            sum += value.applyAsLong(session);
        }
        return sum;
    }

    private static int following(ReplicationFixEngineHa ha, Predicate<LeaderView> matching) {
        return ha.getRole() == Role.FOLLOWER && matching.test(ha.getNode().leader()) ? 1 : 0;
    }
}
