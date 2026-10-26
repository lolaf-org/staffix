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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import org.lolaf.primos.ClusterConfig;
import org.lolaf.primos.Peer;
import org.lolaf.primos.PrimosNode;
import org.lolaf.primos.Timings;
import org.lolaf.primos.store.MemoryStateStore;
import org.lolaf.primos.time.MonotonicClock;
import org.lolaf.primos.transport.UdpTransport;
import org.lolaf.primos.witness.FileConfigStore;
import org.lolaf.primos.witness.WitnessServer;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixAcceptorBuilder;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.FixInitiatorBuilder;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.ha.HaAdmin;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.staffix.api.ha.SwitchoverOutcome;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionDesiredState;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.ha.primos.PrimosLeaderElectionSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;
import org.lolaf.staffix.tests.TestPorts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * An acceptor pair elected by primos with a witness, as deployed, rather than by a test election: the transfer is
 * primos's own, and a leader cut off from its peer and its witness must stop its sessions before the other is elected.
 * The first leader already moved its seqnums ahead, as every node taking leadership does, so gaps are counted from
 * there.
 */
class TestPrimosFailover {

    private static final String CLUSTER = "staffix";
    private static final String SECRET = "a-shared-cluster-secret";
    private static final int WITNESS_ID = Peer.MAX_ID;
    private static final FixSessionId ACCEPTOR_ID = FixSessionId.of("ha", FixRegularVersion.VERSION_44, "ACCEPTOR", "INITIATOR");
    private static final FixSessionId INITIATOR_ID = FixSessionId.of("ha", FixRegularVersion.VERSION_44, "INITIATOR", "ACCEPTOR");
    private static final long JUMP = 10_000;

    @TempDir
    Path witnessDirectory;

    private final List<Runnable> stops = new ArrayList<>();
    private final Counterparty counterparty = new Counterparty();
    private final CuttableTransport[] transports = new CuttableTransport[3];
    private final HaAdmin[] nodes = new HaAdmin[3];
    private final LoggedOnNodes loggedOnNodes = new LoggedOnNodes();
    private AdminApi counterpartyEngine;
    private FixSessionId counterpartySession;

    private static int freeUdpPort() {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

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

    private static FixEngineBuilder.FixEngineBuilderBuilder engine(String name, FixApplication application, FixSessionSettings settings) {
        return FixEngineBuilder.builder()
                .instanceId(name)
                .fixMessagesStore(MemoryMessageStoreSettings.builder().build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder().instanceId("app").application("app", application).build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder().fixSessionSetting(settings).build());
    }

    /**
     * Which side of the session the pair runs. An initiator pair is the one where both nodes could be logged on at
     * once, each from its own connection to the counterparty.
     */
    enum Pair {
        ACCEPTORS,
        INITIATORS
    }

    private void start(Pair pair) throws IOException {
        InetSocketAddress witness = startWitness();
        int[] electionPorts = {0, freeUdpPort(), freeUdpPort()};
        InetSocketAddress[] replication = {null, new InetSocketAddress("localhost", TestPorts.findFree()),
                new InetSocketAddress("localhost", TestPorts.findFree())};
        InetSocketAddress[] fix = {null, new InetSocketAddress("localhost", TestPorts.findFree()),
                new InetSocketAddress("localhost", TestPorts.findFree())};
        InetSocketAddress counterpartyAddress = new InetSocketAddress("localhost", TestPorts.findFree());
        FixSessionId pairSession = pair == Pair.ACCEPTORS ? ACCEPTOR_ID : INITIATOR_ID;
        FixSession.FixSessionType pairType = pair == Pair.ACCEPTORS ? FixSession.FixSessionType.ACCEPTOR : FixSession.FixSessionType.INITIATOR;
        counterpartySession = pair == Pair.ACCEPTORS ? INITIATOR_ID : ACCEPTOR_ID;
        FixEngine counterpartyFixEngine = start(engine("counterparty", counterparty, sessionSettings(counterpartySession,
                pair == Pair.ACCEPTORS ? FixSession.FixSessionType.INITIATOR : FixSession.FixSessionType.ACCEPTOR,
                ReplicationMode.NONE)).build().instance());
        counterpartyEngine = (AdminApi) counterpartyFixEngine;
        if (pair == Pair.INITIATORS) {
            counterpartyFixEngine.newAcceptor(FixAcceptorBuilder.builder().bindAddress(counterpartyAddress).build()).start();
        }
        for (int id = 1; id <= 2; id++) {
            PrimosNode election = election(id, electionPorts, witness);
            FixEngine engine = start(engine("node-" + id, new Echo(loggedOnNodes), sessionSettings(pairSession, pairType, ReplicationMode.ASYNC))
                    .highAvailability(ReplicationHaSettings.builder()
                            .nodeId(String.valueOf(id))
                            .peer("1", replication[1])
                            .peer("2", replication[2])
                            .heartbeatInterval(Duration.ofMillis(100))
                            .reconnectInterval(Duration.ofMillis(50))
                            .promotionOutgoingSeqNumJump(JUMP)
                            .leaderElection(PrimosLeaderElectionSettings.builder().node(election).leadershipControl(true).build())
                            .build())
                    .build().instance());
            if (pair == Pair.ACCEPTORS) {
                engine.newAcceptor(FixAcceptorBuilder.builder().bindAddress(fix[id]).build()).start();
            } else {
                engine.newInitiator(initiator(INITIATOR_ID, counterpartyAddress)).start();
            }
            nodes[id] = ((AdminApi) engine).getHa().orElseThrow();
        }
        if (pair == Pair.ACCEPTORS) {
            counterpartyFixEngine.newInitiator(initiator(INITIATOR_ID, fix[1], fix[2])).start();
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> counterparty.logons.get() == 1);
        counterparty.roundTrips(10);
        await().until(() -> follower().getStatus().getLeader() != null && follower().getStatus().getLeader().isInSync());
    }

    private static FixInitiatorBuilder initiator(FixSessionId id, InetSocketAddress... addresses) {
        FixInitiatorTarget.FixInitiatorTargetBuilder target = FixInitiatorTarget.builder().fixSessionId(id);
        for (InetSocketAddress address : addresses) {
            target.connectAddress(address);
        }
        return FixInitiatorBuilder.builder().connectionRetry(Duration.ofMillis(100)).mainTarget(target.build()).build();
    }

    private InetSocketAddress startWitness() throws IOException {
        Files.createDirectories(witnessDirectory.resolve("applications"));
        Files.write(witnessDirectory.resolve("witness.properties"), ("secret.shared.value=" + SECRET).getBytes(StandardCharsets.UTF_8));
        Files.write(witnessDirectory.resolve("applications/" + CLUSTER + ".properties"), new byte[0]);
        WitnessServer server = WitnessServer.builder()
                .configStore(new FileConfigStore(witnessDirectory, MonotonicClock.SYSTEM))
                .nodeId(WITNESS_ID)
                .bindAddress(new InetSocketAddress("127.0.0.1", 0))
                .build();
        Thread thread = new Thread(server::run, "witness");
        thread.setDaemon(true);
        thread.start();
        stops.add(server::close);
        await().until(() -> server.boundAddress() != null);
        return server.boundAddress();
    }

    /**
     * The application's own node, as staffix's Spring Boot integration has it: started and stopped here, after the
     * engines.
     */
    private PrimosNode election(int id, int[] ports, InetSocketAddress witness) {
        int other = 3 - id;
        Peer peer = Peer.application(other, "127.0.0.1", ports[other]);
        Peer witnessPeer = Peer.witness(WITNESS_ID, "127.0.0.1", witness.getPort());
        PrimosNode node = PrimosNode.builder()
                .clusterConfig(ClusterConfig.builder().applicationName(CLUSTER).nodeId(id).peer(peer).witness(witnessPeer)
                        .timings(Timings.lan()).build())
                .store(MemoryStateStore::new)
                .transport(() -> {
                    try {
                        transports[id] = new CuttableTransport(UdpTransport.builder()
                                .bindAddress(new InetSocketAddress("127.0.0.1", ports[id]))
                                .peers(List.of(peer, witnessPeer))
                                .clusterName(CLUSTER)
                                .build());
                        return transports[id];
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .clusterSecret(SECRET.getBytes(StandardCharsets.UTF_8))
                .build();
        node.start();
        stops.add(node::stop);
        return node;
    }

    private FixEngine start(FixEngine engine) {
        engine.start();
        stops.add(0, () -> engine.stop(Deadline.of(Duration.ofSeconds(5))));
        return engine;
    }

    @AfterEach
    void stop() {
        stops.forEach(Runnable::run);
    }

    private int leaderId() {
        await().atMost(Duration.ofSeconds(10)).until(() -> nodes[1].getStatus().getRole() == Role.LEADER
                ^ nodes[2].getStatus().getRole() == Role.LEADER);
        return nodes[1].getStatus().getRole() == Role.LEADER ? 1 : 2;
    }

    private HaAdmin follower() {
        return nodes[3 - leaderId()];
    }

    private long counterpartyIncomingSeqNum() {
        return counterpartyEngine.getIncomingSeqNum(counterpartySession);
    }

    @ParameterizedTest
    @EnumSource(Pair.class)
    void aSwitchoverThroughPrimosLogsTheCounterpartyOutAndTheOtherNodeCarriesOnWithoutAJump(Pair pair) throws IOException {
        start(pair);
        int leader = leaderId();
        long expected = counterpartyIncomingSeqNum();
        int resendRequests = counterparty.resendRequests.get();

        assertThat(nodes[leader].switchover()).isEqualTo(SwitchoverOutcome.TRANSFERRED);

        await().until(() -> counterparty.logons.get() == 2);
        assertThat(leaderId()).isEqualTo(3 - leader);
        assertThat(counterparty.logoutsReceived.get()).isEqualTo(1);
        counterparty.roundTrips(10);
        assertThat(counterparty.resendRequests.get()).as("no new gap").isEqualTo(resendRequests);
        assertThat(counterpartyIncomingSeqNum()).isEqualTo(expected + 12);
        assertThat(loggedOnNodes.most()).as("never logged on from both nodes at once").isEqualTo(1);
    }

    /**
     * The leader's process runs on, so only fencing stands between the two nodes running the session at once: it must
     * stop its sessions, without a Logout, before the other node is elected, which then moves its seqnums ahead.
     */
    @ParameterizedTest
    @EnumSource(Pair.class)
    void aLeaderCutOffFromItsPeerAndItsWitnessStopsItsSessionsBeforeTheOtherNodeLeads(Pair pair) throws IOException {
        start(pair);
        int leader = leaderId();
        long expected = counterpartyIncomingSeqNum();
        int resendRequests = counterparty.resendRequests.get();

        transports[leader].cut();

        await().atMost(Duration.ofSeconds(15)).until(() -> counterparty.logons.get() == 2);
        HaStatus fenced = nodes[leader].getStatus();
        HaStatus elected = nodes[3 - leader].getStatus();
        assertThat(fenced.getRole()).as("cut off, it cannot prove it leads").isNotEqualTo(Role.LEADER);
        assertThat(elected.getRole()).isEqualTo(Role.LEADER);
        assertThat(fenced.getRoleSince()).as("stopped its sessions before the other node ran them")
                .isBefore(elected.getRoleSince());
        assertThat(counterparty.logoutsReceived.get()).isZero();
        await().until(() -> counterparty.resendRequests.get() == resendRequests + 1);
        counterparty.roundTrips(10);
        assertThat(counterpartyIncomingSeqNum()).isGreaterThan(expected + JUMP);
        assertThat(loggedOnNodes.most()).as("never logged on from both nodes at once").isEqualTo(1);
        System.out.println("FENCING " + pair + " margin: " + Duration.between(fenced.getRoleSince(), elected.getRoleSince()).toMillis() + " ms");
    }
}
