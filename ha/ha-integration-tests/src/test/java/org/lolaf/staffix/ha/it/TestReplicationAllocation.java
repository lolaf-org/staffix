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
package org.lolaf.staffix.ha.it;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.*;
import org.lolaf.staffix.api.alerting.FixSessionProbes;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.ha.FixEngineHa.EngineAccess;
import org.lolaf.staffix.api.monitoring.FixEngineMonitoringContext;
import org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManagerSettings;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionDesiredState;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.fix44.encoders.EmailEncoder;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.ha.ReplicationFixEngineHa;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;
import org.lolaf.staffix.tests.TestPorts;

import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * What a replicated acceptor's session thread allocates per round trip, against the same session not replicated: the
 * thread that receives, answers, stores and replicates must pay nothing for replication, {@code SYNC} included.
 */
@Slf4j
class TestReplicationAllocation {

    private static final int WARM_UP = 20_000;
    /**
     * Enough that one object per round trip, 16 bytes at least, shows far above the one byte allowed.
     */
    private static final int MEASURED = 20_000;
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final FixSessionId ACCEPTOR_ID = FixSessionId.of("ha", FixRegularVersion.VERSION_44, "ACCEPTOR", "INITIATOR");
    private static final FixSessionId INITIATOR_ID = FixSessionId.of("ha", FixRegularVersion.VERSION_44, "INITIATOR", "ACCEPTOR");
    private static final FixSessionProbes NO_PROBES = new FixSessionProbes() {
        @Override
        public void add(Probe probe) {
        }

        @Override
        public boolean remove(String name) {
            return false;
        }

        @Override
        public List<Probe> list() {
            return List.of();
        }
    };
    private final List<Runnable> stops = new ArrayList<>();

    private static FixSessionSettings sessionSettings(FixSessionId id, FixSession.FixSessionType type, ReplicationMode mode) {
        return FixSessionSettings.builder()
                .fixSessionId(id)
                .fixSessionType(type)
                .fixApplicationFactoryInstanceId("app")
                .fixApplicationInstanceId("app")
                .desiredSessionState(FixSessionDesiredState.LOGGED_IN)
                .haSessionSettings(HaSessionSettings.builder().replicationMode(mode).build())
                .build();
    }

    private static FixEngineBuilder.FixEngineBuilderBuilder engine(String name, FixApplication application,
                                                                   FixSessionSettings settings) {
        return FixEngineBuilder.builder()
                .instanceId(name)
                .fixMessagesStore(MemoryMessageStoreSettings.builder().build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder().instanceId("app").application("app", application).build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder().fixSessionSetting(settings).build());
    }

    private static ReplicationHaSettings.ReplicationHaSettingsBuilder<?, ?> ha(String nodeId, Role role, InetSocketAddress a,
                                                                             InetSocketAddress b) {
        return ReplicationHaSettings.builder()
                .nodeId(nodeId)
                .peer("a", a)
                .peer("b", b)
                .heartbeatInterval(Duration.ofMillis(100))
                .reconnectInterval(Duration.ofMillis(50))
                .leaderElection(StaticLeaderElection.Settings.builder().role(role).leaderId("a").build());
    }

    @AfterEach
    void stop() {
        stops.forEach(Runnable::run);
    }

    @Test
    void replicatingAllocatesNothingOnTheSessionThread() {
        Map<ReplicationMode, Double> bytesPerRoundTrip = new EnumMap<>(ReplicationMode.class);
        for (ReplicationMode mode : ReplicationMode.values()) {
            long start = System.nanoTime();
            bytesPerRoundTrip.put(mode, measure(mode));
            log.info("{}: {} bytes per round trip on the session thread, {} ms", mode, bytesPerRoundTrip.get(mode),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            stop();
            stops.clear();
        }

        double baseline = bytesPerRoundTrip.get(ReplicationMode.NONE);
        assertThat(bytesPerRoundTrip.get(ReplicationMode.ASYNC)).as("ASYNC against NONE, bytes per round trip: %s", bytesPerRoundTrip)
                .isLessThan(baseline + 1);
        assertThat(bytesPerRoundTrip.get(ReplicationMode.SYNC)).as("SYNC against NONE, bytes per round trip: %s", bytesPerRoundTrip)
                .isLessThan(baseline + 1);

        long start = System.nanoTime();
        double syncWithMeters = measure(ReplicationMode.SYNC, true);
        log.info("SYNC with the replication's meters: {} bytes per round trip on the session thread, {} ms", syncWithMeters,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        assertThat(syncWithMeters).as("SYNC with the replication's meters against NONE").isLessThan(baseline + 1);
    }

    private double measure(ReplicationMode mode) {
        return measure(mode, false);
    }

    /**
     * @param meters whether a Micrometer monitoring publishes the replication's meters, the {@code SYNC} ack latency
     *               timer among them, recorded on the session thread
     */
    private double measure(ReplicationMode mode, boolean meters) {
        int fixPort = TestPorts.findFree();
        InetSocketAddress leaderReplication = new InetSocketAddress("localhost", TestPorts.findFree());
        InetSocketAddress followerReplication = new InetSocketAddress("localhost", TestPorts.findFree());
        FixSessionSettings acceptorSettings = sessionSettings(ACCEPTOR_ID, FixSession.FixSessionType.ACCEPTOR, mode);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EchoAcceptor acceptor = new EchoAcceptor();
        FixEngineBuilder.FixEngineBuilderBuilder acceptorEngine = engine("acceptor", acceptor, acceptorSettings);
        if (mode != ReplicationMode.NONE) {
            ReplicationHaSettings.ReplicationHaSettingsBuilder<?, ?> leader = ha("a", Role.LEADER, leaderReplication, followerReplication);
            if (meters) {
                acceptorEngine.fixSessionsPlugin(MicrometerMonitoringManagerSettings.builder()
                        .instanceId("monitoring").meterRegistrySupplier(() -> registry).build());
                leader.monitoringInstanceId("monitoring");
            }
            acceptorEngine.highAvailability(leader.build());
        }
        start(acceptorEngine.build().instance()).newAcceptor(FixAcceptorBuilder.builder()
                .bindAddress(new InetSocketAddress("localhost", fixPort))
                .build()).start();
        ReplicationFixEngineHa follower = mode == ReplicationMode.NONE ? null
                : follower(acceptorSettings, leaderReplication, followerReplication);

        Pinger initiator = new Pinger();
        start(engine("initiator", initiator, sessionSettings(INITIATOR_ID, FixSession.FixSessionType.INITIATOR, ReplicationMode.NONE))
                .build().instance())
                .newInitiator(FixInitiatorBuilder.builder()
                        .mainTarget(FixInitiatorTarget.builder().fixSessionId(INITIATOR_ID)
                                .connectAddress(new InetSocketAddress("localhost", fixPort)).build())
                        .build()).start();
        await().until(() -> initiator.session != null);
        if (follower != null) {
            await().until(() -> follower.getReplica().isComplete()
                    && follower.getReplica().getStore(FixSession.FixSessionType.ACCEPTOR, ACCEPTOR_ID.toString()) != null);
        }

        initiator.roundTrips(WARM_UP);
        long before = THREADS.getThreadAllocatedBytes(acceptor.sessionThread.getId());
        assertThat(before).as("the session thread's allocations are counted").isPositive();
        initiator.roundTrips(MEASURED);
        long allocated = THREADS.getThreadAllocatedBytes(acceptor.sessionThread.getId()) - before;

        if (follower != null) {
            FixSessionMessagesStore replica = follower.getReplica().getStore(FixSession.FixSessionType.ACCEPTOR, ACCEPTOR_ID.toString());
            await().untilAsserted(() -> assertThat(replica.getOutgoingSeqNum()).as("replicated").isGreaterThan(WARM_UP + MEASURED));
        }
        if (meters) {
            assertThat(registry.find("ha.sync.ack.latency").timer().count()).as("SYNC acks timed").isGreaterThan(MEASURED);
        }
        return (double) allocated / MEASURED;
    }

    private FixEngine start(FixEngine engine) {
        engine.start();
        stops.add(() -> engine.stop(Deadline.of(Duration.ofSeconds(5))));
        return engine;
    }

    /**
     * A standby with no sessions of its own running, replaying into memory stores.
     */
    private ReplicationFixEngineHa follower(FixSessionSettings settings, InetSocketAddress a, InetSocketAddress b) {
        FixMessagesStore memory = MemoryMessageStoreSettings.builder().build().instance();
        memory.start();
        ReplicationFixEngineHa follower = new ReplicationFixEngineHa(ha("b", Role.FOLLOWER, a, b).build());
        follower.bind(new EngineAccess() {
            @Override
            public List<FixSessionSettings> getFixSessionsSettings() {
                return List.of(settings);
            }

            @Override
            public FixSessionMessagesStore openStore(FixSessionSettings sessionSettings) {
                return memory.getStore(sessionSettings.getFixSessionId(), NO_PROBES);
            }

            @Override
            public FixEngineMonitoringContext monitoring(String monitoringInstanceId) {
                throw new IllegalArgumentException("No monitoring manager " + monitoringInstanceId);
            }

            @Override
            public void activate() {
            }

            @Override
            public void deactivate() {
            }
        });
        follower.start();
        stops.add(() -> {
            follower.stop(Deadline.of(Duration.ofSeconds(5)));
            memory.stop(Deadline.of(Duration.ofSeconds(5)));
        });
        return follower;
    }

    private abstract static class Fix44Application implements FixApplication {

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public FixDictionaryId getDictionaryId() {
            return FixDictionaryId.of(FixRegularVersion.VERSION_44);
        }
    }

    /**
     * Answers each Email with an Email, from inside its decoding: on the session's own thread, which also stores and
     * replicates both.
     */
    private static final class EchoAcceptor extends Fix44Application {

        volatile Thread sessionThread;

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings settings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
            EmailEncoder answer = fixSession.newEncoder(EmailEncoder.class).asReusable();
            return List.of(new FixMessageDecoder() {
                @Override
                public MessageType getMessageType() {
                    return MessageTypes.Email;
                }

                @Override
                public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
                    sessionThread = Thread.currentThread();
                    session.send(answer.begin().setSubject("pong"), null);
                }
            });
        }
    }

    private static final class Pinger extends Fix44Application {

        private final AtomicLong answers = new AtomicLong();
        private volatile FixSession session;
        private EmailEncoder ping;

        @Override
        public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
            ping = fixSession.newEncoder(EmailEncoder.class).asReusable();
            session = fixSession;
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings settings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
            return List.of(new FixMessageDecoder() {
                @Override
                public MessageType getMessageType() {
                    return MessageTypes.Email;
                }

                @Override
                public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
                    answers.incrementAndGet();
                }
            });
        }

        void roundTrips(int count) {
            for (int i = 0; i < count; i++) {
                long expected = answers.get() + 1;
                session.send(ping.begin().setSubject("ping"), null);
                while (answers.get() < expected) {
                    Thread.onSpinWait();
                }
            }
        }
    }
}
