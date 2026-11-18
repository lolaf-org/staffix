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
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.SwitchoverOutcome;
import org.lolaf.staffix.api.ha.LeaderElection;
import org.lolaf.staffix.api.ha.LeaderElection.Role;

import java.time.Duration;

/**
 * A planned move of leadership to the follower: waits for a follower ready to lead, logs the sessions out, hands the
 * log over, then transfers leadership. A step that fails leaves this node leading again at the same epoch. Runs on the
 * role thread, so no role change interleaves.
 */
@Slf4j
@RequiredArgsConstructor
final class Switchover {

    private final ReplicationFixEngineHa ha;

    SwitchoverOutcome run() {
        ReplicationHaSettings settings = ha.getSettings();
        LeaderElection election = ha.getElection();
        if (ha.getRole() != Role.LEADER) {
            throw new IllegalStateException("Only the leader switches over; node " + settings.getNodeId() + " is " + ha.getRole());
        }
        if (!election.isTransferLeadershipSupported()) {
            throw new IllegalStateException("The leader election " + election.getClass().getSimpleName()
                    + " cannot transfer leadership");
        }
        if (!awaitFollowerReadyToLead()) {
            return notReadyToLead();
        }
        // a follower ready at the last moment must not leave the logged out sessions no time to hand over
        Deadline deadline = Deadline.of(settings.switchingTimeout());
        log.info("Node {} switches over at epoch {}: logging its sessions out", settings.getNodeId(), ha.getRoleEpoch());
        ha.getEngine().deactivateWithLogout(deadline);
        if (!ha.getServer().handOver(deadline)) {
            resumeLeading("its follower was not sent every write");
            return SwitchoverOutcome.NOT_HANDED_OVER;
        }
        // a follower reconnecting to this node, which still leads, would start a stream and forget it was handed over
        ha.stopServer(deadline);
        boolean transferring = election.transferLeadership();
        if (transferring) {
            deadline.waitAsLongAs(() -> election.getRole() == Role.LEADER);
        }
        if (election.getRole() == Role.LEADER) {
            resumeLeading(transferring ? "leadership did not move in time" : "the leader election did not transfer leadership");
            return SwitchoverOutcome.NOT_TRANSFERRED;
        }
        log.info("Node {} handed leadership over", settings.getNodeId());
        return SwitchoverOutcome.TRANSFERRED;
    }

    private boolean awaitFollowerReadyToLead() {
        Duration timeout = ha.getSettings().getSwitchoverTimeout();
        return timeout.isZero() ? isAnyFollowerReadyToLead() : Deadline.of(timeout).waitAsLongAs(() -> !isAnyFollowerReadyToLead());
    }

    private boolean isAnyFollowerReadyToLead() {
        return isAnyFollowerInSync() && ha.getFollowers().getPendingWrites() <= ha.getSettings().getSwitchoverMaxPendingWrites();
    }

    private boolean isAnyFollowerInSync() {
        return ha.getFollowers().isAnyFollowerInSync(ha.getSessions().count(), ha.getReplicationLogs().count());
    }

    private SwitchoverOutcome notReadyToLead() {
        String nodeId = ha.getSettings().getNodeId();
        if (isAnyFollowerInSync()) {
            log.warn("Node {} does not switch over: its follower has {} writes left to store, more than {}", nodeId,
                    ha.getFollowers().getPendingWrites(), ha.getSettings().getSwitchoverMaxPendingWrites());
            return SwitchoverOutcome.FOLLOWER_STORE_BEHIND;
        }
        log.warn("Node {} does not switch over: no follower holds every replicated session", nodeId);
        return SwitchoverOutcome.NO_FOLLOWER_IN_SYNC;
    }

    /**
     * Same leadership, so no seqnum jump; the follower reconnects, and its new stream clears the hand over.
     */
    private void resumeLeading(String reason) {
        log.warn("Node {} leads its sessions again at epoch {}: {}", ha.getSettings().getNodeId(), ha.getRoleEpoch(), reason);
        if (ha.getServer() == null) {
            ha.startServer(ha.getRoleEpoch());
        }
        ha.getEngine().activate();
    }
}
