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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixAcceptorBuilder;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.FixInitiatorBuilder;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.ha.HaAdmin;
import org.lolaf.staffix.api.ha.SwitchoverOutcome;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionDesiredState;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.fix44.encoders.EmailEncoder;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;
import org.lolaf.staffix.tests.TestPorts;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * An acceptor pair, a leading and a follower engine, and the counterparty's initiator connecting to both: leadership
 * moving by a switchover or by a failover, as the counterparty sees it.
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
    private AdminApi initiator;

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

    @BeforeEach
    void startThePairAndTheCounterparty() {
        InetSocketAddress fixA = new InetSocketAddress("localhost", TestPorts.findFree());
        InetSocketAddress fixB = new InetSocketAddress("localhost", TestPorts.findFree());
        InetSocketAddress replicationA = new InetSocketAddress("localhost", TestPorts.findFree());
        InetSocketAddress replicationB = new InetSocketAddress("localhost", TestPorts.findFree());
        a = acceptor("a", fixA, replicationA, replicationB);
        b = acceptor("b", fixB, replicationA, replicationB);
        FixEngine initiatorEngine = start(engine("initiator", counterparty,
                sessionSettings(INITIATOR_ID, FixSession.FixSessionType.INITIATOR, ReplicationMode.NONE)).build().instance());
        initiator = (AdminApi) initiatorEngine;
        initiatorEngine.newInitiator(FixInitiatorBuilder.builder()
                        .connectionRetry(Duration.ofMillis(100))
                        .mainTarget(FixInitiatorTarget.builder().fixSessionId(INITIATOR_ID).connectAddress(fixA).connectAddress(fixB).build())
                        .build()).start();
        await().until(() -> counterparty.logons.get() == 1);
        counterparty.roundTrips(10);
        await().until(() -> b.getStatus().getLeader() != null && b.getStatus().getLeader().isInSync());
    }

    private HaAdmin acceptor(String nodeId, InetSocketAddress fix, InetSocketAddress replicationA, InetSocketAddress replicationB) {
        FixEngine engine = start(engine(nodeId, new EchoAcceptor(), sessionSettings(ACCEPTOR_ID, FixSession.FixSessionType.ACCEPTOR, ReplicationMode.ASYNC))
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
        engine.newAcceptor(FixAcceptorBuilder.builder().bindAddress(fix).build()).start();
        return ((AdminApi) engine).getHa().orElseThrow();
    }

    private FixEngine start(FixEngine engine) {
        engine.start();
        stops.add(() -> engine.stop(Deadline.of(Duration.ofSeconds(5))));
        return engine;
    }

    @AfterEach
    void stop() {
        stops.forEach(Runnable::run);
    }

    @Test
    void aSwitchoverLogsTheCounterpartyOutAndTheNewLeaderCarriesOnFromTheSameSequenceNumbers() {
        long expected = initiator.getIncomingSeqNum(INITIATOR_ID);

        assertThat(a.switchover()).isEqualTo(SwitchoverOutcome.TRANSFERRED);

        await().until(() -> counterparty.logons.get() == 2);
        assertThat(counterparty.logoutsReceived.get()).as("a Logout from the old leader").isEqualTo(1);
        counterparty.roundTrips(10);
        assertThat(counterparty.resendRequests.get()).as("no gap: b took over where a left").isZero();
        assertThat(initiator.getIncomingSeqNum(INITIATOR_ID)).as("a's Logout, b's Logon and ten answers").isEqualTo(expected + 12);
        assertThat(b.getStatus().getRole()).isEqualTo(org.lolaf.staffix.api.ha.LeaderElection.Role.LEADER);
    }

    @Test
    void aFailoverDropsTheCounterpartyWithoutALogoutAndTheNewLeaderMovesItsSequenceNumbersAhead() {
        long expected = initiator.getIncomingSeqNum(INITIATOR_ID);

        ballot.elect("b");

        await().until(() -> counterparty.logons.get() == 2);
        assertThat(counterparty.logoutsReceived.get()).as("the old leader stopped without a Logout").isZero();
        await().until(() -> counterparty.resendRequests.get() == 1);
        counterparty.roundTrips(10);
        assertThat(initiator.getIncomingSeqNum(INITIATOR_ID)).as("past the jump").isGreaterThan(expected + JUMP);
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

    private static final class EchoAcceptor extends Fix44Application {

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
                    session.send(answer.begin().setSubject("pong"), null);
                }
            });
        }
    }

    /**
     * The initiator, recording what the acceptor pair looks like from outside.
     */
    private static final class Counterparty extends Fix44Application {

        final AtomicInteger logons = new AtomicInteger();
        final AtomicInteger logoutsReceived = new AtomicInteger();
        final AtomicInteger resendRequests = new AtomicInteger();
        final AtomicLong answers = new AtomicLong();
        private volatile FixSession session;

        @Override
        public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
            session = fixSession;
            logons.incrementAndGet();
        }

        @Override
        public void onPreLogout(FixSession fixSession, String message, boolean logoutInitiated) {
            if (!logoutInitiated) {
                logoutsReceived.incrementAndGet();
            }
        }

        @Override
        public void onResendRequestInitiated(FixSession fixSession, long fromSeqNum, long toSeqNum) {
            resendRequests.incrementAndGet();
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
            EmailEncoder ping = session.newEncoder(EmailEncoder.class).asReusable();
            for (int i = 0; i < count; i++) {
                long expected = answers.get() + 1;
                session.send(ping.begin().setSubject("ping"), null);
                await().until(() -> answers.get() >= expected);
            }
        }
    }
}
