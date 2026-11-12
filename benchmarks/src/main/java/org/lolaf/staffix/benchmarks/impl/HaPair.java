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
package org.lolaf.staffix.benchmarks.impl;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.betty.api.settings.IOWorkersGroupSettings;
import org.lolaf.betty.api.ss.IdleStrategySelectStrategy;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BusySpinIdleStrategy;
import org.lolaf.staffix.api.FixAcceptorBuilder;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.FixInitiatorBuilder;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.ha.HaAdmin;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionDesiredState;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManagerSettings;
import org.lolaf.staffix.stores.loggers.slf4j.Slf4jMessagesLoggerSettings;
import org.lolaf.staffix.stores.messages.file.FileMessageStoreSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * A highly available pair in one JVM, leader {@code a} and follower {@code b}, and the counterparty trading with the
 * leader: the round trip of a QuoteRequest answered by a Quote, whose store write is replicated in {@link #mode}. The
 * FIX side busy spins, so what changes between the modes is the replication.
 */
@Slf4j
@State(Scope.Benchmark)
public class HaPair {

    private static final String APPLICATION = "app";
    private static final String MONITORING = "metrics";

    @Param({"NONE", "ASYNC", "SYNC_RECEIVED", "SYNC_APPLIED"})
    ReplicationMode mode;
    @Param({"MEMORY", "FILE"})
    FollowerStorage.Kind followerStore;
    /**
     * The leader's own store: memory keeps the last 1 024 messages, a file every one, so its snapshots fill whole chunks.
     */
    @Param({"MEMORY"})
    FollowerStorage.Kind leaderStore;
    /**
     * A second session of the pair trading continuously beside the measured one, sharing its replication stream.
     */
    @Param({"false", "true"})
    boolean busySession;
    /**
     * How the replication's threads wait: {@link ReplicationWaiting#BACKOFF}, the default, unless asked for; the
     * levels spinning cost a core each, so they are run on their own, for {@code SYNC} alone.
     */
    @Param({"BACKOFF"})
    ReplicationWaiting replicationWaiting;
    /**
     * The leader's acceptor IO threads: with one, a {@code SYNC} wait on one session's thread holds the other's reads.
     */
    @Param({"1"})
    int leaderIoThreads;
    /**
     * The busy session resynced again as soon as its last snapshot ended, so the measured session trades beside a
     * snapshot in progress almost all the time.
     */
    @Param({"false"})
    boolean busyResync;
    /**
     * The sessions split over this many replication lanes, session {@code n} on lane {@code n} modulo the count.
     */
    @Param({"1"})
    int replicationLanes;
    /**
     * Off, a FILE store writes to the page cache only, as every file store did before {@code syncWrites} forced.
     */
    @Param({"true"})
    boolean fileSyncWrites;
    /**
     * Zero, the follower's network thread writes its stores; otherwise a pool of writer threads does.
     */
    @Param({"0"})
    int followerStoreWriterThreads;

    /**
     * Which of the replication's threads busy spin, each one more core.
     */
    public enum ReplicationWaiting {
        /**
         * Every thread backs off, the default.
         */
        BACKOFF,
        /**
         * The leader's sender, a core per follower.
         */
        SENDER_SPIN,
        /**
         * The sender, and the session thread waiting for a {@code SYNC} send's ack.
         */
        SENDER_WAITER_SPIN,
        /**
         * Those, and the replication's network threads on both nodes.
         */
        ALL_SPIN
    }

    private final List<Runnable> stops = new ArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Map<Meter.Id, long[]> timersAtMeasuring = new HashMap<>();
    private StaffixEngine.InitiatorApplication counterparty;
    private Thread busyTrader;
    private Thread resyncer;
    private volatile boolean trading;
    private FollowerStorage followerStorage;
    private FollowerStorage leaderStorage;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        followerStorage = new FollowerStorage(followerStore, fileSyncWrites);
        leaderStorage = new FollowerStorage(leaderStore, fileSyncWrites);
        int fixPort = freePort();
        InetSocketAddress replicationA = new InetSocketAddress("localhost", freePort());
        InetSocketAddress replicationB = new InetSocketAddress("localhost", freePort());
        FixEngine leaderEngine = haEngine("a", Role.LEADER, leaderStorage.settings(), replicationA, replicationB);
        leaderEngine.newAcceptor(FixAcceptorBuilder.builder().bindAddress(new InetSocketAddress("localhost", fixPort))
                        .ioWorkersGroup(busySpinning(leaderIoThreads)).build()).start();
        FixEngine followerEngine = haEngine("b", Role.FOLLOWER, followerStorage.settings(), replicationA, replicationB);
        // a follower knows the sessions its connectors register, and binds nothing until it leads
        followerEngine.newAcceptor(FixAcceptorBuilder.builder().bindAddress(new InetSocketAddress("localhost", freePort())).build()).start();
        HaAdmin follower = ((AdminApi) followerEngine).getHa().orElseThrow();

        counterparty = new StaffixEngine.InitiatorApplication();
        StaffixEngine.InitiatorApplication busyCounterparty = new StaffixEngine.InitiatorApplication();
        FixEngineBuilder.FixEngineBuilderBuilder<?, ?> counterpartyEngine = engine("counterparty", MemoryMessageStoreSettings.builder().build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder().instanceId(APPLICATION)
                        .application("measured", counterparty).application("busy", busyCounterparty).build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .fixSessionSetting(session(initiatorId("MEASURED"), FixSession.FixSessionType.INITIATOR, "measured", ReplicationMode.NONE))
                        .fixSessionSetting(session(initiatorId("BUSY"), FixSession.FixSessionType.INITIATOR, "busy", ReplicationMode.NONE))
                        .build());
        FixEngine counterpartyFixEngine = start(counterpartyEngine.build().instance());
        for (String name : busySession ? List.of("MEASURED", "BUSY") : List.of("MEASURED")) {
            counterpartyFixEngine.newInitiator(FixInitiatorBuilder.builder().instanceId(name).ioWorkersGroup(busySpinning(1))
                    .mainTarget(FixInitiatorTarget.builder().fixSessionId(initiatorId(name))
                            .connectAddress(new InetSocketAddress("localhost", fixPort)).build())
                    .build()).start();
        }

        await("logon", () -> counterparty.connected.get() && (!busySession || busyCounterparty.connected.get()));
        await("follower in sync", () -> {
            HaStatus.Leader leader = follower.getStatus().getLeader();
            return leader != null && leader.isInSync();
        });
        if (busySession) {
            trading = true;
            busyTrader = new Thread(() -> {
                while (trading) {
                    roundTrip(busyCounterparty);
                }
            }, "busy-session-trader");
            busyTrader.start();
            if (busyResync) {
                HaAdmin leader = ((AdminApi) leaderEngine).getHa().orElseThrow();
                resyncer = new Thread(() -> {
                    while (trading) {
                        // a no-op while the last snapshot is pending
                        leader.resync(acceptorId("BUSY"), FixSession.FixSessionType.ACCEPTOR);
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    }
                }, "busy-session-resyncer");
                resyncer.start();
            }
        }
        log.info("HA pair ready: {} replication, {} follower store, busy session {}, replication waiting {}, leader IO threads {}, busy resync {}, replication lanes {}, follower store writer threads {}",
                mode, followerStore, busySession, replicationWaiting, leaderIoThreads, busyResync, replicationLanes,
                followerStoreWriterThreads);
    }

    public void roundTrip() {
        roundTrip(counterparty);
    }

    private static void roundTrip(StaffixEngine.InitiatorApplication application) {
        application.sendMessage();
        while (!application.receivedResponse.get()) {
            Thread.onSpinWait();
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException, IOException {
        trading = false;
        if (busyTrader != null) {
            busyTrader.join();
        }
        if (resyncer != null) {
            resyncer.join();
        }
        logTimer("ha.follower.lag", "fill to ack");
        logTimer("ha.follower.ack.latency", "follower round trip, batch sent to ack");
        logTimer("ha.sync.ack.latency", "SYNC send waiting for the ack");
        logCount("ha.follower.too.slow", "followers dropped as too slow");
        logCount("ha.snapshots", "snapshots sent");
        for (int i = stops.size() - 1; i >= 0; i--) {
            stops.get(i).run();
        }
        followerStorage.close();
        leaderStorage.close();
    }

    /**
     * Snapshots the replication's timers as measuring starts, so the trial's logs leave the warm-up out.
     */
    @Setup(Level.Iteration)
    public void iteration(IterationParams iteration) {
        if (iteration.getType() == IterationType.MEASUREMENT && timersAtMeasuring.isEmpty()) {
            for (Meter meter : meters.getMeters()) {
                if (meter instanceof Timer) {
                    Timer timer = (Timer) meter;
                    timersAtMeasuring.put(timer.getId(), new long[]{timer.count(), (long) timer.totalTime(TimeUnit.NANOSECONDS)});
                }
            }
        }
    }

    private void logCount(String name, String description) {
        for (FunctionCounter counter : meters.find(name).functionCounters()) {
            log.info("{} ({}), whole trial: {}", name, description, (long) counter.count());
        }
    }

    private void logTimer(String name, String description) {
        for (Timer timer : meters.find(name).timers()) {
            long[] atMeasuring = timersAtMeasuring.getOrDefault(timer.getId(), new long[2]);
            long count = timer.count() - atMeasuring[0];
            long totalNanos = (long) timer.totalTime(TimeUnit.NANOSECONDS) - atMeasuring[1];
            log.info("{} ({}) {}, measurement iterations: count {}, mean {} µs", name, description, timer.getId().getTags(),
                    count, count == 0 ? 0 : TimeUnit.NANOSECONDS.toMicros(totalNanos / count));
        }
    }

    private FixEngine haEngine(String nodeId, Role role, FixMessagesStoreSettings store, InetSocketAddress replicationA,
                               InetSocketAddress replicationB) {
        StaffixEngine.AcceptorApplication application = new StaffixEngine.AcceptorApplication();
        return start(engine(nodeId, store)
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder().instanceId(APPLICATION)
                        .application(APPLICATION, application).build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .fixSessionSetting(session(acceptorId("MEASURED"), FixSession.FixSessionType.ACCEPTOR, APPLICATION, mode))
                        .fixSessionSetting(session(acceptorId("BUSY"), FixSession.FixSessionType.ACCEPTOR, APPLICATION, mode))
                        .build())
                .fixSessionsPlugin(MicrometerMonitoringManagerSettings.builder()
                        .instanceId(MONITORING)
                        .meterRegistrySupplier(() -> meters)
                        // local percentiles go through an auto-resizing HdrHistogram, allocating on the session thread
                        .defaultTimersSettings(MicrometerMonitoringManagerSettings.TimerSettings.builder()
                                .histogramsPercentiles(List.of())
                                .build())
                        .readLatencyEnabled(false)
                        .writeLatencyEnabled(false)
                        .decodingLatencyEnabled(false)
                        .encodingLatencyEnabled(false)
                        .build())
                .highAvailability(replicationWaiting(ReplicationHaSettings.builder(), replicationWaiting)
                        .nodeId(nodeId)
                        .replicationLanes(replicationLanes)
                        .followerStoreWriterThreads(followerStoreWriterThreads)
                        .peer("a", replicationA)
                        .peer("b", replicationB)
                        .monitoringInstanceId(nodeId.equals("a") ? MONITORING : null)
                        .leaderElection(StaticLeaderElection.Settings.builder().role(role).leaderId("a").build())
                        .build())
                .build().instance());
    }

    static ReplicationHaSettings.ReplicationHaSettingsBuilder<?, ?> replicationWaiting(
            ReplicationHaSettings.ReplicationHaSettingsBuilder<?, ?> settings, ReplicationWaiting replicationWaiting) {
        if (replicationWaiting != ReplicationWaiting.BACKOFF) {
            settings.senderIdleStrategy(BusySpinIdleStrategy::getInstance);
        }
        if (replicationWaiting == ReplicationWaiting.SENDER_WAITER_SPIN || replicationWaiting == ReplicationWaiting.ALL_SPIN) {
            settings.syncAckIdleStrategy(BusySpinIdleStrategy::getInstance);
        }
        if (replicationWaiting == ReplicationWaiting.ALL_SPIN) {
            settings.selectStrategy(() -> new IdleStrategySelectStrategy(BusySpinIdleStrategy::getInstance));
        }
        return settings;
    }

    static FixEngineBuilder.FixEngineBuilderBuilder<?, ?> engine(String name, FixMessagesStoreSettings store) {
        return FixEngineBuilder.builder()
                .instanceId(name)
                .fixMessagesStore(store)
                .fixMessagesLogger(Slf4jMessagesLoggerSettings.builder().logIncoming(false).logOutgoing(false).build());
    }

    static FixSessionSettings session(FixSessionId id, FixSession.FixSessionType type, String application,
                                              ReplicationMode mode) {
        return FixSessionSettings.builder()
                .fixSessionId(id)
                .fixSessionType(type)
                .heartBeatInterval(FixSessionSettings.HeartbeatInterval.builder().initiatorInterval(Duration.ofSeconds(10)).build())
                .resetSeqNumOnLogon(true)
                .fixApplicationFactoryInstanceId(APPLICATION)
                .fixApplicationInstanceId(application)
                .desiredSessionState(FixSessionDesiredState.LOGGED_IN)
                .highAvailability(HaSessionSettings.builder().replicationMode(mode).build())
                .build();
    }

    static FixSessionId acceptorId(String name) {
        return FixSessionId.of(name, FixRegularVersion.VERSION_44, "TARGET_" + name, "SENDER_" + name);
    }

    static FixSessionId initiatorId(String name) {
        return FixSessionId.of(name, FixRegularVersion.VERSION_44, "SENDER_" + name, "TARGET_" + name);
    }

    static org.lolaf.betty.api.io.IOWorkersGroup busySpinning(int ioThreads) {
        return IOWorkersGroupSettings.builder()
                .ioThreadGroup(IOWorkersGroupSettings.IOThreadGroup.builder()
                        .selectStrategy(new IdleStrategySelectStrategy(BusySpinIdleStrategy::getInstance))
                        .ioThreadCount(ioThreads)
                        .build())
                .build().newInstance();
    }

    private FixEngine start(FixEngine engine) {
        engine.start();
        stops.add(() -> engine.stop(Deadline.of(Duration.ofSeconds(5))));
        return engine;
    }

    static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Timed out waiting for " + what);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
