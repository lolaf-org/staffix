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
import org.lolaf.replicos.CatchUp;
import org.lolaf.replicos.FollowerSettings;
import org.lolaf.replicos.LeaderSettings;
import org.lolaf.replicos.ReplicationNode;
import org.lolaf.replicos.spi.LatencyRecorder;
import org.lolaf.replicos.spi.ReplicationMetrics;

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
public class ReplicationFixEngineHa extends Startable.SimpleStartable<FixEngineHa> implements FixEngineHa {

    static final Duration ROLE_CHANGE_DEADLINE = Duration.ofSeconds(10);

    @Getter
    private final ReplicationHaSettings settings;
    @Getter
    private final ReplicatedSessions sessions;
    /**
     * Registered on the first start, which publishes the settings.
     */
    private volatile @Nullable SessionSettingsStream settingsStream;
    private final LateTimer syncReceivedAckLatency;
    private final LateTimer syncAppliedAckLatency;
    private final LateTimer receivedWriteLatency;
    private final LateMetrics followerTimers;
    private final PromotionSeqNumJumps promotionJumps;
    private final HaStatusReport statusReport;
    private final Switchover switchover;
    /**
     * What unregisters each settings store's listener.
     */
    private final List<Runnable> settingsListeners;
    /**
     * Built on the first {@link #bind}, which a restarted engine calls again: it keeps its logs and its streams.
     */
    @Getter
    private volatile ReplicationNode node;
    @Getter
    private volatile EngineAccess engine;
    @Getter
    private volatile ReplicaSessions replicaSessions;
    @Getter
    private volatile LeaderElection election;
    private volatile ScheduledExecutorService roleThread;
    private @Nullable ScheduledFuture<?> leaseCheck;
    @Getter
    private volatile Role role;
    /**
     * The election epoch {@link #role} was taken at, -1 while unknown. Written before {@code role}, so a reader of
     * {@code role} sees it.
     */
    @Getter
    private long roleEpoch;
    private @Nullable InetSocketAddress followedLeader;
    @Getter
    private volatile @Nullable Instant roleSince;

    public ReplicationFixEngineHa(ReplicationHaSettings settings) {
        this.syncReceivedAckLatency = new LateTimer();
        this.syncAppliedAckLatency = new LateTimer();
        this.receivedWriteLatency = new LateTimer();
        this.followerTimers = new LateMetrics(receivedWriteLatency, settings.getReplicationLanes());
        this.statusReport = new HaStatusReport(this);
        this.switchover = new Switchover(this);
        this.role = Role.UNKNOWN;
        this.roleEpoch = -1;
        settings.validate();
        this.settings = settings;
        this.sessions = new ReplicatedSessions();
        this.promotionJumps = new PromotionSeqNumJumps(settings.getPromotionOutgoingSeqNumJump());
        this.settingsListeners = new ArrayList<>();
    }

    private static void logAllocatingCipher(ReplicationNode node) {
        if (node.cipherAllocatesOnEveryFrame()) {
            log.info("HA replication cipher {} allocates on every frame, inside the JCE (a new nonce takes a new cipher "
                    + "init): replication adds garbage collections. AES/CTR/NoPadding with HmacSHA256 allocates nothing; "
                    + "see \"Encrypting the replication\" in the high availability docs", node.describeCipher());
        }
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
            node.reannounce(session.getKey());
        }
        probes.add(HaProbes.sessionProbe(session, node.followers()));
        return new ReplicatingFixSessionMessagesStore(store, node.writer(session.getKey()), session, syncReceivedAckLatency,
                syncAppliedAckLatency, started -> promotionJumps.jumpOnStart(sessionSettings, started));
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
        node.reannounce(session.getKey());
    }

    @Override
    public void bind(EngineAccess engine) {
        this.engine = engine;
        if (node != null) {
            return;
        }
        SessionSettingsComparison settingsComparison = new SessionSettingsComparison(engine);
        settingsComparison.setApplyExecutor(this::onRoleThread);
        ReplicationNode built = ReplicationNode.builder()
                .nodeId(settings.getNodeId())
                .leader(LeaderSettings.builder()
                        .bindAddress(ownAddress())
                        .streams(sessions)
                        .lanes(settings.getReplicationLanes())
                        .logFullTimeout(settings.getLogFullTimeout())
                        .slowFollowerHoldOff(settings.getSlowFollowerHoldOff())
                        .heartbeatInterval(settings.getHeartbeatInterval())
                        .followerStallTimeout(settings.getFollowerStallTimeout())
                        .syncFollowers(settings.getSyncFollowers())
                        .sendBuffers(settings.getSendBuffers())
                        .snapshotChunkBytes(settings.getSnapshotChunkBytes())
                        .senderIdleStrategy(settings.getSenderIdleStrategy())
                        .ackIdleStrategy(settings.getSyncAckIdleStrategy())
                        .build())
                .follower(FollowerSettings.builder()
                        .sinks(new FixSessionSinks(engine, settingsComparison))
                        .reconnectInterval(settings.getReconnectInterval())
                        .checkInterval(settings.getFollowerCheckInterval())
                        .sinkWriterThreads(settings.getFollowerStoreWriterThreads())
                        .sinkWriterIdleStrategy(settings.getFollowerStoreWriterIdleStrategy())
                        .build())
                .logCapacity(settings.getLogCapacity())
                .catchUp(CatchUp.byLastRecords())
                .encryption(settings.getEncryption())
                .selectStrategy(settings.getSelectStrategy())
                .clock(new EngineReplicationClock(engine.getClock()))
                .metrics(followerTimers)
                .build();
        logAllocatingCipher(built);
        this.replicaSessions = new ReplicaSessions(engine, settingsComparison, built.leader());
        this.node = built;
    }

    private InetSocketAddress ownAddress() {
        InetSocketAddress own = settings.getPeers().get(settings.getNodeId());
        if (own == null) {
            throw new IllegalStateException("Replication high availability peers " + settings.getPeers().keySet()
                    + " have no address for this node, " + settings.getNodeId());
        }
        return own;
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
            node.resync(key);
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
        node.resync(session.getKey());
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
        requireReplicableSettingsStores();
        if (settingsStream == null) {
            settingsStream = sessions.add(key -> new SessionSettingsStream(key, node.writer(key)));
        }
        engine.getFixSessionsSettingsStores().forEach(this::listenTo);
        settingsChanged();
        election = settings.getLeaderElection().instance();
        if (settings.getMonitoringInstanceId() != null) {
            HaMeters.Timers timers = HaMeters.register(engine.monitoring(settings.getMonitoringInstanceId()), this);
            syncReceivedAckLatency.target = timers.getSyncReceivedAckLatency();
            syncAppliedAckLatency.target = timers.getSyncAppliedAckLatency();
            receivedWriteLatency.target = timers.getReceivedWriteLatency();
            followerTimers.bind(timers);
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
        if (role == Role.LEADER) {
            // the engine stopped the sessions before this, so what they last wrote is all in the log
            node.handOver(Deadline.of(settings.getHandOverTimeout()));
        }
        node.close(stopDeadline);
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
        node.standDown(deadline);
        if (role != Role.LEADER) {
            if (!replicaSessions.isInSync()) {
                log.warn("Node {} leads at epoch {} with sessions its follower copy did not hold in full", settings.getNodeId(), electionEpoch);
            }
            promotionJumps.promoted(sessionsInSettingsStores());
            replicaSessions.jumpOutgoingSeqNums(settings.getPromotionOutgoingSeqNumJump()).forEach(promotionJumps::jumped);
        }
        node.lead(electionEpoch, deadline);
        engine.activate();
        roleChanged(Role.LEADER, electionEpoch, null);
    }

    private void settingsChanged() {
        settingsStream.publish(engine.getFixSessionsSettingsStores(), this::isReplicated);
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

    private void follow(long electionEpoch, @Nullable InetSocketAddress leader) {
        if (role == Role.FOLLOWER && roleEpoch == electionEpoch && sameAddress(leader)) {
            return;
        }
        Deadline deadline = Deadline.of(ROLE_CHANGE_DEADLINE);
        if (role == Role.LEADER) {
            promotionJumps.demoted();
            engine.deactivate();
        }
        if (leader == null) {
            node.standDown(deadline);
            log.warn("Node {} follows at epoch {} but has no address for its leader", settings.getNodeId(), electionEpoch);
        } else {
            node.follow(leader, election::getEpoch, deadline);
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
        }
        node.standDown(deadline);
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

    /**
     * Handed to stores whenever they are built, before or after the meters are registered at start.
     */
    private static final class LateTimer implements Timer {

        private volatile Timer target;

        private LateTimer() {
            this.target = Timer.VoidTimer.getInstance();
        }

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

    /**
     * Gives the replication the meters' timers, which exist only once started: each connection asks for its own as it
     * connects; the received writes' and log full waits' timers are asked for once and record through their late target.
     */
    private static final class LateMetrics implements ReplicationMetrics {

        private final LatencyRecorder receivedWriteLatency;
        private final LateTimer[] logFullWaits;
        private volatile ReplicationMetrics target;

        private LateMetrics(Timer receivedWriteLatency, int lanes) {
            this.receivedWriteLatency = HaMeters.recorder(receivedWriteLatency);
            this.logFullWaits = new LateTimer[lanes];
            for (int lane = 0; lane < lanes; lane++) {
                logFullWaits[lane] = new LateTimer();
            }
            this.target = ReplicationMetrics.NONE;
        }

        void bind(HaMeters.Timers timers) {
            for (int lane = 0; lane < logFullWaits.length; lane++) {
                logFullWaits[lane].target = timers.getLogFullWaits().get(lane);
            }
            target = timers.getFollowerTimers();
        }

        @Override
        public LatencyRecorder followerAckLatency(String followerId, int lane) {
            return target.followerAckLatency(followerId, lane);
        }

        @Override
        public LatencyRecorder followerLag(String followerId, int lane) {
            return target.followerLag(followerId, lane);
        }

        @Override
        public LatencyRecorder followerCatchUp(String followerId, int lane) {
            return target.followerCatchUp(followerId, lane);
        }

        @Override
        public LatencyRecorder logFullWait(int lane) {
            return HaMeters.recorder(logFullWaits[lane]);
        }

        @Override
        public LatencyRecorder receivedWriteLatency() {
            return receivedWriteLatency;
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
            settingsStream.onAdded(store, settings);
            settingsChanged();
        }

        @Override
        public void onRemovedSession(FixSessionSettings settings) {
            settingsStream.onRemoved(store, settings);
            stopReplicating(settings);
            settingsChanged();
        }

        @Override
        public void onUpdatedSession(FixSessionSettings oldSettings, FixSessionSettings newSettings) {
            settingsChanged();
        }
    }
}
