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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.*;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.alerting.ProbeScope;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.ha.HaAdmin;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.api.ha.SwitchoverOutcome;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionDesiredState;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.session.FixSessionsSettingsStoreSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.impl.alerting.ProbesImpl;
import org.lolaf.staffix.stores.messages.file.FileMessageStoreSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessagesStore;
import org.lolaf.staffix.stores.sessions.file.FileSessionsSettingsStoreSettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;
import org.lolaf.staffix.tests.TestPorts;
import org.lolaf.staffix.tests.TestingFixMessagesStoreSettings;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
    @TempDir
    Path storageDirectory;
    private HaAdmin a;
    private HaAdmin b;
    private AdminApi counterpartyEngine;
    private FixSessionId counterpartySession;
    private boolean sharedStorage;
    private boolean followerMissesTheSession;
    private boolean fileSettingsStores;
    private boolean sharedSettingsDirectory;
    private boolean sessionSettingsReplicated = true;
    private Duration switchoverTimeout = Duration.ofSeconds(30);
    private Duration followerStallTimeout = Duration.ofSeconds(10);
    private HoldableStore followerStore;
    private Supplier<FixEngine> startFollower;
    private FixEngine follower;
    private FixEngine leader;
    private Echo followerApplication;

    static Stream<Arguments> pairsAndModes() {
        return Stream.of(Pair.values()).flatMap(pair -> Stream.of(ReplicationMode.ASYNC, ReplicationMode.SYNC_RECEIVED, ReplicationMode.SYNC_APPLIED)
                .map(mode -> Arguments.of(pair, mode)));
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
                .highAvailability(HaSessionSettings.builder().replicationMode(mode).build())
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
        // the counterparty receives a message of several MiB, past the 1 MiB betty grows its read buffer to by default
        return FixInitiatorBuilder.builder().connectionRetry(Duration.ofMillis(100)).mainTarget(target.build())
                .ioSettings(IOSettings.builder().maxReadBufferSize(8 << 20).build()).build();
    }

    /**
     * A fresh pair's first leader moved its sessions ahead as it took the lead, so the first Logon showed a gap.
     */
    private static void countGapsFromHere(Counterparty counterparty) {
        assertThat(counterparty.resendRequests.get()).as("the boot jump").isEqualTo(1);
        counterparty.resendRequests.set(0);
    }

    private void start(Pair pair, ReplicationMode mode) {
        startPair(pair, mode);
        await().until(() -> b.getStatus().getLeader() != null && b.getStatus().getLeader().isInSync());
    }

    private void startPair(Pair pair, ReplicationMode mode) {
        InetSocketAddress replicationA = new InetSocketAddress("localhost", TestPorts.findFree());
        InetSocketAddress replicationB = new InetSocketAddress("localhost", TestPorts.findFree());
        if (pair == Pair.ACCEPTORS) {
            InetSocketAddress fixA = new InetSocketAddress("localhost", TestPorts.findFree());
            InetSocketAddress fixB = new InetSocketAddress("localhost", TestPorts.findFree());
            FixEngine engineA = haEngine("a", sessionSettings(ACCEPTOR_ID, FixSession.FixSessionType.ACCEPTOR, mode), replicationA, replicationB);
            engineA.newAcceptor(FixAcceptorBuilder.builder().bindAddress(fixA).build()).start();
            startFollower = () -> {
                FixEngine engineB = haEngine("b", sessionSettings(ACCEPTOR_ID, FixSession.FixSessionType.ACCEPTOR, mode), replicationA, replicationB);
                if (followerMissesTheSession) {
                    engineB.getFixSessionsSettingsStores().get(0).remove(sessionSettings(ACCEPTOR_ID, FixSession.FixSessionType.ACCEPTOR, mode));
                }
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
        countGapsFromHere(counterparty);
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
        FixEngine engine = start(engine(nodeId, echo, settings[0], storeOf(nodeId))
                .clearFixSessionsSettingsStores()
                .fixSessionsSettingsStore(settingsStoreOf(nodeId, settings))
                .highAvailability(ReplicationHaSettings.builder()
                        .sessionSettingsReplicated(sessionSettingsReplicated)
                        .nodeId(nodeId)
                        .peer("a", replicationA)
                        .peer("b", replicationB)
                        .heartbeatInterval(Duration.ofMillis(100))
                        .reconnectInterval(Duration.ofMillis(50))
                        .promotionOutgoingSeqNumJump(JUMP)
                        .switchoverTimeout(switchoverTimeout)
                        .followerStallTimeout(followerStallTimeout)
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

    private FixSessionsSettingsStoreSettings settingsStoreOf(String nodeId, FixSessionSettings... settings) {
        if (!fileSettingsStores) {
            return MemorySessionsSettingsStoreSettings.builder().fixSessionSettings(List.of(settings)).build();
        }
        FileSessionsSettingsStoreSettings fileStore = FileSessionsSettingsStoreSettings.builder()
                .fixSessionSettingsDirectory(settingsDirectory(nodeId)).build();
        FixSessionsSettingsStore seeding = fileStore.instance();
        seeding.start();
        for (FixSessionSettings session : settings) {
            if (seeding.find(session.getFixSessionId(), session.getFixSessionType()).isEmpty()) {
                seeding.add(session);
            }
        }
        seeding.stop(Deadline.of(Duration.ofSeconds(5)));
        return fileStore;
    }

    private File settingsDirectory(String nodeId) {
        return storageDirectory.resolve(sharedSettingsDirectory ? "settings" : "settings-" + nodeId).toFile();
    }

    private static FixSessionSettings addedSession(FixSessionSettings settings) {
        return settings.toBuilder().fixSessionId(FixSessionId.of("added", FixRegularVersion.VERSION_44, "ADDED", "CLIENT")).build();
    }

    private FixSessionsSettingsStore leaderSettings() {
        return leader.getFixSessionsSettingsStores().get(0);
    }

    private FixSessionsSettingsStore followerSettings() {
        return follower.getFixSessionsSettingsStores().get(0);
    }

    private void awaitNoSettingsDifference() {
        await().untilAsserted(() -> assertThat(b.getStatus().getSessionSettingsDifferences()).isEmpty());
    }

    private String settingsFile(String nodeId, FixSessionSettings settings) throws IOException {
        return Files.readString(new File(settingsDirectory(nodeId), settings.getFixSessionId().forFileName(".yaml")).toPath());
    }

    @org.junit.jupiter.api.Test
    void aSessionAddedAndChangedOnTheLeaderReachesTheFollower() {
        fileSettingsStores = true;
        startPair(Pair.ACCEPTORS, ReplicationMode.ASYNC);
        awaitNoSettingsDifference();
        FixSessionSettings added = addedSession(leaderSettings().getSettings().iterator().next());

        leaderSettings().add(added);

        await().until(() -> followerSettings().find(added.getFixSessionId(), added.getFixSessionType()).isPresent());
        FixSessionSettings changed = added.toBuilder().logInOrOutResponseTimeout(Duration.ofSeconds(77)).build();
        leaderSettings().update(changed);

        await().untilAsserted(() -> assertThat(followerSettings().find(added.getFixSessionId(), added.getFixSessionType()).orElseThrow()
                .getLogInOrOutResponseTimeout()).isEqualTo(Duration.ofSeconds(77)));
        awaitNoSettingsDifference();
        await().untilAsserted(() -> assertThat(a.getSessionStatuses()).hasSize(2).allMatch(HaSessionStatus::isInSync));
    }

    @org.junit.jupiter.api.Test
    void aSessionAddedWhileTheFollowerWasDownReachesItWhenItReturns() {
        fileSettingsStores = true;
        startPair(Pair.ACCEPTORS, ReplicationMode.ASYNC);
        FixSessionSettings added = addedSession(leaderSettings().getSettings().iterator().next());
        follower.stop(Deadline.of(Duration.ofSeconds(5)));

        leaderSettings().add(added);
        follower = startFollower.get();

        await().until(() -> followerSettings().find(added.getFixSessionId(), added.getFixSessionType()).isPresent());
        awaitNoSettingsDifference();
    }

    @org.junit.jupiter.api.Test
    void aPlaceholderReachesTheFollowerAsAnExpressionAndNeverAsItsValue() throws IOException {
        String property = "staffix.test.ha.settings.timeout";
        String expression = "${sysprop:" + property + "}";
        System.setProperty(property, "PT45S");
        try {
            fileSettingsStores = true;
            startPair(Pair.ACCEPTORS, ReplicationMode.ASYNC);
            FixSessionSettings added = addedSession(leaderSettings().getSettings().iterator().next()).toBuilder()
                    .logInOrOutResponseTimeout(Duration.ofSeconds(45))
                    .placeholders(Map.of("/logInOrOutResponseTimeout", expression))
                    .build();

            leaderSettings().add(added);

            await().until(() -> followerSettings().find(added.getFixSessionId(), added.getFixSessionType()).isPresent());
            assertThat(settingsFile("b", added)).contains(expression).doesNotContain("PT45S");
            assertThat(followerSettings().find(added.getFixSessionId(), added.getFixSessionType()).orElseThrow().getLogInOrOutResponseTimeout())
                    .isEqualTo(Duration.ofSeconds(45));
            awaitNoSettingsDifference();
        } finally {
            System.clearProperty(property);
        }
    }

    @org.junit.jupiter.api.Test
    void withSessionSettingsNotReplicatedADifferenceIsOnlyReported() {
        fileSettingsStores = true;
        sessionSettingsReplicated = false;
        startPair(Pair.ACCEPTORS, ReplicationMode.ASYNC);
        FixSessionSettings added = addedSession(leaderSettings().getSettings().iterator().next());

        leaderSettings().add(added);

        await().untilAsserted(() -> assertThat(b.getStatus().getSessionSettingsDifferences()).singleElement()
                .satisfies(difference -> assertThat(difference.getKind()).isEqualTo(HaStatus.SessionSettingsDifference.Kind.MISSING_HERE)));
        assertThat(followerSettings().find(added.getFixSessionId(), added.getFixSessionType())).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void aFollowerSharingTheLeadersSettingsDirectoryPicksUpAChangeWithoutWritingIt() throws IOException {
        fileSettingsStores = true;
        sharedSettingsDirectory = true;
        startPair(Pair.ACCEPTORS, ReplicationMode.ASYNC);
        awaitNoSettingsDifference();
        FixSessionSettings added = addedSession(leaderSettings().getSettings().iterator().next());

        leaderSettings().add(added);

        await().until(() -> followerSettings().find(added.getFixSessionId(), added.getFixSessionType()).isPresent());
        awaitNoSettingsDifference();
        File file = new File(settingsDirectory("a"), added.getFixSessionId().forFileName(".yaml"));
        long writtenByTheLeaderAt = file.lastModified();
        assertThat(settingsFile("a", added)).contains("ADDED");
        await().during(Duration.ofMillis(300)).until(() -> file.lastModified() == writtenByTheLeaderAt);
    }

    private FixMessagesStoreSettings storeOf(String nodeId) {
        if (followerStore != null && nodeId.equals("b")) {
            return TestingFixMessagesStoreSettings.builder().testingFixSessionMessagesStore(followerStore).build();
        }
        return sharedStorage
                ? FileMessageStoreSettings.builder().storageDirectoryPath(storageDirectory.toString()).syncWrites(false).build()
                : MemoryMessageStoreSettings.builder().build();
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

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("pairsAndModes")
    void aSwitchoverLogsTheCounterpartyOutAndTheNewLeaderCarriesOnFromTheSameSequenceNumbers(Pair pair, ReplicationMode mode) {
        start(pair, mode);
        long expected = counterpartyIncomingSeqNum();

        assertThat(a.switchover()).isEqualTo(SwitchoverOutcome.TRANSFERRED);

        await().until(() -> counterparty.logons.get() == 2);
        assertThat(counterparty.logoutsReceived.get()).as("a Logout from the old leader").isEqualTo(1);
        counterparty.roundTrips(10);
        assertThat(counterparty.resendRequests.get()).as("no gap: b took over where a left").isZero();
        assertThat(counterpartyIncomingSeqNum()).as("a's Logout, b's Logon and ten answers").isEqualTo(expected + 12);
        assertThat(b.getStatus().getRole()).isEqualTo(org.lolaf.staffix.api.ha.LeaderElection.Role.LEADER);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("pairsAndModes")
    void aFailoverDropsTheCounterpartyWithoutALogoutAndTheNewLeaderMovesItsSequenceNumbersAhead(Pair pair, ReplicationMode mode) {
        start(pair, mode);
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
        start(pair, mode);

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

    /**
     * The session's writes during the snapshot reach the follower through it, and its end carries the seqnums they
     * moved.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("pairsAndModes")
    void aFollowerRestartedWhileTheCounterpartyKeepsTradingIsCaughtUpAndTakesOverCleanly(Pair pair, ReplicationMode mode)
            throws InterruptedException {
        start(pair, mode);
        counterparty.roundTrips(100);
        follower.stop(Deadline.of(Duration.ofSeconds(5)));
        AtomicBoolean trading = new AtomicBoolean(true);
        Thread trader = new Thread(() -> {
            while (trading.get()) {
                counterparty.roundTrips(1);
            }
        });
        trader.start();

        follower = startFollower.get();
        await().until(() -> b.getStatus().getLeader() != null && b.getStatus().getLeader().isInSync());
        trading.set(false);
        trader.join();
        long expected = counterpartyIncomingSeqNum();

        assertThat(a.switchover()).isEqualTo(SwitchoverOutcome.TRANSFERRED);

        await().until(() -> counterparty.logons.get() == 2);
        counterparty.roundTrips(10);
        assertThat(counterparty.resendRequests.get()).as("no gap: the snapshot gave b all of a's state").isZero();
        assertThat(counterpartyIncomingSeqNum()).as("a's Logout, b's Logon and ten answers").isEqualTo(expected + 12);
    }

    /**
     * Unlike a restarted follower, a dropped one keeps its copy, which the snapshot replaces under a running session.
     */
    @ParameterizedTest
    @EnumSource(value = ReplicationMode.class, names = {"ASYNC", "SYNC_RECEIVED", "SYNC_APPLIED"})
    void aFollowerDroppedAsTooSlowIsCaughtUpAndTakesOverCleanly(ReplicationMode mode) {
        followerStore = new HoldableStore(new MemoryMessagesStore.MemoryStoreFactoryImpl()
                .newInstance(MemoryMessageStoreSettings.builder().build()).getStore(ACCEPTOR_ID, new ProbesImpl(ProbeScope.builder().type("session").name("follower").build())));
        followerStallTimeout = Duration.ZERO;
        start(Pair.ACCEPTORS, mode);

        int resetsBefore = followerStore.resets();
        followerStore.hold();
        try {
            for (int answers = 0; a.getStatus().getDroppedFollowers().isEmpty(); answers++) {
                assertThat(answers).as("big answers before the log filled up").isLessThan(20);
                counterparty.bigRoundTrip();
            }
            assertThat(counterparty.logons.get()).as("the writes waited, the session stayed up").isEqualTo(1);
        } finally {
            followerStore.release();
        }

        await().atMost(Duration.ofSeconds(60)).until(() -> a.getStatus().getDroppedFollowers().isEmpty());
        await().until(() -> b.getStatus().getLeader() != null && b.getStatus().getLeader().isInSync());
        assertThat(followerStore.resets()).as("a snapshot replaced the follower's copy").isGreaterThan(resetsBefore);
        counterparty.roundTrips(10);
        long expected = counterpartyIncomingSeqNum();

        assertThat(a.switchover()).isEqualTo(SwitchoverOutcome.TRANSFERRED);

        await().until(() -> counterparty.logons.get() == 2);
        counterparty.roundTrips(10);
        assertThat(counterparty.resendRequests.get()).as("no gap: the snapshot gave b all of a's state").isZero();
        assertThat(counterpartyIncomingSeqNum()).as("a's Logout, b's Logon and ten answers").isEqualTo(expected + 12);
    }

    @ParameterizedTest
    @EnumSource(value = ReplicationMode.class, names = {"ASYNC", "SYNC_RECEIVED", "SYNC_APPLIED"})
    void aMessageLongerThanALogFrameReachesTheFollowerWholeAndTheNewLeaderResendsIt(ReplicationMode mode) {
        start(Pair.ACCEPTORS, mode);
        long bigSeqNum = counterpartyIncomingSeqNum();
        counterparty.bigRoundTrip();
        assertThat(counterparty.lastTextLength.get()).isEqualTo(Counterparty.BIG_ANSWER_LENGTH);

        assertThat(a.switchover()).isEqualTo(SwitchoverOutcome.TRANSFERRED);
        await().until(() -> counterparty.logons.get() == 2);
        counterpartyEngine.setIncomingSeqNum(counterpartySession, bigSeqNum);
        counterparty.roundTrips(1);

        await().atMost(Duration.ofSeconds(30)).until(() -> counterparty.longestPossibleDuplicate.get() == Counterparty.BIG_ANSWER_LENGTH);
        await().alias("a, now following, is caught up by a snapshot carrying the big message in fragments")
                .until(() -> a.getStatus().getLeader() != null && a.getStatus().getLeader().isInSync());
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("pairsAndModes")
    void aLeaderStoppingCleanlyLogsTheCounterpartyOutAndItsFollowerCarriesOnWithoutAJump(Pair pair, ReplicationMode mode) {
        start(pair, mode);
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
        start(pair, mode);
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

    @org.junit.jupiter.api.Test
    void aSessionAddedToTheFollowersSettingsStoreIsReplicatedWithoutARestart() {
        followerMissesTheSession = true;
        startPair(Pair.ACCEPTORS, ReplicationMode.ASYNC);
        await().untilAsserted(() -> assertThat(a.getSessionStatuses()).singleElement().satisfies(session -> {
            assertThat(session.isInSync()).isFalse();
            assertThat(session.getNotReplicatedReason()).isEqualTo("UNKNOWN_SESSION");
        }));

        follower.getFixSessionsSettingsStores().get(0).add(sessionSettings(ACCEPTOR_ID, FixSession.FixSessionType.ACCEPTOR, ReplicationMode.ASYNC));

        await().untilAsserted(() -> assertThat(a.getSessionStatuses()).singleElement().satisfies(session -> {
            assertThat(session.isInSync()).isTrue();
            assertThat(session.getNotReplicatedReason()).isNull();
        }));
    }

    @org.junit.jupiter.api.Test
    void theFollowerReportsWhereItsSessionSettingsDifferFromTheLeadersAndStopsOnceTheyAgree() {
        startPair(Pair.ACCEPTORS, ReplicationMode.ASYNC);
        await().untilAsserted(() -> assertThat(b.getStatus().getSessionSettingsDifferences()).isEmpty());
        FixSessionSettings settings = follower.getFixSessionsSettingsStores().get(0).getSettings().iterator().next();
        FixSessionSettings changed = settings.toBuilder().logInOrOutResponseTimeout(Duration.ofSeconds(77)).build();

        follower.getFixSessionsSettingsStores().get(0).update(changed);

        await().untilAsserted(() -> assertThat(b.getStatus().getSessionSettingsDifferences()).singleElement()
                .satisfies(difference -> assertThat(difference.getKind()).isEqualTo(HaStatus.SessionSettingsDifference.Kind.DIFFERENT)));

        leader.getFixSessionsSettingsStores().get(0).update(changed.toBuilder().restartLiveSessionOnUpdate(false).build());
        follower.getFixSessionsSettingsStores().get(0).update(changed.toBuilder().restartLiveSessionOnUpdate(false).build());

        await().untilAsserted(() -> assertThat(b.getStatus().getSessionSettingsDifferences()).isEmpty());

        FixSessionSettings extra = settings.toBuilder().fixSessionId(FixSessionId.of("extra", FixRegularVersion.VERSION_44, "EXTRA", "CLIENT")).build();
        leader.getFixSessionsSettingsStores().get(0).add(extra);

        await().untilAsserted(() -> assertThat(b.getStatus().getSessionSettingsDifferences()).singleElement()
                .satisfies(difference -> assertThat(difference.getKind()).isEqualTo(HaStatus.SessionSettingsDifference.Kind.MISSING_HERE)));
    }

    /**
     * Replicating into the leader's own storage would rewrite its data, so the follower refuses it: nothing is in sync
     * to switch over to, and a failover takes the session over from the storage, moving its seqnums ahead.
     */
    @org.junit.jupiter.api.Test
    void aStorageSharedWithTheLeaderIsRefusedAndAFailoverStillMovesTheSequenceNumbersAhead() {
        sharedStorage = true;
        switchoverTimeout = Duration.ofMillis(500);
        startPair(Pair.ACCEPTORS, ReplicationMode.SYNC_APPLIED);
        await().untilAsserted(() -> assertThat(a.getSessionStatuses()).singleElement().satisfies(session -> {
            assertThat(session.isInSync()).isFalse();
            assertThat(session.getNotReplicatedReason()).isEqualTo("SHARED_STORAGE");
        }));
        assertThat(b.getSessionStatuses()).singleElement()
                .satisfies(session -> assertThat(session.getNotReplicatedReason()).isEqualTo("SHARED_STORAGE"));

        assertThat(a.switchover()).isEqualTo(SwitchoverOutcome.NO_FOLLOWER_IN_SYNC);
        counterparty.roundTrips(10);
        long expected = counterpartyIncomingSeqNum();

        ballot.elect("b");

        await().until(() -> counterparty.logons.get() == 2);
        await().until(() -> counterparty.resendRequests.get() == 1);
        counterparty.roundTrips(10);
        assertThat(counterpartyIncomingSeqNum()).as("past the jump").isGreaterThan(expected + JUMP);
    }

    /**
     * One pair of acceptors serving three sessions: the replicated ones move together. The one that is not starts on the
     * new leader from that node's own empty store: its counterparty refuses its Logons as MsgSeqNum too low until the
     * refused Logons have carried its seqnum past the counterparty's, then the new leader asks for everything from 1 and
     * its application receives again what the old leader already processed.
     */
    @org.junit.jupiter.api.Test
    void severalSessionsSwitchOverTogetherAndOnlyTheUnreplicatedOneIsRefused() {
        ReplicationMode[] modes = {ReplicationMode.ASYNC, ReplicationMode.SYNC_APPLIED, ReplicationMode.NONE};
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
        countGapsFromHere(counterparties[0]);
        countGapsFromHere(counterparties[1]);
        await().until(() -> b.getStatus().getLeader() != null && b.getStatus().getLeader().isInSync());
        assertThat(b.getSessionStatuses()).as("the session not replicated is not listed").hasSize(2);
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
        assertThat(followerApplication.possibleDuplicates.get("sync_applied")).hasValue(0);
    }

    /**
     * Which side of the session the highly available pair runs.
     */
    enum Pair {
        ACCEPTORS,
        INITIATORS
    }
}
