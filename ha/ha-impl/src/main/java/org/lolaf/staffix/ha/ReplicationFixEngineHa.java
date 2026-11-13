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
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.alerting.Probes;
import org.lolaf.staffix.api.ha.*;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.staffix.api.monitoring.Timer;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.ha.follower.ReplicaSessions;
import org.lolaf.staffix.ha.follower.ReplicationClient;
import org.lolaf.staffix.ha.leader.Followers;
import org.lolaf.staffix.ha.leader.ReplicationServer;
import org.lolaf.staffix.ha.leader.ReplicationStats;
import org.lolaf.staffix.ha.leader.SlowFollowers;
import org.lolaf.staffix.ha.log.LogFullWait;
import org.lolaf.staffix.ha.log.ReplicationLogs;

import java.util.ArrayList;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.*;

/**
 * Replicates the sessions' stores from the leader to its follower, and runs the engine's sessions only while this
 * node leads: a follower keeps them unbuilt and replays the leader's writes into their stores instead.
 *
 * <p>The election's role is followed on a thread of its own, one change at a time. Promotion stops following,
 * moves every replicated session's outgoing seqnum ahead, closes the replica's stores, starts the server, then
 * activates the engine; demotion deactivates the engine first, so a node that no longer leads stops writing before
 * anything else.
 */
@Slf4j
@Getter
public class ReplicationFixEngineHa extends Startable.SimpleStartable<FixEngineHa> implements FixEngineHa {

    private static final Duration ROLE_CHANGE_DEADLINE = Duration.ofSeconds(10);

    private final ReplicationHaSettings settings;
    private final ReplicationLogs replicationLogs;
    private final ReplicatedSessions sessions;
    private final Followers followers;
    private final LateTimer syncReceivedAckLatency = new LateTimer();
    private final LateTimer syncAppliedAckLatency = new LateTimer();
    private final LateTimer receivedWriteLatency = new LateTimer();
    private final PromotionSeqNumJumps promotionJumps;
    private final HaStatusReport statusReport = new HaStatusReport(this);
    private final Switchover switchover = new Switchover(this);
    /**
     * What unregisters each settings store's listener.
     */
    private final List<Runnable> settingsListeners;
    private volatile ReplicationStats stats;
    private volatile EngineAccess engine;
    private volatile ReplicaSessions replicaSessions;
    private volatile LeaderElection election;
    private volatile ScheduledExecutorService roleThread;
    private @Nullable ScheduledFuture<?> leaseCheck;
    private volatile Role role = Role.UNKNOWN;
    /**
     * The election epoch {@link #role} was taken at, -1 while unknown. Written before {@code role}, so a reader of
     * {@code role} sees it.
     */
    private long roleEpoch = -1;
    private @Nullable InetSocketAddress followedLeader;
    private volatile @Nullable Instant roleSince;
    private volatile @Nullable ReplicationServer server;
    private volatile @Nullable ReplicationClient client;

    public ReplicationFixEngineHa(ReplicationHaSettings settings) {
        settings.validate();
        this.settings = settings;
        this.replicationLogs = new ReplicationLogs(settings.getReplicationLanes(), settings.getLogCapacity());
        this.sessions = new ReplicatedSessions();
        this.followers = new Followers();
        this.promotionJumps = new PromotionSeqNumJumps(settings.getPromotionOutgoingSeqNumJump());
        this.settingsListeners = new ArrayList<>();
    }

    @Override
    public ReplicatingStore replicatingStore(FixSessionSettings sessionSettings, FixSessionMessagesStore store, Probes probes) {
        ReplicatedSession known = sessions.find(sessionSettings.getFixSessionId(), sessionSettings.getFixSessionType());
        ReplicationMode previousMode = known == null ? null : known.getConfig().getReplicationMode();
        ReplicatedSession session = sessions.register(sessionSettings, store);
        ReplicationMode mode = session.getConfig().getReplicationMode();
        if (previousMode == ReplicationMode.NONE) {
            log.info("FIX session {} {} is replicated again, {}: its followers catch up by snapshot",
                    session.getFixSessionType(), session.getFixSessionId(), mode);
        }
        // between ASYNC and the SYNC modes too: a follower refuses a mode its own settings do not give
        if (previousMode != null && previousMode != mode) {
            followers.reannounce(session.getKey());
        }
        probes.add(HaProbes.sessionProbe(session, followers));
        return new ReplicatingFixSessionMessagesStore(store, replicationLogs.forSession(session.getKey()),
                new LogFullWait(settings.getLogFullTimeout()),
                session, followers, syncReceivedAckLatency, syncAppliedAckLatency, settings.getSyncAckIdleStrategy().get(),
                started -> promotionJumps.jumpOnStart(sessionSettings, started));
    }

    /**
     * Its followers are told, and stop holding it: they would otherwise count a copy that no longer moves as in sync.
     */
    @Override
    public void onSessionNotReplicated(FixSessionSettings sessionSettings) {
        promotionJumps.jumped(sessionSettings);
        stopReplicating(sessionSettings);
    }

    @Override
    public void requireSettingsStoreWritable(FixSessionsSettingsStore store) {
        if (role != Role.LEADER && isReplicated(store)) {
            throw new IllegalStateException("Node " + settings.getNodeId() + " does not lead and session settings store "
                    + store.getInstanceId() + " is replicated from the leader: change it on the engine that leads");
        }
    }

    private void stopReplicating(FixSessionSettings sessionSettings) {
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
        this.replicaSessions = new ReplicaSessions(engine);
        replicaSessions.getSettingsComparison().setApplyExecutor(this::onRoleThread);
        replicaSessions.setReceivedWriteLatency(receivedWriteLatency);
        this.stats = new ReplicationStats(new SlowFollowers(settings.getSlowFollowerHoldOff(), engine.getClock()));
    }

    @Override
    public HaStatus getStatus() {
        return statusReport.status();
    }

    @Override
    public void setElectable(boolean electable) {
        LeaderElection currentElection = election;
        if (currentElection == null || !currentElection.isElectableSupported()) {
            throw new IllegalStateException("The leader election "
                    + (currentElection == null ? "has not started" : currentElection.getClass().getSimpleName() + " cannot decline election"));
        }
        if (!electable && Role.LEADER.equals(currentElection.getRole()) && currentElection.isTransferLeadershipSupported()) {
            SwitchoverOutcome outcome = switchover();
            if (!SwitchoverOutcome.TRANSFERRED.equals(outcome)) {
                throw new IllegalStateException("Node " + settings.getNodeId() + " stays electable: its switchover ended "
                        + outcome);
            }
        }
        log.info("Node {} {} elections", settings.getNodeId(), electable ? "stands for" : "withdraws from");
        currentElection.setElectable(electable);
    }

    @Override
    public void resync() {
        requireLeadingToResync();
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
        requireLeadingToResync();
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
            return thread.submit(switchover::run).get();
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

    /**
     * As the election says now, whatever role this node acts on. Any thread.
     */
    boolean isElectedLeader() {
        LeaderElection current = election;
        return current != null && current.getRole() == Role.LEADER;
    }

    /**
     * Elected leader and able to prove it, as the election says now. Any thread.
     */
    boolean isLeadershipValid() {
        return isElectedLeader() && election.isLeadershipValid();
    }

    private void requireLeadingToResync() {
        if (role != Role.LEADER) {
            throw new IllegalStateException("Only the leader re-syncs its followers; node " + settings.getNodeId() + " is "
                    + role);
        }
    }

    @Override
    public List<HaSessionStatus> getSessionStatuses() {
        return statusReport.sessionStatuses();
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
        requireReplicableSettingsStores();
        engine.getFixSessionsSettingsStores().forEach(this::listenTo);
        settingsChanged();
        election = settings.getLeaderElection().instance();
        if (settings.getMonitoringInstanceId() != null) {
            HaMeters.Timers timers = HaMeters.register(engine.monitoring(settings.getMonitoringInstanceId()), this, stats);
            syncReceivedAckLatency.target = timers.getSyncReceivedAckLatency();
            syncAppliedAckLatency.target = timers.getSyncAppliedAckLatency();
            receivedWriteLatency.target = timers.getReceivedWriteLatency();
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
                roleThread.execute(this::followElection);
            } catch (RejectedExecutionException stopped) {
                // the election outlives this engine's role thread by the time it takes to stop
            }
        });
        election.start();
        roleThread.execute(this::followElection);
        long checkNanos = settings.getHeartbeatInterval().toNanos();
        roleThread.scheduleWithFixedDelay(this::followElection, checkNanos, checkNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    protected void stopMe(Deadline stopDeadline) {
        settingsListeners.forEach(Runnable::run);
        settingsListeners.clear();
        roleThread.shutdownNow();
        try {
            if (!roleThread.awaitTermination(Math.max(1, stopDeadline.getRemainingTime().toMillis()), TimeUnit.MILLISECONDS)) {
                log.warn("The role thread of node {} is still busy at the stop deadline", settings.getNodeId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (role == Role.LEADER && server != null) {
            // the engine stopped the sessions before this, so what they last wrote is all in the log
            server.handOver(Deadline.of(settings.getHandOverTimeout()));
        }
        stopServer(stopDeadline);
        stopClient(stopDeadline);
        replicaSessions.close(stopDeadline);
        election.stop(stopDeadline);
    }

    /**
     * Brings this node in line with the election. A leader that can no longer prove it leads is treated as no longer
     * leading, before another node takes over its sessions.
     */
    private void followElection() {
        try {
            Role electedRole = election.getRole();
            if (electedRole == Role.LEADER && !election.isLeadershipValid()) {
                electedRole = Role.UNKNOWN;
            }
            switch (electedRole) {
                case LEADER:
                    lead(election.getEpoch());
                    break;
                case FOLLOWER:
                    follow(election.getEpoch(), leaderAddress());
                    break;
                default:
                    standDown();
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
        OptionalLong leaseEnd = role == Role.LEADER ? election.getLeaseEndNanos() : OptionalLong.empty();
        if (leaseEnd.isPresent()) {
            long delay = Math.max(0, leaseEnd.getAsLong() - System.nanoTime());
            try {
                leaseCheck = roleThread.schedule(this::followElection, delay, TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException stopped) {
                // stopping: the engine deactivates the sessions itself
            }
        }
    }

    private @Nullable InetSocketAddress leaderAddress() {
        Optional<InetSocketAddress> advertised = election.getLeaderEndpoint();
        return advertised.orElseGet(() -> election.getLeaderId().map(settings.getPeers()::get).orElse(null));
    }

    private void lead(long electionEpoch) {
        if (role == Role.LEADER && roleEpoch == electionEpoch) {
            return;
        }
        replicaSessions.getSettingsComparison().clear();
        Deadline deadline = Deadline.of(ROLE_CHANGE_DEADLINE);
        if (role == Role.LEADER) {
            engine.deactivate();
        }
        stopClient(deadline);
        stopServer(deadline);
        if (role != Role.LEADER) {
            if (!replicaSessions.isInSync()) {
                log.warn("Node {} leads at epoch {} with sessions its follower copy did not hold in full", settings.getNodeId(), electionEpoch);
            }
            promotionJumps.promoted(sessionsInSettingsStores());
            replicaSessions.jumpOutgoingSeqNums(settings.getPromotionOutgoingSeqNumJump()).forEach(promotionJumps::jumped);
        }
        replicaSessions.close(deadline);
        startServer(electionEpoch);
        engine.activate();
        roleChanged(Role.LEADER, electionEpoch, null);
    }

    private void settingsChanged() {
        sessions.getSettingsDigests().publish(engine.getFixSessionsSettingsStores(), this::isReplicated);
        replicaSessions.getSettingsComparison().compare();
    }

    private void requireReplicableSettingsStores() {
        for (String instanceId : settings.getReplicatedSettingsStores()) {
            FixSessionsSettingsStore store = engine.getFixSessionsSettingsStores().stream()
                    .filter(candidate -> candidate.getInstanceId().equals(instanceId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("replicatedSettingsStores names " + instanceId
                            + ", which is not a session settings store of this engine"));
            if (store.getStorageIdentity().isEmpty()) {
                throw new IllegalStateException("replicatedSettingsStores names " + instanceId + ", which cannot be replicated:"
                        + " it cannot tell whether it shares its storage with the other node, or holds its sessions in memory only");
            }
        }
    }

    void startServer(long electionEpoch) {
        server = new ReplicationServer(settings.getPeers().get(settings.getNodeId()), replicationLogs, sessions, followers,
                () -> electionEpoch, settings.getNodeId(), settings.getHeartbeatInterval(), settings.getFollowerStallTimeout(),
                settings.getSendBuffers(), settings.getSnapshotChunkBytes(),
                settings.getSenderIdleStrategy(), settings.getSelectStrategy().get(), stats);
        server.start();
    }

    private void follow(long electionEpoch, @Nullable InetSocketAddress leader) {
        if (role == Role.FOLLOWER && roleEpoch == electionEpoch && sameAddress(leader)) {
            return;
        }
        Deadline deadline = Deadline.of(ROLE_CHANGE_DEADLINE);
        if (role == Role.LEADER) {
            promotionJumps.demoted();
            engine.deactivate();
            stopServer(deadline);
        }
        stopClient(deadline);
        if (leader == null) {
            log.warn("Node {} follows at epoch {} but has no address for its leader", settings.getNodeId(), electionEpoch);
        } else {
            client = new ReplicationClient(leader, replicaSessions, election::getEpoch, settings.getNodeId(),
                    replicationLogs.getMaxEventFrameBytes(), settings.getReconnectInterval(), settings.getSelectStrategy().get(),
                    settings.getFollowerStoreWriterThreads(), settings.getFollowerStoreWriterIdleStrategy());
            client.start();
        }
        roleChanged(Role.FOLLOWER, electionEpoch, leader);
    }

    private void standDown() {
        if (role == Role.UNKNOWN) {
            return;
        }
        Deadline deadline = Deadline.of(ROLE_CHANGE_DEADLINE);
        if (role == Role.LEADER) {
            promotionJumps.demoted();
            engine.deactivate();
            stopServer(deadline);
        }
        stopClient(deadline);
        roleChanged(Role.UNKNOWN, -1, null);
    }

    private boolean sameAddress(@Nullable InetSocketAddress leader) {
        return leader == null ? followedLeader == null : leader.equals(followedLeader);
    }

    private void roleChanged(Role newRole, long electionEpoch, @Nullable InetSocketAddress leader) {
        log.info("Node {} is now {} at epoch {}{}", settings.getNodeId(), newRole, electionEpoch, leader == null ? "" : ", following " + leader);
        roleEpoch = electionEpoch;
        followedLeader = leader;
        roleSince = Instant.now();
        role = newRole;
    }

    void stopServer(Deadline deadline) {
        if (server != null) {
            server.stop(deadline);
            server = null;
        }
    }

    private void stopClient(Deadline deadline) {
        if (client != null) {
            client.stop(deadline);
            client = null;
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

    private boolean isReplicated(FixSessionsSettingsStore store) {
        return settings.isSessionSettingsReplicated() && store.getStorageIdentity().isPresent()
                && (settings.getReplicatedSettingsStores().isEmpty() || settings.getReplicatedSettingsStores().contains(store.getInstanceId()));
    }

    private void onRoleThread(Runnable task) {
        try {
            roleThread.execute(task);
        } catch (RejectedExecutionException e) {
            log.debug("Stopped before applying the leader's session settings");
        }
    }

    private List<FixSessionSettings> sessionsInSettingsStores() {
        List<FixSessionSettings> configured = new ArrayList<>();
        for (FixSessionsSettingsStore store : engine.getFixSessionsSettingsStores()) {
            configured.addAll(store.getSettings());
        }
        return configured;
    }

    private void listenTo(FixSessionsSettingsStore store) {
        SettingsListener listener = new SettingsListener(store.getInstanceId());
        store.register(listener);
        settingsListeners.add(() -> store.unregister(listener));
    }

    @RequiredArgsConstructor
    private final class SettingsListener implements FixSessionsSettingsStore.Listener {

        private final String store;

        @Override
        public void onAddedSession(FixSessionSettings settings) {
            sessions.getSettingsDigests().onAdded(store, settings);
            settingsChanged();
        }

        @Override
        public void onRemovedSession(FixSessionSettings settings) {
            sessions.getSettingsDigests().onRemoved(store, settings);
            stopReplicating(settings);
            settingsChanged();
        }

        @Override
        public void onUpdatedSession(FixSessionSettings oldSettings, FixSessionSettings newSettings) {
            settingsChanged();
        }
    }
}
