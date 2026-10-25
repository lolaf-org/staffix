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

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.ha.FixEngineHa;
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.api.ha.LeaderElection;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.ha.follower.Replica;
import org.lolaf.staffix.ha.follower.ReplicationReceiver;
import org.lolaf.staffix.ha.leader.FollowerLink;
import org.lolaf.staffix.ha.leader.Followers;
import org.lolaf.staffix.ha.leader.ReplicationPublisher;
import org.lolaf.staffix.ha.log.ReplicationLog;
import org.lolaf.staffix.ha.wire.SessionStatus;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Replicates the sessions' stores from the leader to its follower, and runs the engine's sessions only while this
 * node leads: a follower keeps them unbuilt and replays the leader's writes into their stores instead.
 *
 * <p>The election's role is followed on a thread of its own, one change at a time. Promotion stops following,
 * moves every replicated session's outgoing seqnum ahead, closes the replica's stores, starts the publisher, then
 * activates the engine; demotion deactivates the engine first, so a node that no longer leads stops writing before
 * anything else.
 */
@Slf4j
@Getter
public class ReplicationFixEngineHa extends Startable.SimpleStartable<FixEngineHa> implements FixEngineHa {

    private static final Duration ROLE_CHANGE_DEADLINE = Duration.ofSeconds(10);

    private final ReplicationHaSettings settings;
    private final ReplicationLog replicationLog;
    private final ReplicatedSessions sessions = new ReplicatedSessions();
    private final Followers followers = new Followers();
    private volatile EngineAccess engine;
    private volatile Replica replica;
    private volatile LeaderElection election;
    private ScheduledExecutorService roleThread;
    private volatile Role followedRole = Role.UNKNOWN;
    private long followedEpoch = -1;
    private @Nullable InetSocketAddress followedLeader;
    private volatile @Nullable Instant roleSince;
    private volatile @Nullable ReplicationPublisher publisher;
    private volatile @Nullable ReplicationReceiver receiver;

    public ReplicationFixEngineHa(ReplicationHaSettings settings) {
        this.settings = settings;
        this.replicationLog = new ReplicationLog(settings.getLogCapacity());
    }

    @Override
    public ReplicatingStore replicatingStore(FixSessionSettings sessionSettings, FixSessionMessagesStore store) {
        return new ReplicatingFixSessionMessagesStore(store, replicationLog, sessions.register(sessionSettings, store), followers);
    }

    @Override
    public void bind(EngineAccess engine) {
        this.engine = engine;
        this.replica = new Replica(engine);
    }

    @Override
    public HaStatus getStatus() {
        LeaderElection currentElection = election;
        Role role = followedRole;
        HaStatus.HaStatusBuilder status = HaStatus.builder()
                .nodeId(settings.getNodeId())
                .role(role)
                .epoch(currentElection == null ? -1 : currentElection.getEpoch())
                .leaderId(currentElection == null ? null : currentElection.getLeaderId().orElse(null))
                .leadershipValid(currentElection != null && currentElection.getRole() == Role.LEADER
                        && currentElection.isLeadershipValid())
                .leaderElection(currentElection == null ? "" : currentElection.getClass().getSimpleName())
                .roleSince(roleSince);
        if (role == Role.LEADER) {
            for (FollowerLink link : followers.all()) {
                status.follower(link.status(sessions.count()));
            }
        }
        ReplicationReceiver currentReceiver = receiver;
        if (role == Role.FOLLOWER && currentReceiver != null) {
            status.leader(currentReceiver.status());
        }
        return status.build();
    }

    @Override
    public List<HaSessionStatus> getSessionsStatus() {
        EngineAccess currentEngine = engine;
        if (currentEngine == null) {
            return List.of();
        }
        Role role = followedRole;
        List<HaSessionStatus> statuses = new ArrayList<>();
        for (FixSessionSettings sessionSettings : currentEngine.getFixSessionsSettings()) {
            HaSessionSettings haSettings = sessionSettings.getHaSessionSettings();
            if (haSettings.getReplicationMode() == ReplicationMode.NONE) {
                continue;
            }
            HaSessionStatus.HaSessionStatusBuilder status = HaSessionStatus.builder()
                    .fixSessionId(sessionSettings.getFixSessionId())
                    .fixSessionType(sessionSettings.getFixSessionType())
                    .replicationMode(haSettings.getReplicationMode())
                    .replicationAckTimeout(haSettings.getReplicationAckTimeout())
                    .nextIncomingSeqNum(-1)
                    .nextOutgoingSeqNum(-1);
            if (role == Role.LEADER) {
                describeLed(sessionSettings, status);
            } else if (role == Role.FOLLOWER) {
                replica.describe(sessionSettings.getFixSessionType(), sessionSettings.getFixSessionId().toString(), status);
            }
            statuses.add(status.build());
        }
        return statuses;
    }

    private void describeLed(FixSessionSettings sessionSettings, HaSessionStatus.HaSessionStatusBuilder status) {
        ReplicatedSession session = sessions.find(sessionSettings.getFixSessionId(), sessionSettings.getFixSessionType());
        if (session == null) {
            return;
        }
        boolean inSync = false;
        boolean sharedStorage = false;
        String refusal = null;
        for (FollowerLink link : followers.all()) {
            SessionStatus reported = link.getReportedStatus(session.getKey());
            inSync |= link.isInSync(session.getKey());
            sharedStorage |= reported == SessionStatus.SHARED_STORAGE;
            if (reported == SessionStatus.UNKNOWN_SESSION || reported == SessionStatus.UNKNOWN_STORAGE) {
                refusal = reported.name();
            }
        }
        status.inSync(inSync)
                .sharedStorage(sharedStorage)
                .notReplicatedReason(inSync ? null : refusal)
                .unprotected(session.isUnprotected())
                .unacknowledgedSends(session.getUnacknowledgedSends());
    }

    @Override
    protected void startMe() {
        if (settings.getLeaderElection() == null) {
            throw new IllegalStateException("Replication high availability of node " + settings.getNodeId()
                    + " needs a leaderElection to tell which node runs the sessions");
        }
        if (!settings.getPeers().containsKey(settings.getNodeId())) {
            throw new IllegalStateException("Replication high availability peers " + settings.getPeers().keySet()
                    + " have no address for this node, " + settings.getNodeId());
        }
        election = settings.getLeaderElection().instance();
        roleThread = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "staffix-ha-role-" + settings.getNodeId());
            thread.setDaemon(true);
            return thread;
        });
        election.addListener((previous, current, epoch) -> {
            try {
                roleThread.execute(this::followRole);
            } catch (RejectedExecutionException stopped) {
                // the election outlives this engine's role thread by the time it takes to stop
            }
        });
        election.start();
        roleThread.execute(this::followRole);
        long checkMillis = settings.getHeartbeatInterval().toMillis();
        roleThread.scheduleWithFixedDelay(this::followRole, checkMillis, checkMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void stopMe(Deadline stopDeadline) {
        roleThread.shutdownNow();
        try {
            if (!roleThread.awaitTermination(Math.max(1, stopDeadline.getRemainingTime().toMillis()), TimeUnit.MILLISECONDS)) {
                log.warn("The role thread of node {} is still busy at the stop deadline", settings.getNodeId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (followedRole == Role.LEADER && publisher != null) {
            // the engine stopped the sessions before this, so what they last wrote is all in the log
            publisher.handOver(Deadline.of(settings.getHandOverTimeout()));
        }
        stopPublisher(stopDeadline);
        stopReceiver(stopDeadline);
        replica.close(stopDeadline);
        election.stop(stopDeadline);
    }

    /**
     * Brings this node in line with the election. A leader that can no longer prove it leads is treated as no longer
     * leading, before another node takes over its sessions.
     */
    private void followRole() {
        try {
            Role role = election.getRole();
            if (role == Role.LEADER && !election.isLeadershipValid()) {
                role = Role.UNKNOWN;
            }
            switch (role) {
                case LEADER:
                    lead(election.getEpoch());
                    break;
                case FOLLOWER:
                    follow(election.getEpoch(), leaderAddress());
                    break;
                default:
                    stand();
            }
        } catch (RuntimeException e) {
            log.error("Node {} failed to follow its leader election", settings.getNodeId(), e);
        }
    }

    private @Nullable InetSocketAddress leaderAddress() {
        Optional<InetSocketAddress> advertised = election.getLeaderEndpoint();
        return advertised.orElseGet(() -> election.getLeaderId().map(settings.getPeers()::get).orElse(null));
    }

    private void lead(long epoch) {
        if (followedRole == Role.LEADER && followedEpoch == epoch) {
            return;
        }
        Deadline deadline = Deadline.of(ROLE_CHANGE_DEADLINE);
        if (followedRole == Role.LEADER) {
            engine.deactivate();
        }
        stopReceiver(deadline);
        stopPublisher(deadline);
        if (followedRole != Role.LEADER) {
            if (!replica.isComplete()) {
                log.warn("Node {} leads at epoch {} with sessions its follower copy did not hold in full", settings.getNodeId(), epoch);
            }
            replica.jumpOutgoingSeqNums(settings.getPromotionOutgoingSeqNumJump());
        }
        replica.close(deadline);
        publisher = new ReplicationPublisher(settings.getPeers().get(settings.getNodeId()), replicationLog, sessions, followers,
                () -> epoch, settings.getNodeId(), settings.getHeartbeatInterval(), settings.getSnapshotMaxMessages());
        publisher.start();
        engine.activate();
        changed(Role.LEADER, epoch, null);
    }

    private void follow(long epoch, @Nullable InetSocketAddress leader) {
        if (followedRole == Role.FOLLOWER && followedEpoch == epoch && sameAddress(leader)) {
            return;
        }
        Deadline deadline = Deadline.of(ROLE_CHANGE_DEADLINE);
        if (followedRole == Role.LEADER) {
            engine.deactivate();
            stopPublisher(deadline);
        }
        stopReceiver(deadline);
        if (leader == null) {
            log.warn("Node {} follows at epoch {} but has no address for its leader", settings.getNodeId(), epoch);
        } else {
            receiver = new ReplicationReceiver(leader, replica, election::getEpoch, settings.getNodeId(),
                    replicationLog.getMaxEventFrameBytes(), settings.getReconnectInterval());
            receiver.start();
        }
        changed(Role.FOLLOWER, epoch, leader);
    }

    private void stand() {
        if (followedRole == Role.UNKNOWN) {
            return;
        }
        Deadline deadline = Deadline.of(ROLE_CHANGE_DEADLINE);
        if (followedRole == Role.LEADER) {
            engine.deactivate();
            stopPublisher(deadline);
        }
        stopReceiver(deadline);
        changed(Role.UNKNOWN, -1, null);
    }

    private boolean sameAddress(@Nullable InetSocketAddress leader) {
        return leader == null ? followedLeader == null : leader.equals(followedLeader);
    }

    private void changed(Role role, long epoch, @Nullable InetSocketAddress leader) {
        log.info("Node {} is now {} at epoch {}{}", settings.getNodeId(), role, epoch, leader == null ? "" : ", following " + leader);
        followedEpoch = epoch;
        followedLeader = leader;
        roleSince = Instant.now();
        followedRole = role;
    }

    private void stopPublisher(Deadline deadline) {
        if (publisher != null) {
            publisher.stop(deadline);
            publisher = null;
        }
    }

    private void stopReceiver(Deadline deadline) {
        if (receiver != null) {
            receiver.stop(deadline);
            receiver = null;
        }
    }

    public static class ReplicationFixEngineHaFactory implements FixEngineHa.FixEngineHaFactory<ReplicationHaSettings> {

        @Override
        public Class<ReplicationHaSettings> getSettingsClass() {
            return ReplicationHaSettings.class;
        }

        @Override
        public FixEngineHa newInstance(ReplicationHaSettings settings) {
            return new ReplicationFixEngineHa(settings);
        }
    }
}
