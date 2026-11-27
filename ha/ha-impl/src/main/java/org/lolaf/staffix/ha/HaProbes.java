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
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.ProbeEvaluator;
import org.lolaf.staffix.api.alerting.Probes;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.api.ha.LeaderElection;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.replicos.EncryptionMode;
import org.lolaf.replicos.DroppedFollower;
import org.lolaf.replicos.FollowerLaneStatus;
import org.lolaf.replicos.FollowersView;
import org.lolaf.replicos.LeaderStatus;
import org.lolaf.replicos.LeaderView;
import org.lolaf.replicos.ReplicationNode;
import org.lolaf.replicos.spi.StreamAnswer;
import org.lolaf.replicos.wire.CloseReason;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The replication's probes: the node's own, of component {@link #COMPONENT}, and each replicated session's. A probe
 * of one role reports nothing in the other.
 */
@UtilityClass
class HaProbes {

    static final String COMPONENT = "ha";
    static final String FOLLOWERS = "ha-followers";
    static final String LEADER_CONNECTION = "ha-leader-connection";
    static final String LEADERSHIP = "ha-leadership";
    static final String SESSION_SETTINGS = "ha-session-settings";
    static final String ENCRYPTION = "ha-encryption";
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
        replace(probes, FOLLOWERS, "While leading: every peer connected, a sync follower among them, and the followers in"
                + " sync and applying the writes, without which a failover loses the sessions' latest writes.",
                followersEvaluator(ha));
        replace(probes, LEADER_CONNECTION, "While following: the connection to the leader and whether this node holds every"
                + " replicated session in full, as a failover to it needs.", leaderConnectionEvaluator(ha));
        replace(probes, LEADERSHIP, "Whether this node knows its role: without one it neither runs the sessions nor"
                + " replicates them.", leadershipEvaluator(ha));
        replace(probes, SESSION_SETTINGS, "While following: whether this node's session settings are the leader's, as a failover"
                + " runs the sessions from this node's own.", sessionSettingsEvaluator(ha));
        replace(probes, ENCRYPTION, "While encryption is preferred: a replication lane in clear, its peer having encryption"
                + " disabled.", encryptionEvaluator(ha));
    }

    private static void replace(Probes probes, String name, String description, ProbeEvaluator evaluator) {
        probes.remove(name);
        probes.add(Probe.builder().name(name).description(description).evaluator(evaluator.withRaiseDelay(RAISE_DELAY)).build());
    }

    static Probe sessionProbe(ReplicatedSession session, FollowersView followers) {
        return Probe.builder()
                .name(REPLICATION)
                .description("While leading: SYNC sends the followers did not ack in time or that fewer sync followers"
                        + " than the quorum were in sync for, messages longer than half the replication log, and a"
                        + " follower refusing to replicate the session.")
                .evaluator(new SessionReplicationEvaluator(session, followers, System::nanoTime, SESSION_ALERT_EXPIRY.toNanos()))
                .onlyWhileActive(false)
                .build();
    }

    static ProbeEvaluator followersEvaluator(ReplicationFixEngineHa ha) {
        return () -> {
            if (ha.getRole() != Role.LEADER) {
                return Optional.empty();
            }
            ReplicationHaSettings settings = ha.getSettings();
            ReplicationNode node = ha.getNode();
            int lanes = node.getLanes();
            long thresholdMillis = settings.getFollowerLagAlertThreshold().toMillis();
            List<String> stalled = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            boolean anyConnected = false;
            boolean anySyncFollowerConnected = false;
            Set<String> connected = new HashSet<>();
            for (FollowerLaneStatus follower : node.followers().getLaneStatuses()) {
                if (!follower.isConnected()) {
                    continue;
                }
                anyConnected = true;
                anySyncFollowerConnected |= follower.isSyncFollower();
                connected.add(follower.getNodeId());
                String name = followerName(follower.getNodeId(), follower.getLane(), lanes, follower.isSyncFollower());
                if (follower.getLagMillis() > thresholdMillis) {
                    String problem = name + " has not applied a write for " + follower.getLagMillis() + " ms";
                    (follower.isSyncFollower() ? stalled : warnings).add(problem);
                } else if (!follower.isInSync() || follower.isSnapshotInProgress()) {
                    warnings.add(name + (follower.isSnapshotInProgress() ? " catching up by snapshot" : " not in sync")
                            + ", " + follower.getUnsentBytes() + " bytes unsent");
                }
            }
            List<String> droppedSyncFollowers = new ArrayList<>();
            for (DroppedFollower dropped : node.followers().getDroppedFollowers()) {
                boolean sync = isSyncFollower(settings, dropped.getNodeId());
                String problem = followerName(dropped.getNodeId(), dropped.getLane(), lanes, sync) + " dropped as too slow at "
                        + dropped.getDroppedAt()
                        + (dropped.getHeldOffUntil() == null ? "" : ", held off until " + dropped.getHeldOffUntil());
                (sync ? droppedSyncFollowers : warnings).add(problem);
                connected.add(dropped.getNodeId());
            }
            if (!droppedSyncFollowers.isEmpty()) {
                return Optional.of(Alert.major("Follower " + String.join("; ", droppedSyncFollowers) + ", a write having"
                        + " waited " + settings.getLogFullTimeout().toMillis() + " ms for it, and not back in sync: a"
                        + " failover now loses what it has not applied"));
            }
            if (!anyConnected) {
                return Optional.of(Alert.major("No follower connected for " + RAISE_DELAY.toSeconds()
                        + " s: a failover now loses the sessions' latest writes"));
            }
            if (!anySyncFollowerConnected) {
                return Optional.of(Alert.major("No sync follower connected for " + RAISE_DELAY.toSeconds()
                        + " s, only followers SYNC sends do not wait for: a failover now may lose the sessions' latest"
                        + " writes"));
            }
            if (!stalled.isEmpty()) {
                return Optional.of(Alert.major("Follower " + String.join("; ", stalled)
                        + ": a failover now loses what it has not applied"));
            }
            for (String peer : settings.getPeers().keySet()) {
                if (!peer.equals(settings.getNodeId()) && !connected.contains(peer)) {
                    warnings.add(followerName(peer, 0, 1, isSyncFollower(settings, peer)) + " not connected");
                }
            }
            return warnings.isEmpty() ? Optional.empty() : Optional.of(Alert.warning("Follower " + String.join("; ", warnings)));
        };
    }

    private static boolean isSyncFollower(ReplicationHaSettings settings, String nodeId) {
        return settings.getSyncFollowers().isEmpty() || settings.getSyncFollowers().contains(nodeId);
    }

    /**
     * Marks a follower {@code SYNC} sends do not wait for, as a failover to it may lose what they acknowledged.
     */
    private static String followerName(@Nullable String nodeId, int lane, int lanes, boolean syncFollower) {
        return laneName(nodeId, lane, lanes) + (syncFollower ? "" : " (async)");
    }

    private static String laneName(@Nullable String nodeId, int lane, int lanes) {
        return lanes == 1 ? String.valueOf(nodeId) : nodeId + " lane " + lane;
    }

    static ProbeEvaluator leaderConnectionEvaluator(ReplicationFixEngineHa ha) {
        return new LeaderConnectionEvaluator(ha);
    }

    static ProbeEvaluator sessionSettingsEvaluator(ReplicationFixEngineHa ha) {
        return () -> {
            List<HaStatus.SessionSettingsDifference> differences = ha.getReplicaSessions().getSettingsComparison().getDifferences();
            if (differences.isEmpty()) {
                return Optional.empty();
            }
            HaStatus.SessionSettingsDifference first = differences.get(0);
            return Optional.of(Alert.warning(differences.size() + " session(s) differ from the leader's settings, first "
                    + first.getFixSessionId() + " in store " + first.getStore() + ": " + first.getKind()));
        };
    }

    static ProbeEvaluator encryptionEvaluator(ReplicationFixEngineHa ha) {
        return () -> {
            if (ha.getSettings().getEncryption().getMode() != EncryptionMode.PREFERRED) {
                return Optional.empty();
            }
            if (ha.getRole() == Role.LEADER) {
                return clearFollowersAlert(ha);
            }
            LeaderStatus status = ha.getNode().leader().getStatus();
            if (ha.getRole() != Role.FOLLOWER || status == null || !status.isConnected() || status.getCipher() != null) {
                return Optional.empty();
            }
            return Optional.of(Alert.warning("Following the leader " + status.getLeaderId()
                    + " in clear: this node prefers encryption, the leader has it disabled"));
        };
    }

    private static Optional<Alert> clearFollowersAlert(ReplicationFixEngineHa ha) {
        ReplicationNode node = ha.getNode();
        List<String> clear = new ArrayList<>();
        for (FollowerLaneStatus follower : node.followers().getLaneStatuses()) {
            if (follower.isConnected() && follower.getCipher() == null) {
                clear.add(laneName(follower.getNodeId(), follower.getLane(), node.getLanes()));
            }
        }
        return clear.isEmpty() ? Optional.empty() : Optional.of(Alert.warning("Replicating in clear to follower "
                + String.join(", ", clear) + ": this node prefers encryption, the follower has it disabled"));
    }

    static ProbeEvaluator leadershipEvaluator(ReplicationFixEngineHa ha) {
        return () -> {
            if (ha.getRole() != Role.UNKNOWN) {
                return Optional.empty();
            }
            boolean leadsUnproven = ha.isElectedLeader() && !ha.isLeadershipValid();
            return Optional.of(Alert.critical((leadsUnproven ? "Elected leader but unable to prove it" : "No role")
                    + " for " + RAISE_DELAY.toSeconds() + " s: the sessions run nowhere unless the other node leads"));
        };
    }

    private static String leaderId(ReplicationFixEngineHa ha) {
        LeaderElection election = ha.getElection();
        return election == null ? "unknown" : election.getLeaderId().orElse("unknown");
    }

    /**
     * Tells a leader refusing this node's id, another node being connected under it, from a leader out of reach: only
     * the closes counted since this node was last connected say which.
     */
    static final class LeaderConnectionEvaluator implements ProbeEvaluator {

        private static final String DUPLICATE_NODE_ID = CloseReason.DUPLICATE_NODE_ID.name();

        private final ReplicationFixEngineHa ha;
        private @Nullable ReplicationNode node;
        private long duplicateClosesWhenConnected;

        LeaderConnectionEvaluator(ReplicationFixEngineHa ha) {
            this.ha = ha;
        }

        @Override
        public Optional<Alert> evaluate() {
            if (ha.getRole() != Role.FOLLOWER) {
                return Optional.empty();
            }
            ReplicationNode current = ha.getNode();
            if (current != node) {
                node = current;
                duplicateClosesWhenConnected = 0;
            }
            LeaderView leader = current.leader();
            LeaderStatus status = leader.getStatus();
            if (status == null) {
                return Optional.of(Alert.major("No address for the leader, " + leaderId(ha) + ", to replicate from"));
            }
            long duplicateCloses = leader.getCloses().getOrDefault(DUPLICATE_NODE_ID, 0L);
            if (status.isConnected()) {
                duplicateClosesWhenConnected = duplicateCloses;
                return status.isInSync() ? Optional.empty()
                        : Optional.of(Alert.warning("Connected to the leader, but not holding every replicated session in full"));
            }
            if (duplicateCloses > duplicateClosesWhenConnected) {
                return Optional.of(Alert.major("Refused by the leader " + status.getAddress() + ": another node is"
                        + " connected under this node's id, " + ha.getSettings().getNodeId()));
            }
            return Optional.of(Alert.major("Not connected to the leader " + status.getAddress()
                    + " for " + RAISE_DELAY.toSeconds() + " s"));
        }
    }

    /**
     * Reads what the session's thread records, so it holds no state of its own and a rebuilt session's probe takes
     * over from the previous one's.
     */
    @Value
    static class SessionReplicationEvaluator implements ProbeEvaluator {

        ReplicatedSession session;
        FollowersView followers;
        LongSupplier nanoClock;
        long expiryNanos;

        @Override
        public Optional<Alert> evaluate() {
            List<String> problems = null;
            for (Map.Entry<String, StreamAnswer> refusal : followers.getStreamRefusals(session.getKey()).entrySet()) {
                problems = add(problems, "follower " + refusal.getKey() + " refuses to replicate it: "
                        + NotReplicatedReason.of(refusal.getValue()));
            }
            long now = nanoClock.getAsLong();
            if (session.getTimedOutSends() > 0 && now - session.getLastTimedOutNanos() < expiryNanos) {
                problems = add(problems,
                        session.getTimedOutSends() + " SYNC sends went out without the followers' acks in time");
            }
            if (session.getNotEnoughFollowersSends() > 0 && now - session.getLastNotEnoughFollowersNanos() < expiryNanos) {
                problems = add(problems, session.getNotEnoughFollowersSends() + " SYNC sends went out unacknowledged, fewer"
                        + " sync followers being in sync than the session's quorum");
            }
            if (session.getUnreplicatedEvents() > 0 && now - session.getLastUnreplicatedNanos() < expiryNanos) {
                problems = add(problems, session.getUnreplicatedEvents()
                        + " messages longer than half the replication log, missing on the followers");
            }
            return problems == null ? Optional.empty() : Optional.of(Alert.major(String.join("; ", problems)));
        }

        private static List<String> add(@Nullable List<String> problems, String problem) {
            List<String> added = problems == null ? new ArrayList<>(2) : problems;
            added.add(problem);
            return added;
        }
    }
}
