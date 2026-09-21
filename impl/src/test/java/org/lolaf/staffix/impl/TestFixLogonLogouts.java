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
package org.lolaf.staffix.impl;

import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.fix44.fields.EncryptMethod;
import org.lolaf.staffix.fix44.fields.HeartBtInt;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;
import org.mockito.Mockito;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;

class TestFixLogonLogouts extends AbstractFixTests {

    /**
     * Long enough to cover several connection retries, which is what turns "it is down right now" into "it stays
     * down" - the difference the logout tests are actually about.
     */
    private static final Duration STAYS_PUT = Duration.ofSeconds(2);

    @Override
    void setupMessageDecoders(ConnectorType connectorType) {
        super.setupMessageDecoders(connectorType);
        Map<MessageType, FixMessageDecoder> targetMap = connectorType.select(initiatorMessageDecoders, acceptorMessageDecoders);
        FixMessageDecoder incomingAdditionalDecoder = connectorType.select(spy(new TestingExecutionReportDecoder()), spy(new TestingNewOrderSingleDecoder()));
        targetMap.put(incomingAdditionalDecoder.getMessageType(), incomingAdditionalDecoder);

        when(getFixApplication(connectorType).setup(any(), any(),
                Mockito.argThat(argument -> {
                    argument.add(connectorType.select(MessageTypes.NewOrderSingle, MessageTypes.ExecutionReport));
                    return true;
                }))).thenReturn(List.of(incomingAdditionalDecoder));
    }

    /**
     * The logout timeout disconnects from the scheduler and waits for the IO thread to do it; the IO thread's
     * disconnection used to cancel that very task with an interrupt, which cut the wait short and had the connection
     * torn down a second time: two disconnections, then two connection attempts dialling at once.
     */
    @Test
    void testUnansweredLogoutDisconnectsOnce() throws Exception {
        setupInitiatorSessionSettings(s -> s.logInOrOutResponseTimeout(Duration.ofMillis(500)).build());
        FixSessionId initiator = getInitiatorFixSessionSettings().build().getFixSessionId();
        try (ServerSocket rawAcceptor = new ServerSocket(acceptorPort)) {
            fixInitiator.start();
            trapCreatedFixSession(ConnectorType.INITIATOR);
            fixInitiatorSession.logon();
            try (RawFixSocketClient.Session peer = RawFixSocketClient.wrap(rawAcceptor.accept(), initiator.getFixVersion(),
                    initiator.getTargetCompID().getValue(), initiator.getSenderCompID().getValue())) {
                peer.readMessageOfType(MessageTypes.Logon, Duration.ofSeconds(10));
                peer.send(peer.message(MessageTypes.Logon, 1).set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));
                await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());

                fixInitiatorSession.disconnect("never answered");
                peer.readMessageOfType(MessageTypes.Logout, Duration.ofSeconds(10));

                assertThat(peer.isClosedByPeer(Duration.ofSeconds(10))).isTrue();
            }
        }
        // longer than the gap between the two disconnections this used to produce
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(500));
        verify(fixInitiatorApplication, times(1)).onDisconnected(any(FixSession.class));
    }

    /**
     * A stop while a Logout is already waiting for its answer cancels that Logout's timeout, then waits for the
     * session to be logged out: with an unlimited deadline and a peer that never answers, it used to wait forever.
     */
    @Test
    void testStopWithUnlimitedDeadlineDoesNotWaitForeverForAnUnansweredLogout() throws Exception {
        setupInitiatorSessionSettings(s -> s.logInOrOutResponseTimeout(Duration.ofSeconds(2)).build());
        FixSessionId initiator = getInitiatorFixSessionSettings().build().getFixSessionId();
        try (ServerSocket rawAcceptor = new ServerSocket(acceptorPort)) {
            fixInitiator.start();
            trapCreatedFixSession(ConnectorType.INITIATOR);
            fixInitiatorSession.logon();
            try (RawFixSocketClient.Session peer = RawFixSocketClient.wrap(rawAcceptor.accept(), initiator.getFixVersion(),
                    initiator.getTargetCompID().getValue(), initiator.getSenderCompID().getValue())) {
                peer.readMessageOfType(MessageTypes.Logon, Duration.ofSeconds(10));
                peer.send(peer.message(MessageTypes.Logon, 1).set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));
                await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());

                fixInitiatorSession.logoutPermanently("never answered");
                peer.readMessageOfType(MessageTypes.Logout, Duration.ofSeconds(10));

                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> fixInitiator.stop(Deadline.unlimited()));
            }
        }
    }

    @Test
    void unknownSessionGetsLogoutBeforeClose() throws Exception {
        startFixAcceptor();

        byte[] logon = RawFixSocketClient.fixMessage("FIX.4.4",
                "35=A", "34=1", "49=UNKNOWN_SENDER", "56=UNKNOWN_TARGET",
                "52=20260620-12:00:00.000", "98=0", "108=30");

        String reply = RawFixSocketClient.exchange(acceptorPort, logon, Duration.ofSeconds(5));

        assertThat(reply)
                .contains(CoreFields.FIELD_SEPARATOR + "35=" + CoreMessageType.LOGOUT + CoreFields.FIELD_SEPARATOR)
                .contains("Unknown fix session FIX.4.4:UNKNOWN_TARGET:UNKNOWN_SENDER");
    }

    @Test
    void testSimpleLogon() {
        connectFixInitiatorAndAcceptor();

        fixInitiatorSession.logon();

        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
    }

    @Test
    void testFixInitiatorStopSendsLogout() {
        logonClient();

        fixInitiator.stop();

        await().untilAsserted(() -> {
            verify(fixInitiatorApplication).onLogout(any(), Mockito.eq("Fix initiator stop"), any());
            verify(fixInitiatorApplication).onDisconnected(any());
        });

        await().untilAsserted(() -> {
            verify(fixAcceptorApplication).onLogout(any(), Mockito.eq("Fix initiator stop"), any());
            verify(fixAcceptorApplication).onDisconnected(any());
        });
    }

    @Test
    void testLogonWithAdvertiseMsgTypeGrpOnLogon() {
        setupInitiatorSessionSettings(s -> s.advertiseMsgTypeGrpOnLogon(true).build());

        setupAcceptorSessionSettings(s -> s.advertiseMsgTypeGrpOnLogon(true).build());

        logonClient();

        assertThat(initiatorLogger.getOutgoingMessages()).anyMatch(s -> {
            assertThat(s).contains("35=" + CoreMessageType.LOGON, "384=2", "372=8", "385=R", "372=D", "385=S");
            return true;
        });

        assertThat(acceptorLogger.getOutgoingMessages()).anyMatch(s -> {
            assertThat(s).contains("35=" + CoreMessageType.LOGON, "384=2", "372=8", "385=S", "372=D", "385=R");
            return true;
        });
    }

    @Test
    void testLogonWithAdvertisedClient() {
        setupInitiatorSessionSettings(s ->
                s.advertiseApplicationOnLogon(true).advertiseEngineOnLogon(true).build());

        setupAcceptorSessionSettings(s ->
                s.advertiseApplicationOnLogon(true).advertiseEngineOnLogon(true).build());

        logonClient();

        assertThat(initiatorLogger.getOutgoingMessages()).anyMatch(s -> {
            assertThat(s).contains("35=" + CoreMessageType.LOGON, "1600=Staffix", "1601=" + FixEngineVersion.getInstance().getVersion(),
                    "1602=Lolaf", "1603=test initiator app", "1604=1.2.3", "1605=test initiator vendor");
            return true;
        });

        assertThat(initiatorLogger.getIncomingMessages()).anyMatch(s -> {
            assertThat(s).contains("35=" + CoreMessageType.LOGON, "1600=Staffix", "1601=" + FixEngineVersion.getInstance().getVersion(),
                    "1602=Lolaf", "1603=test acceptor app", "1604=1.2.3", "1605=test acceptor vendor");
            return true;
        });

        assertThat(acceptorLogger.getOutgoingMessages()).anyMatch(s -> {
            assertThat(s).contains("35=" + CoreMessageType.LOGON, "1600=Staffix", "1601=" + FixEngineVersion.getInstance().getVersion(),
                    "1602=Lolaf", "1603=test acceptor app", "1604=1.2.3", "1605=test acceptor vendor");
            return true;
        });

        assertThat(acceptorLogger.getIncomingMessages()).anyMatch(s -> {
            assertThat(s).contains("35=" + CoreMessageType.LOGON, "1600=Staffix", "1601=" + FixEngineVersion.getInstance().getVersion(),
                    "1602=Lolaf", "1603=test initiator app", "1604=1.2.3", "1605=test initiator vendor");
            return true;
        });
    }

    @Test
    void testLogonAdvertisesMaxMessageSizeWhenConfigured() {
        setupInitiatorSessionSettings(s -> s.validationSettings(
                FixSessionSettings.ValidationSettings.builder().maxMessageSize(4096).build()).build());

        setupAcceptorSessionSettings(s -> s.validationSettings(
                FixSessionSettings.ValidationSettings.builder().maxMessageSize(8192).build()).build());

        logonClient();

        assertThat(initiatorLogger.getOutgoingMessages()).anyMatch(s -> {
            assertThat(s).contains("35=" + CoreMessageType.LOGON, "383=4096");
            return true;
        });

        assertThat(acceptorLogger.getOutgoingMessages()).anyMatch(s -> {
            assertThat(s).contains("35=" + CoreMessageType.LOGON, "383=8192");
            return true;
        });
    }

    @Test
    void testLogout() {
        logonClient();

        fixInitiatorSession.logoutPermanently("test logout");

        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogout(any(FixSession.class), eq("test logout"), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogout(any(FixSession.class), eq("test logout"), any(DecodedFixMessage.class)));

        // the session, not the socket: a permanent logout leaves the desired state at LOGGED_OUT, from which an
        // initiator reconnects and waits there without sending a Logon. Asserting the disconnection instead would be
        // asserting the gap between the two, which lasts one connection retry
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isFalse());
        await().untilAsserted(() -> assertThat(fixAcceptorSession.isLoggedIn()).isFalse());
        await().during(STAYS_PUT).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(fixInitiatorSession.isLoggedIn()).as("logged back in after a permanent logout").isFalse();
            assertThat(fixAcceptorSession.isLoggedIn()).as("acceptor logged back in after a permanent logout").isFalse();
        });
    }

    /**
     * What {@code logoutPermanently} promises, and where it differs from {@link FixSession#disconnect(String)}: the
     * session stays logged out for as long as it is left alone - a reconnected initiator sends no Logon of its own -
     * and {@link FixSession#logon()} is what reopens it.
     */
    @Test
    void aPermanentlyLoggedOutSessionOnlyComesBackOnLogon() {
        logonClient();

        fixInitiatorSession.logoutPermanently("test logout");
        await().during(STAYS_PUT).atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isFalse());

        fixInitiatorSession.logon();

        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());
        await().untilAsserted(() -> assertThat(fixAcceptorSession.isLoggedIn()).isTrue());
    }

    /**
     * The other half of that pair: {@code disconnect} takes the connection down and keeps it down - the initiator is
     * asked before every dialling attempt and answers no while its desired state is DISCONNECTED - until a
     * {@link FixSession#logon()} asks for the session back.
     */
    @Test
    void aDisconnectedSessionStopsDiallingUntilLogon() {
        logonClient();

        fixInitiatorSession.disconnect("test disconnect");

        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isFalse());
        await().during(STAYS_PUT).atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(fixInitiatorSession.isConnected())
                        .as("dialled the peer again while disconnected on purpose").isFalse());

        fixInitiatorSession.logon();

        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());
        await().untilAsserted(() -> assertThat(fixAcceptorSession.isLoggedIn()).isTrue());
    }

    @Test
    void testLogoutFromAcceptor() {
        logonClient();

        fixAcceptor.stop();

        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogout(any(FixSession.class), eq("FIX server stop"), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogout(any(FixSession.class), eq("FIX server stop"), any(DecodedFixMessage.class)));

        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isFalse());
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isFalse());
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isFalse());
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isFalse());
    }

}