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
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.ProbeEvaluator;
import org.lolaf.staffix.api.alerting.Probes;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.api.ha.LeaderElection;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.staffix.ha.follower.ReplicationReceiver;
import org.lolaf.staffix.ha.leader.FollowerLink;
import org.lolaf.staffix.ha.leader.Followers;
import org.lolaf.staffix.ha.wire.SessionStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * The replication's probes: the node's own, of component {@link #COMPONENT}, and each replicated session's. A probe
 * of one role reports nothing in the other.
 */
@UtilityClass
class HaProbes {

    static final String COMPONENT = "ha";
    static final String FOLLOWERS = "ha-followers";
    static final String LEADER_LINK = "ha-leader-link";
    static final String LEADERSHIP = "ha-leadership";
    static final String REPLICATION = "ha-replication";

    /**
     * Long enough for a follower to reconnect or an election to settle without alerting.
     */
    static final Duration RAISE_DELAY = Duration.ofSeconds(10);

    /**
     * How long a session's alert outlives the last send or event it reports, which it cannot tell has stopped.
     */
    static final Duration SESSION_ALERT_EXPIRY = Duration.ofMinutes(5);

    /**
     * Adds the node's probes, replacing those of a previous start.
     */
    static void register(Probes probes, ReplicationFixEngineHa ha) {
        replace(probes, FOLLOWERS, "While leading: the followers connected and in sync, without which a failover loses"
                + " the sessions' latest writes.", followers(ha));
        replace(probes, LEADER_LINK, "While following: the connection to the leader and whether this node holds every"
                + " replicated session in full, as a failover to it needs.", leaderLink(ha));
        replace(probes, LEADERSHIP, "Whether this node knows its role: without one it neither runs the sessions nor"
                + " replicates them.", leadership(ha));
    }

    private static void replace(Probes probes, String name, String description, ProbeEvaluator evaluator) {
        probes.remove(name);
        probes.add(Probe.builder().name(name).description(description).evaluator(evaluator.withRaiseDelay(RAISE_DELAY)).build());
    }

    static Probe sessionProbe(ReplicatedSession session, Followers followers) {
        return Probe.builder()
                .name(REPLICATION)
                .description("While leading: SYNC sends the follower did not ack in time, messages longer than half the"
                        + " replication log, and a follower refusing to replicate the session.")
                .evaluator(new SessionReplicationEvaluator(session, followers, System::nanoTime, SESSION_ALERT_EXPIRY.toNanos()))
                .onlyWhileActive(false)
                .build();
    }

    static ProbeEvaluator followers(ReplicationFixEngineHa ha) {
        return () -> {
            if (ha.getFollowedRole() != Role.LEADER) {
                return Optional.empty();
            }
            List<String> lagging = new ArrayList<>();
            boolean anyConnected = false;
            for (FollowerLink link : ha.getFollowers().all()) {
                HaStatus.Follower follower = link.status(ha.getSessions().count());
                anyConnected |= follower.isConnected();
                if (follower.isConnected() && (!follower.isInSync() || follower.isSnapshotInProgress())) {
                    lagging.add(follower.getNodeId() + (follower.isSnapshotInProgress() ? " catching up by snapshot"
                            : " not in sync") + ", " + follower.getUnsentBytes() + " bytes unsent");
                }
            }
            if (!anyConnected) {
                return Optional.of(Alert.major("No follower connected for " + RAISE_DELAY.toSeconds()
                        + " s: a failover now loses the sessions' latest writes"));
            }
            return lagging.isEmpty() ? Optional.empty() : Optional.of(Alert.warning("Follower " + String.join("; ", lagging)));
        };
    }

    static ProbeEvaluator leaderLink(ReplicationFixEngineHa ha) {
        return () -> {
            if (ha.getFollowedRole() != Role.FOLLOWER) {
                return Optional.empty();
            }
            ReplicationReceiver receiver = ha.getReceiver();
            if (receiver == null) {
                return Optional.of(Alert.major("No address for the leader, " + leaderId(ha) + ", to replicate from"));
            }
            if (!receiver.isConnected()) {
                return Optional.of(Alert.major("Not connected to the leader " + receiver.status().getAddress()
                        + " for " + RAISE_DELAY.toSeconds() + " s"));
            }
            return receiver.isInSync() ? Optional.empty()
                    : Optional.of(Alert.warning("Connected to the leader, but not holding every replicated session in full"));
        };
    }

    static ProbeEvaluator leadership(ReplicationFixEngineHa ha) {
        return () -> {
            if (ha.getFollowedRole() != Role.UNKNOWN) {
                return Optional.empty();
            }
            LeaderElection election = ha.getElection();
            boolean leadsUnproven = election != null && election.getRole() == Role.LEADER && !election.isLeadershipValid();
            return Optional.of(Alert.critical((leadsUnproven ? "Elected leader but unable to prove it" : "No role")
                    + " for " + RAISE_DELAY.toSeconds() + " s: the sessions run nowhere unless the other node leads"));
        };
    }

    private static String leaderId(ReplicationFixEngineHa ha) {
        LeaderElection election = ha.getElection();
        return election == null ? "unknown" : election.getLeaderId().orElse("unknown");
    }

    /**
     * Reads what the session's thread records, so it holds no state of its own and a rebuilt session's probe takes
     * over from the previous one's.
     */
    @Value
    static class SessionReplicationEvaluator implements ProbeEvaluator {

        ReplicatedSession session;
        Followers followers;
        LongSupplier nanoClock;
        long expiryNanos;

        @Override
        public Optional<Alert> evaluate() {
            List<String> problems = new ArrayList<>();
            for (FollowerLink link : followers.all()) {
                SessionStatus reported = link.getReportedStatus(session.getKey());
                if (reported == SessionStatus.UNKNOWN_SESSION || reported == SessionStatus.UNKNOWN_STORAGE) {
                    problems.add("follower " + link.getFollowerId() + " refuses to replicate it: " + reported);
                }
            }
            long now = nanoClock.getAsLong();
            if (session.getTimedOutSends() > 0 && now - session.getLastTimedOutNanos() < expiryNanos) {
                problems.add(session.getTimedOutSends() + " SYNC sends went out without the follower's ack in time");
            }
            if (session.getUnreplicatedEvents() > 0 && now - session.getLastUnreplicatedNanos() < expiryNanos) {
                problems.add(session.getUnreplicatedEvents() + " messages longer than half the replication log, missing"
                        + " on the follower");
            }
            return problems.isEmpty() ? Optional.empty() : Optional.of(Alert.major(String.join("; ", problems)));
        }
    }
}
