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
import org.lolaf.staffix.api.alerting.Probes;
import org.lolaf.staffix.api.ha.FixEngineHa;
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.api.ha.LeaderElection;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.staffix.api.ha.SwitchoverOutcome;
import org.lolaf.staffix.api.monitoring.Timer;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.ha.follower.Replica;
import org.lolaf.staffix.ha.follower.ReplicationReceiver;
import org.lolaf.staffix.ha.leader.FollowerLink;
import org.lolaf.staffix.ha.leader.Followers;
import org.lolaf.staffix.ha.leader.ReplicationPublisher;
import org.lolaf.staffix.ha.leader.ReplicationStats;
import org.lolaf.staffix.ha.log.ReplicationLog;
import org.lolaf.staffix.ha.wire.SessionStatus;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
    private final ReplicationStats stats = new ReplicationStats();
    private final LateTimer syncAckLatency = new LateTimer();
    private volatile EngineAccess engine;
    private volatile Replica replica;
    private volatile LeaderElection election;
    private volatile ScheduledExecutorService roleThread;
    private @Nullable ScheduledFuture<?> leaseCheck;
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
    public ReplicatingStore replicatingStore(FixSessionSettings sessionSettings, FixSessionMessagesStore store, Probes probes) {
        ReplicatedSession known = sessions.find(sessionSettings.getFixSessionId(), sessionSettings.getFixSessionType());
        boolean resumed = known != null && !known.isReplicated();
        ReplicatedSession session = sessions.register(sessionSettings, store);
        if (resumed) {
            log.info("FIX session {} {} is replicated again, {}: its followers catch up by snapshot",
                    session.getFixSessionType(), session.getFixSessionId(), session.getConfig().getReplicationMode());
            followers.reannounce(session.getKey());
        }
        probes.add(HaProbes.sessionProbe(session, followers));
        return new ReplicatingFixSessionMessagesStore(store, replicationLog, session, followers, syncAckLatency);
    }

    /**
     * Its followers are told, and stop holding it: they would otherwise count a copy that no longer moves as in sync.
     */
    @Override
    public void notReplicated(FixSessionSettings sessionSettings) {
        ReplicatedSession session = sessions.find(sessionSettings.getFixSessionId(), sessionSettings.getFixSessionType());
        if (session == null || !session.isReplicated()) {
            return;
        }
        session.update(session.getConfig().notReplicated());
        log.info("FIX session {} {} is no longer replicated", session.getFixSessionType(), session.getFixSessionId());
        followers.reannounce(session.getKey());
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
                .roleSince(roleSince)
                .transferLeadershipSupported(currentElection != null && currentElection.isTransferLeadershipSupported())
                .candidacySupported(currentElection != null && currentElection.isCandidacySupported())
                .candidate(currentElection == null || currentElection.isCandidate());
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
    public void setCandidate(boolean candidate) {
        LeaderElection currentElection = election;
        if (currentElection == null || !currentElection.isCandidacySupported()) {
            throw new IllegalStateException("The leader election "
                    + (currentElection == null ? "has not started" : currentElection.getClass().getSimpleName() + " cannot decline candidacy"));
        }
        log.info("Node {} {} elections", settings.getNodeId(), candidate ? "stands for" : "withdraws from");
        currentElection.setCandidate(candidate);
    }

    @Override
    public void resync() {
        requireLeading();
        log.info("Every replicated session is sent again to the followers by snapshot");
        for (int key = 0; key < sessions.count(); key++) {
            followers.resync(key);
        }
    }

    @Override
    public void resync(FixSessionId fixSessionId, FixSessionType fixSessionType) {
        ReplicatedSession session = sessions.find(fixSessionId, fixSessionType);
        if (session == null || !session.isReplicated()) {
            throw new IllegalArgumentException("FIX session " + fixSessionType + " " + fixSessionId + " is not replicated");
        }
        requireLeading();
        log.info("FIX session {} {} is sent again to the followers by snapshot", fixSessionType, fixSessionId);
        followers.resync(session.getKey());
    }

    /**
     * Runs on the role thread, so no role change interleaves: one the election makes meanwhile is followed once this
     * returns.
     */
    @Override
    public SwitchoverOutcome switchover() {
        ScheduledExecutorService thread = roleThread;
        if (thread == null) {
            throw new IllegalStateException("The high availability of node " + settings.getNodeId() + " has not started");
        }
        try {
            return thread.submit(this::switchOverOnRoleThread).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while node " + settings.getNodeId() + " switched over", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            }
            throw new IllegalStateException("The switchover of node " + settings.getNodeId() + " failed", e.getCause());
        }
    }

    private SwitchoverOutcome switchOverOnRoleThread() {
        LeaderElection currentElection = election;
        if (followedRole != Role.LEADER) {
            throw new IllegalStateException("Only the leader switches over; node " + settings.getNodeId() + " is " + followedRole);
        }
        if (!currentElection.isTransferLeadershipSupported()) {
            throw new IllegalStateException("The leader election " + currentElection.getClass().getSimpleName()
                    + " cannot transfer leadership");
        }
        Deadline deadline = Deadline.of(settings.getSwitchoverTimeout());
        if (!deadline.waitAsLongAs(() -> !isAnyFollowerInSyncForAll())) {
            log.warn("Node {} does not switch over: no follower holds every replicated session", settings.getNodeId());
            return SwitchoverOutcome.NO_FOLLOWER_IN_SYNC;
        }
        log.info("Node {} switches over at epoch {}: logging its sessions out", settings.getNodeId(), followedEpoch);
        engine.deactivateWithLogout(deadline);
        if (!publisher.handOver(deadline)) {
            resumeLeading("its follower was not sent every write");
            return SwitchoverOutcome.NOT_HANDED_OVER;
        }
        // a follower reconnecting to this node, which still leads, would start a stream and forget it was handed over
        stopPublisher(deadline);
        boolean transferring = currentElection.transferLeadership();
        if (!transferring || !deadline.waitAsLongAs(() -> currentElection.getRole() == Role.LEADER)) {
            if (currentElection.getRole() == Role.LEADER) {
                resumeLeading(transferring ? "leadership did not move in time" : "the leader election did not transfer leadership");
                return SwitchoverOutcome.NOT_TRANSFERRED;
            }
        }
        log.info("Node {} handed leadership over", settings.getNodeId());
        return SwitchoverOutcome.TRANSFERRED;
    }

    private boolean isAnyFollowerInSyncForAll() {
        for (FollowerLink link : followers.all()) {
            if (link.isInSyncForAll(sessions.count())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Same leadership, so no seqnum jump; the follower reconnects, and its new stream clears the hand over.
     */
    private void resumeLeading(String reason) {
        log.warn("Node {} leads its sessions again at epoch {}: {}", settings.getNodeId(), followedEpoch, reason);
        if (publisher == null) {
            startPublisher(followedEpoch);
        }
        engine.activate();
    }

    private void requireLeading() {
        if (followedRole != Role.LEADER) {
            throw new IllegalStateException("Only the leader re-syncs its followers; node " + settings.getNodeId() + " is "
                    + followedRole);
        }
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
        if (settings.getMonitoringInstanceId() != null) {
            syncAckLatency.target = HaMeters.register(engine.monitoring(settings.getMonitoringInstanceId()), this, stats);
        }
        if (settings.getAlertsPublisherInstanceId() != null) {
            HaProbes.register(engine.probes(HaProbes.COMPONENT, settings.getAlertsPublisherInstanceId(), this::isStarted), this);
        }
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
            checkAtLeaseEnd();
        } catch (RuntimeException e) {
            log.error("Node {} failed to follow its leader election", settings.getNodeId(), e);
        }
    }

    private void checkAtLeaseEnd() {
        if (leaseCheck != null) {
            leaseCheck.cancel(false);
            leaseCheck = null;
        }
        OptionalLong leaseEnd = followedRole == Role.LEADER ? election.getLeaseEndNanos() : OptionalLong.empty();
        if (leaseEnd.isPresent()) {
            long delay = Math.max(0, leaseEnd.getAsLong() - System.nanoTime());
            try {
                leaseCheck = roleThread.schedule(this::followRole, delay, TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException stopped) {
                // stopping: the engine deactivates the sessions itself
            }
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
        startPublisher(epoch);
        engine.activate();
        changed(Role.LEADER, epoch, null);
    }

    private void startPublisher(long epoch) {
        publisher = new ReplicationPublisher(settings.getPeers().get(settings.getNodeId()), replicationLog, sessions, followers,
                () -> epoch, settings.getNodeId(), settings.getHeartbeatInterval(), settings.getSnapshotMaxMessages(), stats);
        publisher.start();
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

    /**
     * Handed to stores whenever they are built, before or after the meters are registered at start.
     */
    private static final class LateTimer implements Timer {

        private Timer target = Timer.VoidTimer.getInstance();

        @Override
        public void start() {
            target.start();
        }

        @Override
        public void stop() {
            target.stop();
        }

        @Override
        public void record(long duration, TimeUnit unit) {
            target.record(duration, unit);
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
