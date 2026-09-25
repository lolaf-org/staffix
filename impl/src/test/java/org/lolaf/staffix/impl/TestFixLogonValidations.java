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
import org.lolaf.staffix.api.FixAcceptor;
import org.lolaf.staffix.api.codec.FixFieldsEncoder;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.fix44.fields.EncryptMethod;
import org.lolaf.staffix.fix44.fields.HeartBtInt;
import org.lolaf.staffix.fix44.fields.ResetSeqNumFlag;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.impl.jaas.JaasHelper;
import org.lolaf.staffix.impl.utils.TestingLoginModule;
import org.lolaf.staffix.tests.FixMessageFields;
import org.lolaf.staffix.tests.RawFixSocketClient;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/**
 * Every logon-rejection test here pins itself to a single dialling attempt with
 * {@link AbstractFixTests#rejectedLogonEndsDialling()} and then verifies the rejection callbacks with
 * {@link AbstractFixTests#awaitSingleRejectedLogon(String)}, which checks them exactly
 * and holds the check. Left to itself an initiator re-dials every 100ms after a rejection and is rejected again, so
 * the callback counts never settle: an exactly-once verification is only true for the first ~100ms, which makes it a
 * race against Awaitility's own 100ms poll interval that, once lost, can never be satisfied again - the condition is
 * monotonically unsatisfiable and the test burns its full timeout before failing on a count in the hundreds. Ending
 * the dialling instead of loosening the verification keeps the counts exact, so a reject path that fires its
 * callbacks twice for one attempt still fails the test.
 */
class TestFixLogonValidations extends AbstractFixTests {

    @Test
    void testLogonRejectedWithFailedCompletedFuture() {
        when(fixAcceptorApplication.validateLogon(any(FixSession.class), any(DecodedFixMessage.class), any(Executor.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("test error")));

        connectFixInitiatorAndAcceptor();
        rejectedLogonEndsDialling();

        fixInitiatorSession.logon();

        awaitSingleRejectedLogon("Failed to validate logon request");
    }

    @Test
    void testLogonAcceptWithJaasHelper() {
        Mockito.doAnswer(invocationOnMock ->
                        JaasHelper.jaasLogin(invocationOnMock.getArgument(0, FixSession.class),
                                invocationOnMock.getArgument(1, DecodedFixMessage.class),
                                invocationOnMock.getArgument(2, Executor.class),
                                TestingLoginModule.class, null, "Invalid credentials"))
                .when(fixAcceptorApplication).validateLogon(any(FixSession.class), any(DecodedFixMessage.class), any(Executor.class));

        doAnswer(invocationOnMock -> {
            Supplier<FixFieldsEncoder<?>> bodyAppender = invocationOnMock.getArgument(3, Supplier.class);
            bodyAppender.get().addString(JaasHelper.USERNAME, TestingLoginModule.TEST_USER);
            bodyAppender.get().addString(JaasHelper.PASSWORD, TestingLoginModule.TEST_PASSWORD);
            return null;
        }).when(fixInitiatorApplication).onAdminMessageEncoding(any(), any(), any(), any(), any());

        logonClient();

        assertThat(fixAcceptorSession.getAuthenticatedSubject()).isNotNull();
        assertThat(fixAcceptorSession.getAuthenticatedSubject().getPrincipals()).hasSize(1);
    }

    @Test
    void testLogonRejectWithJaasHelper() {
        Mockito.doAnswer(invocationOnMock ->
                        JaasHelper.jaasLogin(invocationOnMock.getArgument(0, FixSession.class),
                                invocationOnMock.getArgument(1, DecodedFixMessage.class),
                                invocationOnMock.getArgument(2, Executor.class),
                                TestingLoginModule.class, null, "Invalid test credentials"))
                .when(fixAcceptorApplication).validateLogon(any(FixSession.class), any(DecodedFixMessage.class), any(Executor.class));

        connectFixInitiatorAndAcceptor();
        rejectedLogonEndsDialling();

        fixInitiatorSession.logon();

        awaitSingleRejectedLogon("Invalid test credentials");

        assertThat(fixAcceptorSession.getAuthenticatedSubject()).isNull();
    }

    @Test
    void testLogonRejectedWithImmediateCompletedFuture() {
        when(fixAcceptorApplication.validateLogon(any(FixSession.class), any(DecodedFixMessage.class), any(Executor.class)))
                .thenReturn(CompletableFuture.completedFuture(Optional.of("Test logon reject")));

        connectFixInitiatorAndAcceptor();
        rejectedLogonEndsDialling();

        fixInitiatorSession.logon();

        awaitSingleRejectedLogon("Test logon reject");
    }

    @Test
    void testLogonAcceptedWithCompletedFuture() {
        Mockito.doAnswer(invocationOnMock ->
                        CompletableFuture.completedFuture(Optional.empty()))
                .when(fixAcceptorApplication).validateLogon(any(FixSession.class), any(DecodedFixMessage.class), any(Executor.class));

        logonClient();
    }

    @Test
    void testLogonAcceptedWithAsyncCompletedFuture() {
        Mockito.doAnswer(invocationOnMock ->
                        CompletableFuture.supplyAsync(Optional::empty, invocationOnMock.getArgument(2, Executor.class)))
                .when(fixAcceptorApplication).validateLogon(any(FixSession.class), any(DecodedFixMessage.class), any(Executor.class));

        logonClient();
    }

    @Test
    void testLogonAcceptedWithAsyncCompletedFutureAndSimulatedBlockingIO() {
        Mockito.doAnswer(invocationOnMock ->
                        CompletableFuture.supplyAsync(() -> {
                            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
                            return Optional.empty();
                        }, invocationOnMock.getArgument(2, Executor.class)))
                .when(fixAcceptorApplication).validateLogon(any(FixSession.class), any(DecodedFixMessage.class), any(Executor.class));

        logonClient();
    }

    @Test
    void testLogonRejectedWithAsyncCompletedFuture() {
        Mockito.doAnswer(invocationOnMock -> {
            Executor executor = invocationOnMock.getArgument(2, Executor.class);
            return CompletableFuture.supplyAsync(() -> Optional.of("Test logon reject"), executor);
        }).when(fixAcceptorApplication).validateLogon(any(FixSession.class), any(DecodedFixMessage.class), any(Executor.class));

        connectFixInitiatorAndAcceptor();
        rejectedLogonEndsDialling();

        fixInitiatorSession.logon();

        awaitSingleRejectedLogon("Test logon reject");
    }

    @Test
    void testImmediateDisconnectionWhenFixSessionNotSetupToAllowConnections() {
        setupAcceptorSessionSettings(s -> s.desiredSessionState(FixSessionState.DISCONNECTED).build());

        connectFixInitiatorAndAcceptor();

        fixInitiatorSession.logon();

        // an acceptor that refuses the connection outright never settles the initiator: it dials again every
        // connectionRetry and is dropped again, so the disconnection is counted atLeastOnce rather than exactly once -
        // asserting one raced the retry interval and made this test flaky. What the scenario is about is that the
        // connection ends with nothing sent: no Logout, and never a logon
        await().untilAsserted(() -> verify(fixInitiatorApplication, atLeastOnce()).onDisconnected(any()));
        verify(fixInitiatorApplication, never()).onLogout(any(), any(), any());
        verify(fixInitiatorApplication, never()).onLogon(any(), any());

        // and on the acceptor side nothing of the session layer ran on the connection it refused: the Logon that
        // identified the session went no further than identifying it, and the session never took the connection on -
        // so the application is not told about a disconnection from a connection it never had
        verify(fixAcceptorApplication, never()).validateLogon(any(), any(), any());
        verify(fixAcceptorApplication, never()).onLogon(any(), any());
        verify(fixAcceptorApplication, never()).onDisconnected(any());
        assertThat(acceptorMessagesStore.getIncomingSeqNum())
                .as("a Logon that was never processed cannot have counted")
                .isEqualTo(1);

        // a connection the session refuses is a rejected one, and it is reported as such rather than as accepted.
        // Awaited: this is the acceptor telling its listener, and what was awaited above is the initiator seeing the
        // socket close - two sides, so the order between them is not guaranteed
        await().untilAsserted(() -> verify(fixSessionEventsListener, atLeastOnce())
                .onFixSessionRejected(any(), any(FixAcceptor.SessionNotAcceptingConnectionsException.class)));
        verify(fixSessionEventsListener, never()).onFixSessionAccepted(any());
    }

    /**
     * What the sequence number above is about, end to end: a peer whose Logon was refused was told nothing - no
     * Logout, no reject - so it reconnects on the very same MsgSeqNum(34). Counting the refused Logon leaves the
     * acceptor expecting one more than that, and the peer is then logged out for a MsgSeqNum too low it has no way of
     * knowing about.
     */
    @Test
    void testLogonAcceptedOnceAllowedAgainAfterARefusedConnection() throws Exception {
        setupAcceptorSessionSettings(s -> s.desiredSessionState(FixSessionState.DISCONNECTED).build());
        startFixAcceptor();

        FixSessionId initiator = getInitiatorFixSessionSettings().build().getFixSessionId();
        try (RawFixSocketClient.Session peer = RawFixSocketClient.connect(acceptorPort, initiator.getFixVersion(),
                initiator.getSenderCompID().getValue(), initiator.getTargetCompID().getValue(), Duration.ofSeconds(10))) {
            peer.send(peer.message(MessageTypes.Logon, 1).set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));

            assertThat(peer.isClosedByPeer(Duration.ofSeconds(10)))
                    .as("a session not setup to allow connections drops the connection with nothing sent")
                    .isTrue();
        }
        await().untilAsserted(() -> verify(fixSessionEventsListener)
                .onFixSessionRejected(any(), any(FixAcceptor.SessionNotAcceptingConnectionsException.class)));

        fixAcceptorSession.logon();

        try (RawFixSocketClient.Session peer = RawFixSocketClient.connect(acceptorPort, initiator.getFixVersion(),
                initiator.getSenderCompID().getValue(), initiator.getTargetCompID().getValue(), Duration.ofSeconds(10))) {
            peer.send(peer.message(MessageTypes.Logon, 1).set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));

            String response = peer.readMessageOfType(MessageTypes.Logon, Duration.ofSeconds(10));
            assertThat(FixMessageFields.hasFieldWithValue(response, CoreFields.MESSAGE_TYPE, CoreMessageType.LOGON))
                    .as("the logon is acknowledged rather than logged out, the refused connection having left the "
                            + "numbering where the peer thinks it is, but got: %s", response)
                    .isTrue();
            await().untilAsserted(() -> assertThat(fixAcceptorSession.isLoggedIn()).isTrue());
        }
        // awaited, not verified outright: bringSessionUp() flips the session state and calls the application on
        // adjacent lines, so isLoggedIn() above is the earlier of the two signals and a loaded machine can sit
        // between them for longer than it takes this thread to get here
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(), any()));
        await().untilAsserted(() -> verify(fixSessionEventsListener).onFixSessionAccepted(any()));
    }

    @Test
    void testLogonRejectedWhenFixSessionNotSetupToAllowLogins() {
        setupAcceptorSessionSettings(s -> s.desiredSessionState(FixSessionState.LOGGED_OUT).build());

        connectFixInitiatorAndAcceptor();
        rejectedLogonEndsDialling();

        fixInitiatorSession.logon();

        awaitSingleRejectedLogon("Logon rejected, session not setup to accept login requests for now");
    }

    @Test
    void testLogonRejectedWithTestMessageIndicatorSetAndNotEnabledOnAcceptorSide() {
        setupInitiatorSessionSettings(s -> s.testingMode(true).build());

        connectFixInitiatorAndAcceptor();
        rejectedLogonEndsDialling();

        fixInitiatorSession.logon();

        awaitSingleRejectedLogon("Session not configured for accepting login with TestMessageIndicator(464) enabled");
    }

    @Test
    void testLogonRejectedWithTestMessageIndicatorSetOnAcceptorSideAndNotEnabledOnClient() {
        setupAcceptorSessionSettings(s -> s.testingMode(true).build());

        connectFixInitiatorAndAcceptor();
        rejectedLogonEndsDialling();

        fixInitiatorSession.logon();

        awaitSingleRejectedLogon("Session configured for only accepting login with TestMessageIndicator(464) enabled");
    }

    @Test
    void testLogonAttemptByInitiatorOutsideOfSessionTimeAreRejected() {
        LocalDateTime now = LocalDateTime.now();
        setupAcceptorSessionSettings(s -> s.sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                .sessionSchedule(FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(now.getDayOfWeek().plus(1))
                        .endDay(now.getDayOfWeek().plus(1))
                        .startTime(LocalTime.of(0, 0))
                        .endTime(LocalTime.of(23, 59))
                        .build())
                .build()).build());

        connectFixInitiatorAndAcceptor();
        rejectedLogonEndsDialling();

        fixInitiatorSession.logon();

        awaitSingleRejectedLogon("Logon attempt outside of configured session time");
    }

    @Test
    void testLogonRejectedWhenReceivedMaxMessageSizeIsSmallerThanConfiguredOnAcceptorSide() {
        // Initiator advertises it can only receive 2048 bytes; the acceptor requires the peer to be able to receive
        // at least 4096 bytes, so per the FIX session layer spec it terminates the session with a Logout.
        setupInitiatorSessionSettings(s -> s.validationSettings(
                FixSessionSettings.ValidationSettings.builder().maxMessageSize(2048).build()).build());

        setupAcceptorSessionSettings(s -> s.validationSettings(
                FixSessionSettings.ValidationSettings.builder().requiredPeerMaxMessageSize(4096).build()).build());

        connectFixInitiatorAndAcceptor();
        rejectedLogonEndsDialling();

        fixInitiatorSession.logon();

        awaitSingleRejectedLogon("MaxMessageSize(383) = 2048 < required message size 4096");
    }

    /**
     * Section 4.8.1: "the FIX session should be terminated if the incoming message MsgSeqNum(34) is less than
     * NextNumIn and PossDupFlag(43) is not set to Y, except if the message is the SequenceReset(35=4) with the
     * GapFillFlag(123) set to N". A Logon(35=A) is not that exception, and a peer whose numbering went backwards
     * without announcing it - no ResetSeqNumFlag(141)=Y - is indistinguishable from one replaying an old message.
     * <p>
     * This used to answer with a ResendRequest(35=2) instead, over a range computed as
     * {@code [expected .. MsgSeqNum - 1]} and therefore running backwards - asking the peer to replay messages it no
     * longer believes it sent. Driven from a raw socket, a real initiator session having no way to send a Logon at a
     * sequence number of the test's choosing.
     */
    @Test
    void testLogonWithMsgSeqNumTooLowIsLoggedOut() throws Exception {
        // the acceptor closes once its Logout goes unanswered, which the default timeout 10s makes exactly as long as the
        // wait for the close below
        setupAcceptorSessionSettings(s -> s.logInOrOutResponseTimeout(Duration.ofSeconds(1)).build());
        startFixAcceptor();
        // an established session the peer has fallen behind: the acceptor expects 10 next
        acceptorMessagesStore.setCurrentIncomingSeqNum(10);

        FixSessionId initiator = getInitiatorFixSessionSettings().build().getFixSessionId();
        try (RawFixSocketClient.Session peer = RawFixSocketClient.connect(acceptorPort, initiator.getFixVersion(),
                initiator.getSenderCompID().getValue(), initiator.getTargetCompID().getValue(), Duration.ofSeconds(10))) {
            peer.send(peer.message(MessageTypes.Logon, 1).set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));

            assertThat(FixMessageFields.valuesOf(
                    peer.readMessageOfType(MessageTypes.Logout, Duration.ofSeconds(10)), CoreFields.TEXT))
                    .as("a Logon whose MsgSeqNum went backwards unannounced ends the session, section 4.8.1")
                    .containsExactly("MsgSeqNum too low, expecting 10 but received 1");
            assertThat(peer.isClosedByPeer(Duration.ofSeconds(10)))
                    .as("and the connection is dropped rather than left running on numbering neither end agrees about")
                    .isTrue();
            assertThat(acceptorLogger.getOutgoingMessages())
                    .as("nothing may have been asked of the peer: the range would run backwards")
                    .noneMatch(message -> FixMessageFields.hasFieldWithValue(message, CoreFields.MESSAGE_TYPE, CoreMessageType.RESEND_REQUEST));
        }
        verify(fixAcceptorApplication).onPreLogout(any(FixSession.class), startsWith("MsgSeqNum too low"), eq(true));
    }

    /**
     * The announced restart, which is the same Logon with ResetSeqNumFlag(141)=Y and must still be accepted: section
     * 4.4.3 has both ends go back to 1, so its MsgSeqNum(34) being below what was expected is the point of it.
     */
    @Test
    void testLogonWithMsgSeqNumTooLowAndResetSeqNumFlagIsAccepted() throws Exception {
        startFixAcceptor();
        acceptorMessagesStore.setCurrentIncomingSeqNum(10);

        FixSessionId initiator = getInitiatorFixSessionSettings().build().getFixSessionId();
        try (RawFixSocketClient.Session peer = RawFixSocketClient.connect(acceptorPort, initiator.getFixVersion(),
                initiator.getSenderCompID().getValue(), initiator.getTargetCompID().getValue(), Duration.ofSeconds(10))) {
            peer.send(peer.message(MessageTypes.Logon, 1).set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5")
                    .set(ResetSeqNumFlag.get(), "Y"));

            assertThat(FixMessageFields.valuesOf(
                    peer.readMessageOfType(MessageTypes.Logon, Duration.ofSeconds(10)), CoreFields.RESET_NUM_FLAG))
                    .as("the acceptor follows the announced reset and acknowledges it")
                    .containsExactly("Y");
            await().untilAsserted(() -> assertThat(fixAcceptorSession.isLoggedIn()).isTrue());
        }
    }

    /**
     * The other way a Logon below what was expected is legitimate: an acceptor configured to restart the numbering
     * for every logon of its own accord, which is exactly what such a Logon looks like on arrival.
     */
    @Test
    void testLogonWithMsgSeqNumTooLowIsAcceptedWhenResettingOnLogon() throws Exception {
        setupAcceptorSessionSettings(s -> s.resetSeqNumOnLogon(true).build());
        startFixAcceptor();
        acceptorMessagesStore.setCurrentIncomingSeqNum(10);

        FixSessionId initiator = getInitiatorFixSessionSettings().build().getFixSessionId();
        try (RawFixSocketClient.Session peer = RawFixSocketClient.connect(acceptorPort, initiator.getFixVersion(),
                initiator.getSenderCompID().getValue(), initiator.getTargetCompID().getValue(), Duration.ofSeconds(10))) {
            peer.send(peer.message(MessageTypes.Logon, 1).set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));

            assertThat(FixMessageFields.valuesOf(
                    peer.readMessageOfType(MessageTypes.Logon, Duration.ofSeconds(10)), CoreFields.RESET_NUM_FLAG))
                    .as("the acceptor restarts the numbering itself and says so")
                    .containsExactly("Y");
            await().untilAsserted(() -> assertThat(fixAcceptorSession.isLoggedIn()).isTrue());
        }
    }

    @Test
    void testLogonRejectedWhenHeartbeatIntervalIsOutsideAcceptorBounds() {
        setupInitiatorSessionSettings(s -> s.heartBeatInterval(
                FixSessionSettings.HeartbeatInterval.builder()
                        .initiatorInterval(Duration.ofSeconds(5))
                        .build()).build());

        setupAcceptorSessionSettings(s -> s.heartBeatInterval(
                FixSessionSettings.HeartbeatInterval.builder()
                        .acceptorLowerBoundInterval(Duration.ofSeconds(1))
                        .acceptorUpperBoundInterval(Duration.ofSeconds(3))
                        .build()).build());

        connectFixInitiatorAndAcceptor();
        rejectedLogonEndsDialling();

        fixInitiatorSession.logon();

        awaitSingleRejectedLogon("HeartBtInt(108) = 5 is not within accepted bounds [1, 3]");
    }
}