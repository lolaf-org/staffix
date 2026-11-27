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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.replicos.FollowerLaneStatus;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.SwitchoverOutcome;
import org.lolaf.staffix.api.ha.LeaderElection;
import org.lolaf.staffix.api.ha.LeaderElection.Role;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A planned move of leadership to a follower: waits for a sync follower ready to lead, logs the sessions out, hands the
 * log over, then transfers leadership to that follower, the one with the fewest writes left to store. A step that fails
 * leaves this node leading again at the same epoch. Runs on the role thread, so no role change interleaves.
 */
@Slf4j
@RequiredArgsConstructor
final class Switchover {

    private final ReplicationFixEngineHa ha;

    /**
     * @param successor the follower to hand leadership to; null for the sync follower with the fewest writes left
     */
    SwitchoverOutcome run(@Nullable String successor) {
        ReplicationHaSettings settings = ha.getSettings();
        LeaderElection election = ha.getElection();
        if (ha.getRole() != Role.LEADER) {
            throw new IllegalStateException("Only the leader switches over; node " + settings.getNodeId() + " is " + ha.getRole());
        }
        if (!election.isTransferLeadershipSupported()) {
            throw new IllegalStateException("The leader election " + election.getClass().getSimpleName()
                    + " cannot transfer leadership");
        }
        Candidate ready = awaitReady(successor);
        if (ready == null) {
            return notReadyToLead(successor);
        }
        // a follower ready at the last moment must not leave the logged out sessions no time to hand over
        Deadline deadline = Deadline.of(settings.switchingTimeout());
        log.info("Node {} switches over to {} at epoch {}: logging its sessions out", settings.getNodeId(), ready.nodeId,
                ha.getRoleEpoch());
        ha.getEngine().deactivateWithLogout(deadline);
        if (!ha.getNode().handOver(deadline)) {
            resumeLeading("its follower was not sent every write");
            return SwitchoverOutcome.NOT_HANDED_OVER;
        }
        // a follower reconnecting to this node, which still leads, would start a stream and forget it was handed over
        ha.getNode().standDown(deadline);
        boolean transferring = election.transferLeadership(ready.nodeId);
        if (transferring) {
            deadline.waitAsLongAs(() -> election.getRole() == Role.LEADER);
        }
        if (election.getRole() == Role.LEADER) {
            resumeLeading(transferring ? "leadership did not move in time" : "the leader election did not transfer leadership");
            return SwitchoverOutcome.NOT_TRANSFERRED;
        }
        log.info("Node {} handed leadership over to {}", settings.getNodeId(), ready.nodeId);
        return SwitchoverOutcome.TRANSFERRED;
    }

    /**
     * The sync follower a leader stopping hands leadership to: in sync, the fewest writes left to store; null for none.
     */
    @Nullable String bestSuccessor() {
        return bestInSync(null).map(candidate -> candidate.nodeId).orElse(null);
    }

    private @Nullable Candidate awaitReady(@Nullable String successor) {
        Duration timeout = ha.getSettings().getSwitchoverTimeout();
        if (!timeout.isZero()) {
            Deadline.of(timeout).waitAsLongAs(() -> ready(successor) == null);
        }
        return ready(successor);
    }

    private @Nullable Candidate ready(@Nullable String successor) {
        return bestInSync(successor).filter(best -> best.pendingWrites <= ha.getSettings().getSwitchoverMaxPendingWrites())
                .orElse(null);
    }

    private Optional<Candidate> bestInSync(@Nullable String successor) {
        Map<String, Candidate> byNode = ha.getNode().followers().getLaneStatuses().stream()
                .filter(lane -> lane.getNodeId() != null && (successor == null || successor.equals(lane.getNodeId())))
                .filter(lane -> lane.isConnected() && lane.isInSync() && lane.isSyncFollower())
                .collect(Collectors.toMap(FollowerLaneStatus::getNodeId, Candidate::new, Candidate::plus));
        int lanes = ha.getSettings().getReplicationLanes();
        return byNode.values().stream().filter(candidate -> candidate.lanes == lanes)
                .min(Comparator.comparingLong(candidate -> candidate.pendingWrites));
    }

    private SwitchoverOutcome notReadyToLead(@Nullable String successor) {
        String nodeId = ha.getSettings().getNodeId();
        Optional<Candidate> best = bestInSync(successor);
        if (best.isPresent()) {
            log.warn("Node {} does not switch over: its sync follower {}, the most caught up, has {} writes left to store,"
                    + " more than {}", nodeId, best.get().nodeId, best.get().pendingWrites,
                    ha.getSettings().getSwitchoverMaxPendingWrites());
            return SwitchoverOutcome.FOLLOWER_STORE_BEHIND;
        }
        log.warn("Node {} does not switch over: {}", nodeId, successor == null
                ? "no sync follower holds every replicated session" : successor + " does not hold every replicated session");
        return SwitchoverOutcome.NO_FOLLOWER_IN_SYNC;
    }

    /**
     * Same leadership, so no seqnum jump; the follower reconnects, and its new stream clears the hand over.
     */
    private void resumeLeading(String reason) {
        log.warn("Node {} leads its sessions again at epoch {}: {}", ha.getSettings().getNodeId(), ha.getRoleEpoch(), reason);
        if (!ha.getNode().isLeading()) {
            ha.getNode().lead(ha.getRoleEpoch(), Deadline.of(ReplicationFixEngineHa.ROLE_CHANGE_DEADLINE));
        }
        ha.getEngine().activate();
    }

    private static final class Candidate {
        private final String nodeId;
        private final int lanes;
        private final long pendingWrites;

        Candidate(FollowerLaneStatus lane) {
            this(Objects.requireNonNull(lane.getNodeId()), 1, lane.getPendingWrites());
        }

        Candidate(String nodeId, int lanes, long pendingWrites) {
            this.nodeId = nodeId;
            this.lanes = lanes;
            this.pendingWrites = pendingWrites;
        }

        Candidate plus(Candidate other) {
            return new Candidate(nodeId, lanes + other.lanes, pendingWrites + other.pendingWrites);
        }
    }
}
