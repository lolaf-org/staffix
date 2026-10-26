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
package org.lolaf.staffix.ha.primos;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.primos.Leader;
import org.lolaf.primos.PrimosListener;
import org.lolaf.primos.PrimosNode;
import org.lolaf.primos.PrimosNodeBuilder;
import org.lolaf.primos.RoleAndTerm;
import org.lolaf.primos.store.FileStateStore;
import org.lolaf.primos.transport.UdpTransport;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.LeaderElection;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A primos node seen as a {@link LeaderElection}: its term is the epoch, its node id the leader id.
 *
 * <p>A follower that knows no leader, a candidate and a failed node are all {@link Role#UNKNOWN}: none can tell the
 * engine where to replicate from. Leadership is valid while a quorum confirmed it less than one minimum election
 * timeout ago, since no successor can be elected sooner. Listeners are also called when the leader changes and the
 * role does not.
 */
@Slf4j
public final class PrimosLeaderElection implements LeaderElection {

    private final PrimosNode node;
    private final boolean owned;
    private final boolean leadershipControl;
    private final long leaseNanos;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Role reportedRole = Role.UNKNOWN;
    private volatile boolean started;

    PrimosLeaderElection(PrimosNode node, boolean owned, boolean leadershipControl) {
        this.node = node;
        this.owned = owned;
        this.leadershipControl = owned || leadershipControl;
        this.leaseNanos = node.config().timings().electionTimeoutMin().toNanos();
        node.addListener(new PrimosListener() {
            @Override
            public void onRoleChanged(org.lolaf.primos.Role previous, org.lolaf.primos.Role current, long term) {
                if (current == org.lolaf.primos.Role.FAILED) {
                    log.error("primos node {} failed: this engine cannot lead until the process restarts", node.config().nodeId());
                }
                changed();
            }

            @Override
            public void onLeaderChanged(Optional<Leader> leader, long term) {
                changed();
            }
        });
    }

    private void changed() {
        Role previous = reportedRole;
        Role current = getRole();
        reportedRole = current;
        long epoch = getEpoch();
        listeners.forEach(listener -> listener.onRoleChanged(previous, current, epoch));
    }

    @Override
    public Role getRole() {
        RoleAndTerm roleAndTerm = node.roleAndTerm();
        switch (roleAndTerm.role()) {
            case LEADER:
                return Role.LEADER;
            case FOLLOWER:
                return node.leader().filter(leader -> !leader.isSelf()).isPresent() ? Role.FOLLOWER : Role.UNKNOWN;
            default:
                return Role.UNKNOWN;
        }
    }

    @Override
    public long getEpoch() {
        return node.roleAndTerm().term();
    }

    @Override
    public Optional<String> getLeaderId() {
        return node.leader().map(leader -> String.valueOf(leader.id()));
    }

    @Override
    public boolean isLeadershipValid() {
        OptionalLong confirmed = node.leadershipConfirmedNanos();
        return confirmed.isPresent() && System.nanoTime() - confirmed.getAsLong() < leaseNanos;
    }

    @Override
    public boolean isTransferLeadershipSupported() {
        return leadershipControl;
    }

    /**
     * Primos's own transfer: this node keeps leading until its successor campaigns.
     */
    @Override
    public boolean transferLeadership() {
        requireLeadershipControl("transfer leadership");
        return node.transferLeadership();
    }

    @Override
    public boolean isCandidacySupported() {
        return leadershipControl;
    }

    @Override
    public void setCandidate(boolean candidate) {
        requireLeadershipControl("decline candidacy");
        node.setCandidate(candidate);
    }

    @Override
    public boolean isCandidate() {
        return node.isCandidate();
    }

    private void requireLeadershipControl(String action) {
        if (!leadershipControl) {
            throw new UnsupportedOperationException("The application's primos node " + node.config().nodeId()
                    + " cannot " + action + " through staffix unless leadershipControl allows it");
        }
    }

    @Override
    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    @Override
    public LeaderElection start() {
        if (owned) {
            node.start();
        }
        started = true;
        return this;
    }

    /**
     * A leader staffix runs steps down by stopping, which hands leadership over; one the application runs is left
     * alone.
     */
    @Override
    public LeaderElection stop(Deadline stopDeadline) {
        if (owned) {
            node.stop();
        }
        started = false;
        return this;
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    public static class PrimosLeaderElectionFactory implements LeaderElectionFactory<PrimosLeaderElectionSettings> {

        @Override
        public Class<PrimosLeaderElectionSettings> getSettingsClass() {
            return PrimosLeaderElectionSettings.class;
        }

        /**
         * @throws IllegalArgumentException unless exactly one of a node or a cluster is given, the latter with its
         *                                  state directory and bind address
         */
        @Override
        public LeaderElection newInstance(PrimosLeaderElectionSettings settings) {
            if ((settings.getNode() == null) == (settings.getCluster() == null)) {
                throw new IllegalArgumentException("A primos leader election takes exactly one of a node, which the "
                        + "application runs, or a cluster, for a node staffix runs");
            }
            if (settings.getNode() != null) {
                return new PrimosLeaderElection(settings.getNode(), false, settings.isLeadershipControl());
            }
            if (settings.getStateDirectory() == null || settings.getBindAddress() == null) {
                throw new IllegalArgumentException("A primos node staffix runs needs a stateDirectory, to remember its "
                        + "term and vote across restarts, and a bindAddress");
            }
            return new PrimosLeaderElection(ownedNode(settings), true, true);
        }

        private static PrimosNode ownedNode(PrimosLeaderElectionSettings settings) {
            byte[] secret = settings.getClusterSecret();
            if (secret == null) {
                log.warn("primos cluster {} runs without a clusterSecret: its datagrams are neither signed nor checked",
                        settings.getCluster().applicationName());
            }
            PrimosNodeBuilder builder = PrimosNode.builder()
                    .clusterConfig(settings.getCluster())
                    .store(() -> new FileStateStore(settings.getStateDirectory()))
                    .transport(UdpTransport.builder()
                            .bindAddress(settings.getBindAddress())
                            .peers(settings.getCluster().allPeers())
                            .clusterName(settings.getCluster().applicationName())
                            // learning a peer's address from its traffic is safe only when it cannot be forged
                            .addressLearning(secret != null));
            if (secret != null) {
                builder.clusterSecret(secret);
            }
            return builder.build();
        }
    }
}
