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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.HdrHistogram.Histogram;
import org.lolaf.betty.api.io.IOWorkersGroup;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BusySpinIdleStrategy;
import org.lolaf.staffix.api.FixAcceptorBuilder;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixInitiatorBuilder;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.codec.FixMessageEncodersPool;
import org.lolaf.staffix.api.ha.HaAdmin;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.benchmarks.fix44.encoders.QuoteRequestEncoder;
import org.lolaf.staffix.benchmarks.fix44.msg.MessageTypes;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManagerSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.lolaf.staffix.benchmarks.impl.HaPair.acceptorId;
import static org.lolaf.staffix.benchmarks.impl.HaPair.await;
import static org.lolaf.staffix.benchmarks.impl.HaPair.busySpinning;
import static org.lolaf.staffix.benchmarks.impl.HaPair.engine;
import static org.lolaf.staffix.benchmarks.impl.HaPair.freePort;
import static org.lolaf.staffix.benchmarks.impl.HaPair.initiatorId;
import static org.lolaf.staffix.benchmarks.impl.HaPair.replicationWaiting;
import static org.lolaf.staffix.benchmarks.impl.HaPair.session;

/**
 * A highly available pair answering {@link #sessions} counterparty sessions in an open loop: one generator thread
 * sends QuoteRequests round-robin over the sessions at {@link #rate} a second, whatever the answers, and each Quote's
 * latency is taken from the time its request was due, so a stalled engine shows as latency rather than as a lower
 * rate. Each iteration logs the latency percentiles and the replication's meters over that iteration.
 */
@Slf4j
@State(Scope.Benchmark)
public class HaLoad {

    private static final String APPLICATION = "app";
    private static final String MONITORING = "metrics";
    private static final long HIGHEST_LATENCY_NANOS = TimeUnit.SECONDS.toNanos(60);

    @Param({"1", "4", "16"})
    int sessions;
    /**
     * QuoteRequests a second, over every session.
     */
    @Param({"10000", "50000", "100000", "200000"})
    int rate;
    @Param({"ASYNC", "SYNC"})
    ReplicationMode mode;
    @Param({"1"})
    int leaderIoThreads;
    /**
     * The counterparty's IO threads, shared by its sessions, so that many sessions do not mean many spinning threads.
     */
    @Param({"2"})
    int counterpartyIoThreads;
    @Param({"BACKOFF"})
    HaPair.ReplicationWaiting replicationWaiting;
    @Param({"5"})
    int seconds;

    private final List<Runnable> stops = new ArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Map<Meter.Id, double[]> metersAtStart = new HashMap<>();
    private final Histogram latency = new Histogram(HIGHEST_LATENCY_NANOS, 3);
    private LoadSession[] loadSessions;
    private long sentThisIteration;
    private long iterationNanos;

    @Setup(Level.Trial)
    public void setup() {
        int fixPort = freePort();
        InetSocketAddress replicationA = new InetSocketAddress("localhost", freePort());
        InetSocketAddress replicationB = new InetSocketAddress("localhost", freePort());
        haEngine("a", Role.LEADER, replicationA, replicationB)
                .newAcceptor(FixAcceptorBuilder.builder().bindAddress(new InetSocketAddress("localhost", fixPort))
                        .ioWorkersGroup(busySpinning(leaderIoThreads)).build()).start();
        FixEngine followerEngine = haEngine("b", Role.FOLLOWER, replicationA, replicationB);
        // a standby knows the sessions its connectors register, and binds nothing until it leads
        followerEngine.newAcceptor(FixAcceptorBuilder.builder().bindAddress(new InetSocketAddress("localhost", freePort())).build()).start();
        HaAdmin follower = ((AdminApi) followerEngine).getHa().orElseThrow();

        loadSessions = new LoadSession[sessions];
        SimpleApplicationFactorySettings.SimpleApplicationFactorySettingsBuilder applications =
                SimpleApplicationFactorySettings.builder().instanceId(APPLICATION);
        MemorySessionsSettingsStoreSettings.MemorySessionsSettingsStoreSettingsBuilder initiators = MemorySessionsSettingsStoreSettings.builder();
        for (int i = 0; i < sessions; i++) {
            loadSessions[i] = new LoadSession();
            applications.application(name(i), loadSessions[i]);
            initiators.fixSessionSetting(session(initiatorId(name(i)), FixSession.FixSessionType.INITIATOR, name(i), ReplicationMode.NONE));
        }
        FixEngine counterparty = start(engine("counterparty", MemoryMessageStoreSettings.builder().build())
                .fixApplicationFactory(applications.build())
                .fixSessionsSettingsStore(initiators.build())
                .build().instance());
        IOWorkersGroup counterpartyIo = busySpinning(counterpartyIoThreads);
        for (int i = 0; i < sessions; i++) {
            counterparty.newInitiator(FixInitiatorBuilder.builder().instanceId(name(i)).ioWorkersGroup(counterpartyIo)
                    .mainTarget(FixInitiatorTarget.builder().fixSessionId(initiatorId(name(i)))
                            .connectAddress(new InetSocketAddress("localhost", fixPort)).build())
                    .build()).start();
        }

        await("logon", () -> {
            for (LoadSession loadSession : loadSessions) {
                if (!loadSession.connected) {
                    return false;
                }
            }
            return true;
        });
        await("follower in sync", () -> {
            HaStatus.Leader leader = follower.getStatus().getLeader();
            return leader != null && leader.isInSync();
        });
        log.info("HA load ready: {} sessions at {} msg/s, {} replication, replication waiting {}, leader IO threads {}",
                sessions, rate, mode, replicationWaiting, leaderIoThreads);
    }

    @Setup(Level.Iteration)
    public void startIteration() {
        for (LoadSession loadSession : loadSessions) {
            loadSession.latency.reset();
        }
        metersAtStart.clear();
        for (Meter meter : meters.getMeters()) {
            metersAtStart.put(meter.getId(), cumulative(meter));
        }
        sentThisIteration = 0;
    }

    /**
     * Sends at {@link #rate} for {@link #seconds}, each request due at its own time from the start.
     *
     * @return the requests sent
     */
    public long run() {
        long durationNanos = TimeUnit.SECONDS.toNanos(seconds);
        long start = System.nanoTime();
        long sent = 0;
        while (true) {
            long due = start + sent * 1_000_000_000L / rate;
            if (due - start >= durationNanos) {
                break;
            }
            while (System.nanoTime() < due) {
                Thread.onSpinWait();
            }
            loadSessions[(int) (sent % sessions)].send(due);
            sent++;
        }
        iterationNanos = System.nanoTime() - start;
        sentThisIteration = sent;
        return sent;
    }

    @TearDown(Level.Iteration)
    public void endIteration() {
        long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (unanswered() > 0 && System.nanoTime() < drainDeadline) {
            Thread.onSpinWait();
        }
        latency.reset();
        for (LoadSession loadSession : loadSessions) {
            latency.add(loadSession.latency);
        }
        log.info("HA load {} sessions, {} msg/s asked, {} achieved, {} unanswered; latency µs p50 {} p90 {} p99 {} p99.9 {} max {}",
                sessions, rate, sentThisIteration * 1_000_000_000L / iterationNanos, unanswered(),
                micros(latency.getValueAtPercentile(50)), micros(latency.getValueAtPercentile(90)),
                micros(latency.getValueAtPercentile(99)), micros(latency.getValueAtPercentile(99.9)), micros(latency.getMaxValue()));
        logReplicationMeters();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        for (int i = stops.size() - 1; i >= 0; i--) {
            stops.get(i).run();
        }
    }

    private long unanswered() {
        long unanswered = 0;
        for (LoadSession loadSession : loadSessions) {
            unanswered += loadSession.published - loadSession.answered;
        }
        return unanswered;
    }

    private void logReplicationMeters() {
        StringBuilder line = new StringBuilder("HA load replication:");
        for (Meter meter : meters.getMeters()) {
            String name = meter.getId().getName();
            if (!name.startsWith("ha.") || name.endsWith(".percentile")) {
                continue;
            }
            double[] now = cumulative(meter);
            double[] atStart = metersAtStart.getOrDefault(meter.getId(), new double[now.length]);
            if (meter instanceof Timer) {
                long count = (long) (now[0] - atStart[0]);
                line.append(String.format(" %s count %d mean %d µs;", name, count,
                        count == 0 ? 0 : (long) ((now[1] - atStart[1]) / count / 1_000)));
            } else if (meter instanceof Counter || meter instanceof FunctionCounter) {
                line.append(String.format(" %s +%d;", name, (long) (now[0] - atStart[0])));
            } else if (meter instanceof Gauge) {
                line.append(String.format(" %s %d;", name, (long) now[0]));
            }
        }
        log.info("{}", line);
    }

    private static double[] cumulative(Meter meter) {
        if (meter instanceof Timer) {
            Timer timer = (Timer) meter;
            return new double[]{timer.count(), timer.totalTime(TimeUnit.NANOSECONDS)};
        }
        if (meter instanceof Counter) {
            return new double[]{((Counter) meter).count()};
        }
        if (meter instanceof FunctionCounter) {
            return new double[]{((FunctionCounter) meter).count()};
        }
        if (meter instanceof Gauge) {
            return new double[]{((Gauge) meter).value()};
        }
        return new double[0];
    }

    private static long micros(long nanos) {
        return TimeUnit.NANOSECONDS.toMicros(nanos);
    }

    private static String name(int session) {
        return "S" + session;
    }

    private FixEngine haEngine(String nodeId, Role role, InetSocketAddress replicationA, InetSocketAddress replicationB) {
        MemorySessionsSettingsStoreSettings.MemorySessionsSettingsStoreSettingsBuilder acceptors = MemorySessionsSettingsStoreSettings.builder();
        for (int i = 0; i < sessions; i++) {
            acceptors.fixSessionSetting(session(acceptorId(name(i)), FixSession.FixSessionType.ACCEPTOR, APPLICATION, mode));
        }
        FixMessagesStoreSettings store = MemoryMessageStoreSettings.builder().build();
        return start(engine(nodeId, store)
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder().instanceId(APPLICATION)
                        .application(APPLICATION, new StaffixEngine.AcceptorApplication()).build())
                .fixSessionsSettingsStore(acceptors.build())
                .fixSessionsPlugin(MicrometerMonitoringManagerSettings.builder()
                        .instanceId(MONITORING)
                        .meterRegistrySupplier(() -> meters)
                        .readLatencyEnabled(false)
                        .writeLatencyEnabled(false)
                        .decodingLatencyEnabled(false)
                        .encodingLatencyEnabled(false)
                        .build())
                .highAvailability(replicationWaiting(ReplicationHaSettings.builder(), replicationWaiting)
                        .nodeId(nodeId)
                        .peer("a", replicationA)
                        .peer("b", replicationB)
                        .monitoringInstanceId(nodeId.equals("a") ? MONITORING : null)
                        .leaderElection(StaticLeaderElection.Settings.builder().role(role).leaderId("a").build())
                        .build())
                .build().instance());
    }

    private FixEngine start(FixEngine engine) {
        engine.start();
        stops.add(() -> engine.stop(Deadline.of(Duration.ofSeconds(5))));
        return engine;
    }

    /**
     * One counterparty session: the generator sends on it, and its IO thread takes each Quote's latency from the due
     * time of the request it answers, the oldest unanswered one, a session answering in order.
     */
    static final class LoadSession implements FixApplication {

        private static final int IN_FLIGHT = 1 << 16;
        private static final int MASK = IN_FLIGHT - 1;

        private final long[] dueNanos = new long[IN_FLIGHT];
        private final Histogram latency = new Histogram(HIGHEST_LATENCY_NANOS, 3);
        private volatile long published;
        private volatile long answered;
        private volatile boolean connected;
        private FixSession fixSession;
        private FixMessageEncodersPool<QuoteRequestEncoder> encoders;

        void send(long due) {
            long sent = published;
            while (sent - answered >= IN_FLIGHT) {
                Thread.onSpinWait();
            }
            dueNanos[(int) (sent & MASK)] = due;
            published = sent + 1;
            QuoteRequestEncoder encoder = encoders.borrowBlocking(BusySpinIdleStrategy.getInstance());
            encoder.begin()
                    .setQuoteReqID("load")
                    .addNoRelatedSym(1).setSymbol("FOO/BAR");
            fixSession.send(encoder, null);
        }

        private void onQuote() {
            long next = answered;
            if (next >= published) {
                log.warn("A Quote on {} answering no request", fixSession.getFixSessionId());
                return;
            }
            latency.recordValue(Math.min(System.nanoTime() - dueNanos[(int) (next & MASK)], HIGHEST_LATENCY_NANOS));
            answered = next + 1;
        }

        @Override
        public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
            this.fixSession = fixSession;
            encoders = fixSession.newEncodersPool("load", false, QuoteRequestEncoder.class);
            connected = true;
        }

        @Override
        public void onLogout(FixSession fixSession, String message, DecodedFixMessage logoutMessage) {
            connected = false;
        }

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public FixDictionaryId getDictionaryId() {
            return FixDictionaryId.of("benchmarks", FixRegularVersion.VERSION_44);
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings fixSessionSettings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
            return List.of(new FixMessageDecoder() {
                @Override
                public MessageType getMessageType() {
                    return MessageTypes.Quote;
                }

                @Override
                public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
                    onQuote();
                }
            });
        }
    }
}
