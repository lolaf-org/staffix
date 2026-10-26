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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixAcceptorBuilder;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.FixInitiatorBuilder;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.ha.HaAdmin;
import org.lolaf.staffix.api.ha.SwitchoverOutcome;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionDesiredState;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.stores.messages.file.FileMessageStoreSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;
import org.lolaf.staffix.tests.TestPorts;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A highly available pair, a leading and a following engine, and the counterparty they trade with: leadership moving
 * by a switchover or by a failover, as the counterparty sees it, whichever side of the session the pair runs and in
 * either replication mode.
 */
class TestSwitchover {

    private static final FixSessionId ACCEPTOR_ID = FixSessionId.of("ha", FixRegularVersion.VERSION_44, "ACCEPTOR", "INITIATOR");
    private static final FixSessionId INITIATOR_ID = FixSessionId.of("ha", FixRegularVersion.VERSION_44, "INITIATOR", "ACCEPTOR");
    private static final long JUMP = 10_000;

    private final List<Runnable> stops = new ArrayList<>();
    private final SharedLeaderElection.Ballot ballot = new SharedLeaderElection.Ballot("a");
    private final Counterparty counterparty = new Counterparty();
    private HaAdmin a;
    private HaAdmin b;
    private AdminApi counterpartyEngine;
    private FixSessionId counterpartySession;
    private Storage storage;
    private Supplier<FixEngine> startFollower;
    private FixEngine follower;
    private FixEngine leader;
    private Echo followerApplication;

    @TempDir
    Path sharedDirectory;

    /**
     * Which side of the session the highly available pair runs.
     */
    enum Pair {
        ACCEPTORS,
        INITIATORS
    }

    /**
     * Where the pair's nodes store the session: each its own, replicated, or one file directory both see, which the
     * follower detects and replicates nothing into.
     */
    enum Storage {
        SEPARATE,
        SHARED
    }

    static Stream<Arguments> pairsAndModes() {
        return Stream.of(Pair.values()).flatMap(pair -> Stream.of(ReplicationMode.ASYNC, ReplicationMode.SYNC)
                .map(mode -> Arguments.of(pair, mode)));
    }

    static Stream<Arguments> pairs() {
        return Stream.of(Storage.values()).flatMap(storage -> Stream.of(Pair.values())
                .flatMap(pair -> Stream.of(ReplicationMode.ASYNC, ReplicationMode.SYNC)
                        .map(mode -> Arguments.of(storage, pair, mode))));
    }

    private static FixSessionSettings sessionSettings(FixSessionId id, FixSession.FixSessionType type, ReplicationMode mode) {
        return sessionSettings(id, type, mode, "app");
    }

    private static FixSessionSettings sessionSettings(FixSessionId id, FixSession.FixSessionType type, ReplicationMode mode,
                                                      String applicationId) {
        return FixSessionSettings.builder()
                .fixSessionId(id)
                .fixSessionType(type)
                .fixApplicationFactoryInstanceId("app")
                .fixApplicationInstanceId(applicationId)
                .desiredSessionState(FixSessionDesiredState.LOGGED_IN)
                .haSessionSettings(HaSessionSettings.builder().replicationMode(mode).build())
                .build();
    }

    private static FixEngineBuilder.FixEngineBuilderBuilder engine(String name, FixApplication application, FixSessionSettings settings,
                                                                   FixMessagesStoreSettings store) {
        return FixEngineBuilder.builder()
                .instanceId(name)
                .fixMessagesStore(store)
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder().instanceId("app").application("app", application).build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder().fixSessionSetting(settings).build());
    }

    private static FixInitiatorBuilder initiator(FixSessionId id, InetSocketAddress... addresses) {
        FixInitiatorTarget.FixInitiatorTargetBuilder target = FixInitiatorTarget.builder().fixSessionId(id);
        for (InetSocketAddress address : addresses) {
            target.connectAddress(address);
        }
        return FixInitiatorBuilder.builder().connectionRetry(Duration.ofMillis(100)).mainTarget(target.build()).build();
    }

    /**
     * Starts the pair, a leading and b following, and the counterparty, then trades until b holds the session.
     */
    private void start(Storage storage, Pair pair, ReplicationMode mode) {
        this.storage = storage;
        InetSocketAddress replicationA = new InetSocketAddress("localhost", TestPorts.findFree());
        InetSocketAddress replicationB = new InetSocketAddress("localhost", TestPorts.findFree());
        if (pair == Pair.ACCEPTORS) {
            InetSocketAddress fixA = new InetSocketAddress("localhost", TestPorts.findFree());
            InetSocketAddress fixB = new InetSocketAddress("localhost", TestPorts.findFree());
            FixEngine engineA = haEngine("a", sessionSettings(ACCEPTOR_ID, FixSession.FixSessionType.ACCEPTOR, mode), replicationA, replicationB);
            engineA.newAcceptor(FixAcceptorBuilder.builder().bindAddress(fixA).build()).start();
            startFollower = () -> {
                FixEngine engineB = haEngine("b", sessionSettings(ACCEPTOR_ID, FixSession.FixSessionType.ACCEPTOR, mode), replicationA, replicationB);
                engineB.newAcceptor(FixAcceptorBuilder.builder().bindAddress(fixB).build()).start();
                return engineB;
            };
            follower = startFollower.get();
            FixEngine counterpartyFixEngine = start(engine("counterparty", counterparty,
                    sessionSettings(INITIATOR_ID, FixSession.FixSessionType.INITIATOR, ReplicationMode.NONE),
                    MemoryMessageStoreSettings.builder().build()).build().instance());
            counterpartyFixEngine.newInitiator(initiator(INITIATOR_ID, fixA, fixB)).start();
            counterpartyEngine = (AdminApi) counterpartyFixEngine;
            counterpartySession = INITIATOR_ID;
        } else {
            InetSocketAddress fix = new InetSocketAddress("localhost", TestPorts.findFree());
            FixEngine counterpartyFixEngine = start(engine("counterparty", counterparty,
                    sessionSettings(ACCEPTOR_ID, FixSession.FixSessionType.ACCEPTOR, ReplicationMode.NONE),
                    MemoryMessageStoreSettings.builder().build()).build().instance());
            counterpartyFixEngine.newAcceptor(FixAcceptorBuilder.builder().bindAddress(fix).build()).start();
            counterpartyEngine = (AdminApi) counterpartyFixEngine;
            counterpartySession = ACCEPTOR_ID;
            haEngine("a", sessionSettings(INITIATOR_ID, FixSession.FixSessionType.INITIATOR, mode), replicationA, replicationB)
                    .newInitiator(initiator(INITIATOR_ID, fix)).start();
            startFollower = () -> {
                FixEngine engineB = haEngine("b", sessionSettings(INITIATOR_ID, FixSession.FixSessionType.INITIATOR, mode), replicationA, replicationB);
                engineB.newInitiator(initiator(INITIATOR_ID, fix)).start();
                return engineB;
            };
            follower = startFollower.get();
        }
        await().until(() -> counterparty.logons.get() == 1);
        counterparty.roundTrips(10);
        await().until(() -> b.getStatus().getLeader() != null && b.getStatus().getLeader().isInSync());
        assertThat(b.getSessionsStatus()).singleElement().satisfies(session -> assertThat(session.isSharedStorage())
                .as("the follower sees whether its storage is the leader's").isEqualTo(storage == Storage.SHARED));
    }

    private FixEngine haEngine(String nodeId, FixSessionSettings settings, InetSocketAddress replicationA, InetSocketAddress replicationB) {
        return haEngine(nodeId, replicationA, replicationB, settings);
    }

    private FixEngine haEngine(String nodeId, InetSocketAddress replicationA, InetSocketAddress replicationB,
                               FixSessionSettings... settings) {
        Echo echo = new Echo();
        if (nodeId.equals("b")) {
            followerApplication = echo;
        }
        FixEngine engine = start(engine(nodeId, echo, settings[0], storage == Storage.SHARED
                ? FileMessageStoreSettings.builder().storageDirectoryPath(sharedDirectory.toString()).build()
                : MemoryMessageStoreSettings.builder().build())
                .clearFixSessionsSettingsStores()
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder().fixSessionSettings(List.of(settings)).build())
                .highAvailability(ReplicationHaSettings.builder()
                        .nodeId(nodeId)
                        .peer("a", replicationA)
                        .peer("b", replicationB)
                        .heartbeatInterval(Duration.ofMillis(100))
                        .reconnectInterval(Duration.ofMillis(50))
                        .promotionOutgoingSeqNumJump(JUMP)
                        .leaderElection(SharedLeaderElection.Settings.builder().ballot(ballot).nodeId(nodeId).build())
                        .build())
                .build().instance());
        HaAdmin ha = ((AdminApi) engine).getHa().orElseThrow();
        if (nodeId.equals("a")) {
            a = ha;
            leader = engine;
        } else {
            b = ha;
        }
        return engine;
    }

    private FixEngine start(FixEngine engine) {
        engine.start();
        stops.add(() -> engine.stop(Deadline.of(Duration.ofSeconds(5))));
        return engine;
    }

    private long counterpartyIncomingSeqNum() {
        return counterpartyEngine.getIncomingSeqNum(counterpartySession);
    }

    @AfterEach
    void stop() {
        stops.forEach(Runnable::run);
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("pairs")
    void aSwitchoverLogsTheCounterpartyOutAndTheNewLeaderCarriesOnFromTheSameSequenceNumbers(Storage storage, Pair pair,
                                                                                              ReplicationMode mode) {
        start(storage, pair, mode);
        long expected = counterpartyIncomingSeqNum();

        assertThat(a.switchover()).isEqualTo(SwitchoverOutcome.TRANSFERRED);

        await().until(() -> counterparty.logons.get() == 2);
        assertThat(counterparty.logoutsReceived.get()).as("a Logout from the old leader").isEqualTo(1);
        counterparty.roundTrips(10);
        assertThat(counterparty.resendRequests.get()).as("no gap: b took over where a left").isZero();
        assertThat(counterpartyIncomingSeqNum()).as("a's Logout, b's Logon and ten answers").isEqualTo(expected + 12);
        assertThat(b.getStatus().getRole()).isEqualTo(org.lolaf.staffix.api.ha.LeaderElection.Role.LEADER);
    }

    /**
     * Shared storage too: the old leader may have sent a message it never stored.
     */
    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("pairs")
    void aFailoverDropsTheCounterpartyWithoutALogoutAndTheNewLeaderMovesItsSequenceNumbersAhead(Storage storage, Pair pair,
                                                                                                 ReplicationMode mode) {
        start(storage, pair, mode);
        long expected = counterpartyIncomingSeqNum();

        ballot.elect("b");

        await().until(() -> counterparty.logons.get() == 2);
        assertThat(counterparty.logoutsReceived.get()).as("the old leader stopped without a Logout").isZero();
        await().until(() -> counterparty.resendRequests.get() == 1);
        counterparty.roundTrips(10);
        assertThat(counterpartyIncomingSeqNum()).as("past the jump").isGreaterThan(expected + JUMP);
    }

    /**
     * A restarted follower holds nothing, its replica being in memory: only a full snapshot lets it take over without
     * a jump.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("pairsAndModes")
    void aFollowerRestartedWhileTheLeaderTradesIsCaughtUpAndTakesOverCleanly(Pair pair, ReplicationMode mode) {
        start(Storage.SEPARATE, pair, mode);

        follower.stop(Deadline.of(Duration.ofSeconds(5)));
        counterparty.roundTrips(10);
        follower = startFollower.get();
        await().until(() -> b.getStatus().getLeader() != null && b.getStatus().getLeader().isInSync());
        counterparty.roundTrips(10);
        long expected = counterpartyIncomingSeqNum();

        assertThat(a.switchover()).isEqualTo(SwitchoverOutcome.TRANSFERRED);

        await().until(() -> counterparty.logons.get() == 2);
        counterparty.roundTrips(10);
        assertThat(counterparty.resendRequests.get()).as("no gap: the snapshot gave b all of a's state").isZero();
        assertThat(counterpartyIncomingSeqNum()).as("a's Logout, b's Logon and ten answers").isEqualTo(expected + 12);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("pairsAndModes")
    void aLeaderStoppingCleanlyLogsTheCounterpartyOutAndItsFollowerCarriesOnWithoutAJump(Pair pair, ReplicationMode mode) {
        start(Storage.SEPARATE, pair, mode);
        long expected = counterpartyIncomingSeqNum();

        leader.stop(Deadline.of(Duration.ofSeconds(5)));
        // a primos node staffix runs hands leadership over as it stops, after the engine
        ballot.elect("b");

        await().until(() -> counterparty.logons.get() == 2);
        assertThat(counterparty.logoutsReceived.get()).as("a Logout from the stopping leader").isEqualTo(1);
        counterparty.roundTrips(10);
        assertThat(counterparty.resendRequests.get()).as("no gap: a handed over every write as it stopped").isZero();
        assertThat(counterpartyIncomingSeqNum()).as("a's Logout, b's Logon and ten answers").isEqualTo(expected + 12);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("pairsAndModes")
    void aPairThatFailedOverFailsBackCleanlyOnceTheOldLeaderCaughtUp(Pair pair, ReplicationMode mode) {
        start(Storage.SEPARATE, pair, mode);
        ballot.elect("b");
        await().until(() -> counterparty.logons.get() == 2 && counterparty.resendRequests.get() == 1);
        counterparty.roundTrips(10);
        await().until(() -> a.getStatus().getLeader() != null && a.getStatus().getLeader().isInSync());
        long expected = counterpartyIncomingSeqNum();

        assertThat(b.switchover()).isEqualTo(SwitchoverOutcome.TRANSFERRED);

        await().until(() -> counterparty.logons.get() == 3);
        counterparty.roundTrips(10);
        assertThat(counterparty.resendRequests.get()).as("only the failover's gap: a caught up on b, newer epoch included")
                .isEqualTo(1);
        assertThat(counterpartyIncomingSeqNum()).as("b's Logout, a's Logon and ten answers").isEqualTo(expected + 12);
        assertThat(a.getStatus().getRole()).isEqualTo(org.lolaf.staffix.api.ha.LeaderElection.Role.LEADER);
    }

    /**
     * One pair of acceptors serving three sessions: the replicated ones move together. The one that is not starts on the
     * new leader from that node's own empty store: its counterparty refuses its Logons as MsgSeqNum too low until the
     * refused Logons have carried its seqnum past the counterparty's, then the new leader asks for everything from 1 and
     * its application receives again what the old leader already processed.
     */
    @org.junit.jupiter.api.Test
    void severalSessionsSwitchOverTogetherAndOnlyTheUnreplicatedOneIsRefused() {
        storage = Storage.SEPARATE;
        ReplicationMode[] modes = {ReplicationMode.ASYNC, ReplicationMode.SYNC, ReplicationMode.NONE};
        InetSocketAddress replicationA = new InetSocketAddress("localhost", TestPorts.findFree());
        InetSocketAddress replicationB = new InetSocketAddress("localhost", TestPorts.findFree());
        InetSocketAddress fixA = new InetSocketAddress("localhost", TestPorts.findFree());
        InetSocketAddress fixB = new InetSocketAddress("localhost", TestPorts.findFree());
        FixSessionSettings[] pairSessions = new FixSessionSettings[modes.length];
        FixSessionSettings[] counterpartySessions = new FixSessionSettings[modes.length];
        Counterparty[] counterparties = new Counterparty[modes.length];
        SimpleApplicationFactorySettings.SimpleApplicationFactorySettingsBuilder applications =
                SimpleApplicationFactorySettings.builder().instanceId("app");
        for (int i = 0; i < modes.length; i++) {
            String name = modes[i].name().toLowerCase();
            pairSessions[i] = sessionSettings(FixSessionId.of(name, FixRegularVersion.VERSION_44, "ACCEPTOR", name.toUpperCase()),
                    FixSession.FixSessionType.ACCEPTOR, modes[i]);
            counterpartySessions[i] = sessionSettings(FixSessionId.of(name, FixRegularVersion.VERSION_44, name.toUpperCase(), "ACCEPTOR"),
                    FixSession.FixSessionType.INITIATOR, ReplicationMode.NONE, name);
            counterparties[i] = new Counterparty();
            applications.application(name, counterparties[i]);
        }
        haEngine("a", replicationA, replicationB, pairSessions).newAcceptor(FixAcceptorBuilder.builder().bindAddress(fixA).build()).start();
        haEngine("b", replicationA, replicationB, pairSessions).newAcceptor(FixAcceptorBuilder.builder().bindAddress(fixB).build()).start();
        FixEngine counterpartyFixEngine = start(FixEngineBuilder.builder()
                .instanceId("counterparty")
                .fixMessagesStore(MemoryMessageStoreSettings.builder().build())
                .fixApplicationFactory(applications.build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder().fixSessionSettings(List.of(counterpartySessions)).build())
                .build().instance());
        for (FixSessionSettings session : counterpartySessions) {
            counterpartyFixEngine.newInitiator(initiator(session.getFixSessionId(), fixA, fixB).toBuilder()
                    .instanceId(session.getFixSessionId().getName()).build()).start();
        }
        AdminApi counterpartyAdmin = (AdminApi) counterpartyFixEngine;
        for (Counterparty counterparty : counterparties) {
            await().until(() -> counterparty.logons.get() == 1);
            counterparty.roundTrips(10);
        }
        await().until(() -> b.getStatus().getLeader() != null && b.getStatus().getLeader().isInSync());
        assertThat(b.getSessionsStatus()).as("the session not replicated is not listed").hasSize(2);
        long[] expected = new long[modes.length];
        for (int i = 0; i < modes.length; i++) {
            expected[i] = counterpartyAdmin.getIncomingSeqNum(counterpartySessions[i].getFixSessionId());
        }

        assertThat(a.switchover()).isEqualTo(SwitchoverOutcome.TRANSFERRED);

        Counterparty unreplicated = counterparties[2];
        await().until(() -> counterparties[0].logons.get() == 2 && counterparties[1].logons.get() == 2);
        assertThat(unreplicated.logons.get()).as("b's Logons are refused as MsgSeqNum too low, its store being empty")
                .isEqualTo(1);
        for (int i = 0; i < 2; i++) {
            Counterparty counterparty = counterparties[i];
            counterparty.roundTrips(10);
            assertThat(counterparty.resendRequests.get()).as("%s: no gap", modes[i]).isZero();
            assertThat(counterpartyAdmin.getIncomingSeqNum(counterpartySessions[i].getFixSessionId()))
                    .as("%s: a's Logout, b's Logon and ten answers", modes[i]).isEqualTo(expected[i] + 12);
        }
        assertThat(unreplicated.logoutsReceived.get()).as("logged out cleanly by a, like the others").isEqualTo(1);
        await().until(() -> unreplicated.logons.get() == 2);
        await().untilAsserted(() -> assertThat(followerApplication.possibleDuplicates.get("none")).as("b's seqnums climbed past "
                + "the counterparty's through the refused Logons, then b asked it to resend from 1: the counterparty's old "
                + "messages, which a already processed, reach b's application again").hasValueGreaterThanOrEqualTo(10));
        assertThat(followerApplication.possibleDuplicates.get("async")).hasValue(0);
        assertThat(followerApplication.possibleDuplicates.get("sync")).hasValue(0);
    }
}
