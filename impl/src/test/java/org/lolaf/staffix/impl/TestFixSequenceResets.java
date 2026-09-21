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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.fix44.fields.EmailThreadID;
import org.lolaf.staffix.impl.session.FixSessionImpl;
import org.lolaf.staffix.tests.FixMessageFields;
import org.lolaf.staffix.tests.TestingFixSessionMessagesStore;
import org.lolaf.staffix.tests.TestingLogger;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TestFixSequenceResets extends AbstractFixTests {

    /**
     * Heartbeats are kept out of the way of every test in this class: a Heartbeat(35=0) spends a sequence number, and
     * sequence numbering is what these tests assert. No test here runs for five minutes, so logging on with that
     * interval means none is ever sent.
     * <p>
     * It has to be lifted at both ends. An acceptor refuses a HeartBtInt(108) above its
     * {@code acceptorUpperBoundInterval} - twenty seconds by default - so raising only the initiator's interval gets
     * every Logon answered with "HeartBtInt(108) = 300 is not within accepted bounds [1, 20]" and nothing in the
     * class ever logs on.
     */
    private static final Duration HEARTBEAT_INTERVAL_NO_TEST_REACHES = Duration.ofMinutes(5);
    /**
     * MsgType(35) and SessionRejectReason(373) are matched SOH delimited on both sides so that a search for
     * {@code 35=3} cannot also match {@code 335=3}.
     */
    private static final String SOH = String.valueOf(CoreFields.FIELD_SEPARATOR);

    /**
     * The MsgType(35) of every message a side put on the wire, in order.
     */
    private static List<String> sentMessageTypes(TestingLogger logger) {
        return logger.getOutgoingMessages().stream()
                .flatMap(message -> FixMessageFields.valuesOf(message, CoreFields.MESSAGE_TYPE).stream())
                .collect(Collectors.toList());
    }

    @Override
    FixSessionSettings.FixSessionSettingsBuilder<?, ?> getInitiatorFixSessionSettings() {
        return super.getInitiatorFixSessionSettings().heartBeatInterval(FixSessionSettings.HeartbeatInterval.builder()
                .initiatorInterval(HEARTBEAT_INTERVAL_NO_TEST_REACHES)
                .build());
    }

    @Override
    FixSessionSettings.FixSessionSettingsBuilder<?, ?> getAcceptorFixSessionSettings() {
        return super.getAcceptorFixSessionSettings().heartBeatInterval(FixSessionSettings.HeartbeatInterval.builder()
                .acceptorUpperBoundInterval(HEARTBEAT_INTERVAL_NO_TEST_REACHES)
                .build());
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testSequenceResetOnLogon(ConnectorType connectorType) {
        // the peer is left at the default, following the flag rather than refusing it, which
        // testSequenceResetRefusedWhenSessionDoesNotSupportIt covers
        setupSessionSettings(connectorType, s -> s.resetSeqNumOnLogon(true).build());

        logonClient();

        verify(getFixApplication(connectorType.inverse()))
                .onLogon(any(FixSession.class), Mockito.argThat(messageFieldReceived(CoreFields.RESET_NUM_FLAG, "Y")));
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testSequenceResetRefusedWhenSessionDoesNotSupportIt(ConnectorType connectorType) {
        // Section 4.4.3: "if the peer is not configured to accept resetting of an inbound session the peer should
        // send a Logout(35=5) with Text(58) indicating that resetting the sequence number is not supported and then
        // terminate the transport layer connection". An explicit false is that configuration; null instead leaves the
        // flag to the counterparty, which is what testSequenceResetOnLogon above exercises.
        setupSessionSettings(connectorType, s -> s.resetSeqNumOnLogon(true).build());
        setupSessionSettings(connectorType.inverse(), s -> s.resetSeqNumOnLogon(false).build());

        connectFixInitiatorAndAcceptor();
        fixInitiatorSession.logon();

        await().untilAsserted(() -> verify(getFixApplication(connectorType.inverse()))
                .onLogoutInitiated(any(FixSession.class), eq("Resetting the sequence number is not supported by this session")));
        await().untilAsserted(() -> assertThat(getFixSession(connectorType.inverse()).isLoggedIn()).isFalse());
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testSequenceResetOnLogonWithOutOfOrderSequenceNumber(ConnectorType connectorType) {
        // A Logon carrying ResetSeqNumFlag(141)=Y restarts the numbering at 1, so its own MsgSeqNum(34) belongs to a
        // numbering that is being thrown away: it must be honoured whatever that number is, and must not be taken for
        // a gap to recover. Asking for a retransmission of messages the reset has just made irrelevant would at best
        // waste a round trip and at worst race the reset against a "MsgSeqNum too low" logout.
        // NextExpectedMsgSeqNum(789) off for this one: rewinding what a side expects to receive also rewrites the
        // 789 it advertises, and a peer that has never sent that many messages rightly refuses the Logon (section
        // 4.4.1). What is under test here is the reset, so the two are kept apart.
        setupSessionSettings(connectorType, s -> s.resetSeqNumOnLogon(true)
                .enabledLogonNextExpectedMsgSeqNum(false).build());
        setupSessionSettings(connectorType.inverse(), s -> s.enabledLogonNextExpectedMsgSeqNum(false).build());

        // the peer expects a far higher sequence number than the Logon about to arrive will carry
        getFixMessagesStore(connectorType.inverse()).setCurrentIncomingSeqNum(100);

        logonClient();

        FixApplication resettingSideApplication = getFixApplication(connectorType);
        FixApplication peerApplication = getFixApplication(connectorType.inverse());

        await().untilAsserted(() -> verify(peerApplication)
                .onLogon(any(FixSession.class), Mockito.argThat(messageFieldReceived(CoreFields.RESET_NUM_FLAG, "Y"))));

        // neither end asked for anything back
        verify(peerApplication, never()).onResendRequestInitiated(any(FixSession.class), anyLong(), anyLong());
        verify(resettingSideApplication, never()).onResendRequestInitiated(any(FixSession.class), anyLong(), anyLong());

        // and both restarted at 1, the Logon exchange having spent the first number on each side
        await().untilAsserted(() -> assertThat(getFixMessagesStore(connectorType.inverse()).getIncomingSeqNum()).isEqualTo(2));
        await().untilAsserted(() -> assertThat(getFixMessagesStore(connectorType.inverse()).getOutgoingSeqNum()).isEqualTo(2));
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testSequenceResetWhenSessionEstablishedInPlace(ConnectorType connectorType) {
        // AdminApi.ResetFixSessionMode.RESET_SEQUENCE: both sequence numbers are put back to 1 on the spot, without
        // telling the peer. It is the operator's business to have the other end do the same, which this drives too -
        // the session is only usable afterwards because both ends were reset.
        logonClient();

        exchangeAMessageEachWay(1);

        getFixSessionImpl(connectorType).adminResetSequence(AdminApi.ResetFixSessionMode.RESET_SEQUENCE);
        getFixSessionImpl(connectorType.inverse()).adminResetSequence(AdminApi.ResetFixSessionMode.RESET_SEQUENCE);

        // each reset runs on its own session's IO thread, so a message sent before both have landed is seen as too low
        for (ConnectorType side : List.of(connectorType, connectorType.inverse())) {
            await().untilAsserted(() -> assertThat(getFixMessagesStore(side).getOutgoingSeqNum()).isEqualTo(1));
            await().untilAsserted(() -> assertThat(getFixMessagesStore(side).getIncomingSeqNum()).isEqualTo(1));
        }

        // and the session keeps working, numbering from 1 again
        exchangeAMessageEachWay(2);
    }

    /**
     * The same reset with the session down, which is how an operator normally uses it - a session is taken out,
     * renumbered, and let back in. It only works because a session's message store is open for as long as the
     * session exists rather than only while it holds a connection.
     */
    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testSequenceResetWhileSessionIsDisconnected(ConnectorType connectorType) {
        logonClient();
        exchangeAMessageEachWay(1);

        fixInitiatorSession.disconnect("taken out for renumbering");
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isFalse());
        await().untilAsserted(() -> assertThat(fixAcceptorSession.isConnected()).isFalse());

        getFixSessionImpl(connectorType).adminResetSequence(AdminApi.ResetFixSessionMode.RESET_SEQUENCE);
        getFixSessionImpl(connectorType.inverse()).adminResetSequence(AdminApi.ResetFixSessionMode.RESET_SEQUENCE);

        // an admin operation runs on the session's owner, which for a disconnected session is the engine's
        // executor, so the renumbering lands just after the call rather than within it
        await().untilAsserted(() -> assertThat(getFixMessagesStore(connectorType).getOutgoingSeqNum()).isEqualTo(1));
        await().untilAsserted(() -> assertThat(getFixMessagesStore(connectorType).getIncomingSeqNum()).isEqualTo(1));

        fixInitiatorSession.logon();
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());

        // the renumbering held across the reconnection, and the session runs on it
        exchangeAMessageEachWay(2);
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testSequenceResetOverActiveSession(ConnectorType connectorType) {
        // AdminApi.ResetFixSessionMode.RESET_SEQUENCE_IN_SESSION, section 4.4.2: "the FIX session may be reset to
        // NextNumIn=1 and NextNumOut=1 over an active FIX session". The end starting it puts both its numbers back to
        // 1 and sends a Logon(35=A) carrying ResetSeqNumFlag(141)=Y, the peer answers with one of its own, and both
        // finish at 2. Either end may be the one to start it, which is why this runs both ways round.
        //
        // Keeping the connection is the whole point of 4.4.2 and what separates it from LOGOUT_LOGON_REST_NUM_FLAG,
        // which reaches the same numbers by dropping it. That half is asserted on the wire rather than on session
        // state: a logout and an immediate re-logon would leave isLoggedIn() reading true either side of a poll and
        // prove nothing.
        //
        // The schedule reaches this same reset on a timer instead of through the admin API - see
        // TestFixSessionsSchedule, which covers that path and the per-day settings behind it.
        logonClient();

        exchangeAMessageEachWay(1);
        TestingLogger resettingSideLogger = connectorType.select(initiatorLogger, acceptorLogger);
        TestingLogger peerLogger = connectorType.inverse().select(initiatorLogger, acceptorLogger);
        resettingSideLogger.clear();
        peerLogger.clear();

        getFixSessionImpl(connectorType).adminResetSequence(AdminApi.ResetFixSessionMode.RESET_SEQUENCE_IN_SESSION);

        // "upon completion of the session reset, both peers must have NextNumIn = 2 and NextNumOut = 2"
        await().untilAsserted(() -> assertThat(getFixMessagesStore(connectorType).getOutgoingSeqNum()).isEqualTo(2));
        await().untilAsserted(() -> assertThat(getFixMessagesStore(connectorType).getIncomingSeqNum()).isEqualTo(2));
        await().untilAsserted(() -> assertThat(getFixMessagesStore(connectorType.inverse()).getOutgoingSeqNum()).isEqualTo(2));
        await().untilAsserted(() -> assertThat(getFixMessagesStore(connectorType.inverse()).getIncomingSeqNum()).isEqualTo(2));

        // the two Logons of the exchange, each announcing the reset
        assertThatFixMessage(String.join(String.valueOf(CoreFields.FIELD_SEPARATOR), resettingSideLogger.getOutgoingMessages()))
                .as("the end starting the reset must announce it with ResetSeqNumFlag(141)=Y")
                .containsFieldWithValue(CoreFields.RESET_NUM_FLAG, "Y");
        assertThatFixMessage(String.join(String.valueOf(CoreFields.FIELD_SEPARATOR), peerLogger.getOutgoingMessages()))
                .as("the peer must acknowledge with a Logon carrying ResetSeqNumFlag(141)=Y")
                .containsFieldWithValue(CoreFields.RESET_NUM_FLAG, "Y");

        // and neither end logged out to get there
        assertThat(sentMessageTypes(resettingSideLogger))
                .as("the connection must survive the reset, so the end starting it may not have sent a Logout")
                .doesNotContain(CoreMessageType.LOGOUT);
        assertThat(sentMessageTypes(peerLogger))
                .as("the connection must survive the reset, so the peer may not have sent a Logout")
                .doesNotContain(CoreMessageType.LOGOUT);

        assertThat(getFixSession(connectorType).isLoggedIn()).isTrue();

        // and the session carries on from the new numbering, which is what says the two ends agree on it
        exchangeAMessageEachWay(2);
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testSequenceResetOverActiveSessionRefusedWhenNotSupported(ConnectorType connectorType) {
        // Section 4.4.2's last line: "if the peer is not configured to accept resetting of an inbound session the peer
        // should send a Logout(35=5) with Text(58) indicating that resetting the sequence number is not supported and
        // then terminate the transport layer connection". An explicit false is that configuration.
        setupSessionSettings(connectorType.inverse(), s -> s.resetSeqNumOnLogon(false).build());
        logonClient();

        exchangeAMessageEachWay(1);

        getFixSessionImpl(connectorType).adminResetSequence(AdminApi.ResetFixSessionMode.RESET_SEQUENCE_IN_SESSION);

        await().untilAsserted(() -> verify(getFixApplication(connectorType.inverse()))
                .onLogoutInitiated(any(FixSession.class), startsWith("Resetting the sequence number is not supported")));
    }

    @Test
    void testSequenceResetWhenSessionEstablishedThroughLogoutAndLogon() {
        // AdminApi.ResetFixSessionMode.LOGOUT_LOGON_REST_NUM_FLAG: the session is cycled and comes back with
        // ResetSeqNumFlag(141)=Y, which is the section 4.4.2 flow and tells the peer to reset with us. Only an
        // initiator can drive it, being the end that sends the Logon.
        //
        // Neither end sets resetSeqNumOnLogon here, on purpose: with it the reconnection resets on its own and the
        // mode is credited with something it did not do. Arranging that the reset rides on the Logon that follows the
        // logout is the mode's own job, so the session settings must have no opinion on the flag.
        logonClient();

        exchangeAMessageEachWay(3);

        setupOrResetFixInitiatorApplication();
        setupOrResetFixAcceptorApplication();
        initiatorLogger.clear();
        acceptorLogger.clear();

        getFixSessionImpl(ConnectorType.INITIATOR).adminResetSequence(AdminApi.ResetFixSessionMode.LOGOUT_LOGON_REST_NUM_FLAG);

        // the peer sees the reset announced on the Logon that brings the session back
        await().untilAsserted(() -> verify(fixAcceptorApplication)
                .onLogon(any(FixSession.class), Mockito.argThat(messageFieldReceived(CoreFields.RESET_NUM_FLAG, "Y"))));
        // section 4.4.2: "upon completion of the session reset, both peers must have NextNumIn = 2 and NextNumOut = 2"
        await().untilAsserted(() -> assertThat(acceptorMessagesStore.getIncomingSeqNum()).isEqualTo(2));
        await().untilAsserted(() -> assertThat(acceptorMessagesStore.getOutgoingSeqNum()).isEqualTo(2));

        // the order is what the mode is: the Logout first, and the Logon announcing the reset only once the session
        // has been cycled. A Logon sent while the session is still logged in is refused where it stands, and numbers
        // rewound before the peer has acknowledged the Logout leave that acknowledgement arriving against a numbering
        // the peer has never heard of.
        assertThat(sentMessageTypes(initiatorLogger))
                .as("the Logout must go out before the Logon that announces the reset")
                .containsSubsequence(CoreMessageType.LOGOUT, CoreMessageType.LOGON);

        // and the peer went through the cycle without ever taking the numbering for a gap or for a rewind: either one
        // would show up as a recovery it asked for, or as a Logout it decided to send
        verify(fixAcceptorApplication, never()).onResendRequestInitiated(any(FixSession.class), anyLong(), anyLong());
        verify(fixAcceptorApplication, never()).onLogoutInitiated(any(FixSession.class), any());

        exchangeAMessageEachWay(4);
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testNoSequenceResetWhenNeitherEndManagesResetsOnLogon(ConnectorType connectorType) {
        // Neither end has an opinion: this session leaves ResetSeqNumFlag(141) to the counterparty and the
        // counterparty sends none. 141 is an optional field, so a Logon without it means no reset, and the session
        // carries on from the sequence numbers it already has rather than being turned away.
        // both ends at the default, which is exactly "the counterparty manages the flag"
        logonClient();

        // logged on, with no reset announced in either direction. A Logon acknowledgement carries an explicit
        // ResetSeqNumFlag(141)=N rather than leaving the field out, so what is asserted is that neither end was told
        // to reset, not that the field is absent.
        verify(getFixApplication(connectorType.inverse()))
                .onLogon(any(FixSession.class), any(DecodedFixMessage.class));
        verify(getFixApplication(connectorType.inverse()), never())
                .onLogon(any(FixSession.class), Mockito.argThat(messageFieldReceived(CoreFields.RESET_NUM_FLAG, "Y")));
        verify(getFixApplication(connectorType), never()).onLogoutInitiated(any(FixSession.class), any());

        // and it keeps working from where it was
        fixInitiatorSession.send(encodeTestMessage(1), null);
        assertMessageReceived(decodedAcceptorMessages, EmailThreadID.get(), "test thread id 1");
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testHardSequenceResetJumpsTheExpectedSequenceNumber(ConnectorType connectorType) {
        // Section 4.8.6: a SequenceReset(35=4) with GapFillFlag(123) other than Y forces the peer's expected incoming
        // sequence number to NewSeqNo(36). Unlike a gap fill it is not a way of answering a ResendRequest(35=2): the
        // skipped numbers are abandoned, not accounted for, so nothing may be asked for or put back on the wire.
        logonClient();

        exchangeAMessageEachWay(1);
        clearApplicationsInvocations();

        long newSeqNo = getFixMessagesStore(connectorType.inverse()).getIncomingSeqNum() + 50;
        sendHardSequenceReset(connectorType, newSeqNo);

        await().untilAsserted(() -> assertThat(getFixMessagesStore(connectorType.inverse()).getIncomingSeqNum())
                .as("a hard reset forces the expected incoming sequence number to NewSeqNo(36)")
                .isEqualTo(newSeqNo));

        // the fifty numbers jumped over are gone rather than missing: neither end tries to recover them
        verify(getFixApplication(connectorType.inverse()), never())
                .onResendRequestInitiated(any(FixSession.class), anyLong(), anyLong());
        verify(getFixApplication(connectorType), never())
                .onResendRequest(any(FixSession.class), any(), any(DecodedFixMessage.class));

        // and the session runs on from the new numbering
        exchangeAMessageEachWay(2);
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testHardSequenceResetIsAppliedWhateverItsOwnSequenceNumber(ConnectorType connectorType) {
        // Section 4.8.6 again, and the rule that makes this message peculiar: a hard reset is applied "without regard
        // to its own MsgSeqNum(34)". Arriving with a number far beyond what the peer expects, it must still be
        // processed rather than queued as the far end of a gap and answered with a ResendRequest(35=2).
        logonClient();

        exchangeAMessageEachWay(1);
        clearApplicationsInvocations();

        // the reset is made to carry a MsgSeqNum hundreds ahead of what the peer expects next
        TestingFixSessionMessagesStore senderStore = getFixMessagesStore(connectorType);
        senderStore.setCurrentOutgoingSeqNum(senderStore.getOutgoingSeqNum() + 500);

        long newSeqNo = getFixMessagesStore(connectorType.inverse()).getIncomingSeqNum() + 10;
        sendHardSequenceReset(connectorType, newSeqNo);

        await().untilAsserted(() -> assertThat(getFixMessagesStore(connectorType.inverse()).getIncomingSeqNum())
                .as("a hard reset is applied without regard to its own MsgSeqNum(34)")
                .isEqualTo(newSeqNo));
        verify(getFixApplication(connectorType.inverse()), never())
                .onResendRequestInitiated(any(FixSession.class), anyLong(), anyLong());

        exchangeAMessageEachWay(2);
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testHardSequenceResetLoweringTheSequenceIsRejected(ConnectorType connectorType) {
        // Section 4.8.6: a hard reset may only move the sequence forward. One that would lower it is answered with a
        // Reject(35=3) carrying SessionRejectReason(373)=5, and - the part worth pinning - the expected sequence
        // number is left exactly where it was rather than being half applied.
        logonClient();

        exchangeAMessageEachWay(1);
        clearApplicationsInvocations();
        getLogger(connectorType.inverse()).clear();

        TestingFixSessionMessagesStore peerStore = getFixMessagesStore(connectorType.inverse());
        long expectedIncomingSeqNum = peerStore.getIncomingSeqNum();

        sendHardSequenceReset(connectorType, expectedIncomingSeqNum - 1);

        await().untilAsserted(() -> assertThat(getLogger(connectorType.inverse()).getOutgoingMessages())
                .as("a hard reset that would lower the sequence number is answered with a Reject(35=3) naming "
                        + "SessionRejectReason(373)=5")
                .anyMatch(message -> message.contains(SOH + "35=3" + SOH)
                        && message.contains(SOH + "373=5" + SOH)));

        assertThat(peerStore.getIncomingSeqNum())
                .as("the refused reset must leave the expected incoming sequence number untouched")
                .isEqualTo(expectedIncomingSeqNum);
        assertThat(getFixSession(connectorType.inverse()).isLoggedIn())
                .as("a refused reset is a Reject, not a reason to end the session")
                .isTrue();

        // and the session is still usable. Shown the peer's way round on purpose: this side followed an announcement
        // the peer refused, so its own numbering is now ahead of what the peer will accept, while the peer's is
        // exactly where it was. What survives a refused reset is the peer's sequence, and that is what is asserted.
        getFixSession(connectorType.inverse()).send(encodeTestMessage(102), null);
        assertMessageReceived(getDecodedFixMessages(connectorType), EmailThreadID.get(), "test thread id 102");
    }

    /**
     * Sends a hard SequenceReset(35=4) from {@code from} and waits until that side has taken the number it announced,
     * which is what {@link FixSessionImpl#hardSequenceReset} does from the send callback.
     */
    private void sendHardSequenceReset(ConnectorType from, long newSeqNo) {
        getFixSessionImpl(from).hardSequenceReset(newSeqNo);
        await().untilAsserted(() -> assertThat(getFixMessagesStore(from).getOutgoingSeqNum()).isEqualTo(newSeqNo));
    }

    /**
     * Sends one message each way and waits for both to land, so that a reset is shown to leave a working session
     * rather than merely the right numbers in the stores.
     */
    private void exchangeAMessageEachWay(int messageIndex) {
        fixInitiatorSession.send(encodeTestMessage(messageIndex), null);
        fixAcceptorSession.send(encodeTestMessage(100 + messageIndex), null);
        assertMessageReceived(decodedAcceptorMessages, EmailThreadID.get(), "test thread id " + messageIndex);
        assertMessageReceived(decodedInitiatorMessages, EmailThreadID.get(), "test thread id " + (100 + messageIndex));
    }

}
