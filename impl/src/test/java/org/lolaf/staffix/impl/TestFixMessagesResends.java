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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.ResendRequestRange;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.fix44.encoders.TradingSessionStatusRequestEncoder;
import org.lolaf.staffix.fix44.fields.*;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.FixMessageFields;
import org.lolaf.staffix.tests.RawFixSocketClient;
import org.lolaf.staffix.tests.TestingLogger;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class TestFixMessagesResends extends AbstractFixTests {

    /**
     * How long a retransmission is held up while the peer is given a chance to get a message through, in
     * testResendRequestDoesNotBlockIncomingMessagesWhileRetransmitting. Long enough for a healthy engine to read one
     * message, short enough that a regression fails rather than stalls the build.
     */
    private static final int REPLAY_HOLD_TIMEOUT_SECONDS = 5;

    /**
     * MsgType(35)=2, ResendRequest, SOH delimited on both sides so that it matches neither a longer MsgType nor
     * the inside of another field's value.
     */
    private static final String RESEND_REQUEST_MSG_TYPE_FIELD = CoreFields.FIELD_SEPARATOR + "35=2" + CoreFields.FIELD_SEPARATOR;

    /**
     * How many messages each test hands to the session, and after which of them the connection is taken down. The
     * messages sent before the cut are delivered normally; the ones after it consume their MsgSeqNum(34) and go to
     * the store without reaching the peer, which is the gap the peer then asks to have retransmitted.
     */
    private static final int MESSAGES_TO_SEND = 20;
    private static final int LAST_DELIVERED_MESSAGE_INDEX = 10;

    /**
     * MsgSeqNum(34) of the first message the peer misses, captured from the store the moment the connection is cut.
     * Deriving it beats hardcoding: the exact number depends on how many session level messages - the Logout of the
     * shutdown, possibly a Heartbeat - happen to have been sent by then.
     */
    private long firstMissedSeqNum;
    /**
     * MsgSeqNum(34) of the last message the peer misses, i.e. the last one sent while the connection was down.
     */
    private long lastMissedSeqNum;

    static Stream<Arguments> initiatorOrAcceptorWithLastMessageFilteredParams() {
        return Stream.of(Arguments.of(ConnectorType.INITIATOR, true),
                Arguments.of(ConnectorType.INITIATOR, false),
                Arguments.of(ConnectorType.ACCEPTOR, true),
                Arguments.of(ConnectorType.ACCEPTOR, false));
    }

    private static boolean awaitLatch(CountDownLatch latch) {
        try {
            return latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    FixSessionSettings.FixSessionSettingsBuilder<?, ?> getAcceptorFixSessionSettings() {
        return super.getAcceptorFixSessionSettings().enabledLogonNextExpectedMsgSeqNum(false);
    }

    @Override
    FixSessionSettings.FixSessionSettingsBuilder<?, ?> getInitiatorFixSessionSettings() {
        return super.getInitiatorFixSessionSettings().enabledLogonNextExpectedMsgSeqNum(false);
    }

    @Override
    List<MessageType> getDecoders(ConnectorType connectorType) {
        return List.of(testMessageType, MessageTypes.TradingSessionStatusRequest);
    }

    /**
     * How many messages the peer misses, i.e. how many the resend has to account for.
     */
    private int missedMessagesCount() {
        return (int) (lastMissedSeqNum - firstMissedSeqNum + 1);
    }

    /**
     * Sends {@link #MESSAGES_TO_SEND} messages, taking the connection of {@code connectorToCut} down right after the
     * one at {@link #LAST_DELIVERED_MESSAGE_INDEX} so that the rest never reach the peer, and records the
     * MsgSeqNum(34) range that went missing in {@link #firstMissedSeqNum} / {@link #lastMissedSeqNum}.
     *
     * <p>The <em>connection</em> goes, not the connector: the session stays alive and keeps numbering and storing
     * what it is given, which is what builds the backlog the peer later asks for. Stopping the connector would end
     * the session altogether - its store released - and everything sent afterwards would be refused.
     *
     * @param messageSender sends the message for a given index, so that a test can mix message types
     */
    private void sendMessagesAndCutConnection(ConnectorType connectorToCut, IntConsumer messageSender) {
        FixSession sessionToCut = getFixSession(connectorToCut);
        for (int i = 0; i < MESSAGES_TO_SEND; i++) {
            messageSender.accept(i);
            if (i == LAST_DELIVERED_MESSAGE_INDEX) {
                cutConnection(connectorToCut);
                firstMissedSeqNum = getFixMessagesStore(connectorToCut).getOutgoingSeqNum();
            }
        }
        lastMissedSeqNum = getFixMessagesStore(connectorToCut).getOutgoingSeqNum() - 1;
        assertThat(missedMessagesCount())
                .as("the messages sent while the connection was down must all have gone missing")
                .isEqualTo(MESSAGES_TO_SEND - LAST_DELIVERED_MESSAGE_INDEX - 1);
        assertThat(sessionToCut.isConnected()).isFalse();
    }

    /**
     * Takes the connection down and leaves it down.
     *
     * <p>Both ends are put in a desired state of DISCONNECTED, not only the one being cut: an initiator left wanting
     * to be logged in dials again within its retry interval, and every attempt an acceptor turns away costs the
     * acceptor a MsgSeqNum(34) for the Logout it answers with - so the gap a test is trying to pin down would grow
     * on its own while the test looked away.
     */
    private void cutConnection(ConnectorType connectorType) {
        getFixSession(connectorType).disconnect("cutting the connection");
        await().untilAsserted(() -> {
            assertThat(fixInitiatorSession.isConnected()).isFalse();
            assertThat(fixAcceptorSession.isConnected()).isFalse();
        });
        // and only once the link is down, so that nothing still in flight is dropped by a second teardown: the peer
        // is put in the same desired state, which is what keeps it from dialling straight back in
        getFixSession(connectorType.inverse()).disconnect("cutting the connection");
    }

    /**
     * Brings the connection back up and drives the logon that makes the peer discover the gap: the acceptor stops
     * turning connections away, then the initiator dials.
     */
    private void reconnect(ConnectorType connectorType) {
        fixAcceptorSession.logon();
        fixInitiatorSession.logon();
    }

    private void awaitResendRequestCompleted(FixApplication resendRequestReceiver) {
        await().untilAsserted(() -> verify(resendRequestReceiver)
                .onResendRequestInitiated(any(FixSession.class), eq(firstMissedSeqNum), eq(lastMissedSeqNum)));
        await().untilAsserted(() -> verify(resendRequestReceiver)
                .onResendRequestTerminated(any(FixSession.class), eq(firstMissedSeqNum), eq(lastMissedSeqNum)));
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testLogonWithSequenceResetFlag(ConnectorType connectorType) {
        // Section 4.4.3: a Logon(35=A) carrying ResetSeqNumFlag(141)=Y restarts both sequences at 1. A side coming
        // back with a backlog of unsent messages and resetting rather than recovering therefore abandons that
        // backlog: the gap it would otherwise have asked for stops existing, so no retransmission may take place.
        // the other end is left at the default, "the counterparty manages the sequence reset flag on session
        // establishment", which is what makes it honour the ResetSeqNumFlag(141)=Y it is about to be sent
        setupSessionSettings(connectorType, s -> s.resetSeqNumOnLogon(true).build());

        logonClient();

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixSession targetFixSession = getFixSession(connectorType);

        sendMessagesAndCutConnection(connectorType, i -> targetFixSession.send(encodeTestMessage(i), null));

        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 0");
        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 10");

        clearApplicationsInvocations();
        initiatorLogger.clear();
        acceptorLogger.clear();

        when(resendRequestSender.onResendRequest(any(), any(), any())).thenReturn(true);

        reconnect(connectorType);

        // the peer is told of the reset. Only the peer: an initiator announces it on its own Logon and the acceptor
        // echoes it back, but an acceptor resetting on its own has nothing to echo - the Logon it answers carries no
        // flag at all - so its acknowledgement is the only message of the exchange bearing one
        await().untilAsserted(() -> verify(resendRequestReceiver)
                .onLogon(any(FixSession.class), argThat(messageFieldReceived(CoreFields.RESET_NUM_FLAG, "Y"))));

        // the gap was wiped rather than recovered: nothing is asked for and nothing is put back on the wire
        assertNoResendRequestSent();
        verify(resendRequestReceiver, never()).onResendRequestInitiated(any(FixSession.class), anyLong(), anyLong());
        verify(resendRequestSender, never()).onResendRequest(any(FixSession.class), any(), any(DecodedFixMessage.class));
        assertNoMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 11");
        assertNoMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 19");

        // both sequences restarted at 1, the Logon exchange having consumed the first of them on each side
        await().untilAsserted(() -> assertThat(getFixMessagesStore(connectorType).getOutgoingSeqNum()).isEqualTo(2));
        await().untilAsserted(() -> assertThat(getFixMessagesStore(connectorType).getIncomingSeqNum()).isEqualTo(2));

        assertMessagesSendReceiveStillWorkAfterResync();
    }

    @Test
    void testLogoutWhenNextExpectedMsgSeqNumOnLogonIsTooBig() {
        // NextExpectedMsgSeqNum(789) has to be enabled on both ends: the initiator to send it, and the acceptor to
        // act on it. Enabling it only on the sender leaves the receiver ignoring the field and logging on normally.
        setupInitiatorSessionSettings(s -> s.enabledLogonNextExpectedMsgSeqNum(true).build());
        setupAcceptorSessionSettings(s -> s.enabledLogonNextExpectedMsgSeqNum(true).build());

        initiatorMessagesStore.setCurrentIncomingSeqNum(100);

        connectFixInitiatorAndAcceptor();

        fixInitiatorSession.logon();

        // section 4.4.1: a NextExpectedMsgSeqNum(789) beyond what we have ever sent means the peer's view of the
        // session is broken, and the session is ended rather than recovered
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogoutInitiated(any(FixSession.class),
                eq("NextExpectedMsgSeqNum is higher than expected: expected 1, received 100")));
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testResendRequestWithoutGapsWithEnabledNextExpectedMsgSeqNumOnLogon(ConnectorType connectorType) {
        // both ends, since 789 synchronization is a round trip: each side advertises what it expects next on its own
        // Logon, and each side acts on what the other advertised. Enabling it on one end only falls back to the
        // ResendRequest path, which is what the sibling tests above cover.
        setupInitiatorSessionSettings(s -> s.enabledLogonNextExpectedMsgSeqNum(true).build());
        setupAcceptorSessionSettings(s -> s.enabledLogonNextExpectedMsgSeqNum(true).build());

        logonClient();

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixSession targetFixSession = getFixSession(connectorType);
        FixMessageDecoder targetDecoder = getDecoder(testMessageType, connectorType.inverse());

        sendMessagesAndCutConnection(connectorType, i -> targetFixSession.send(encodeTestMessage(i), null));

        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 0");
        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 10");

        clearApplicationsInvocations();
        initiatorLogger.clear();
        acceptorLogger.clear();

        when(resendRequestSender.onResendRequest(any(), any(), any())).thenReturn(true);

        reconnect(connectorType);

        // the peer never asks: it advertises what it expects next on its Logon and the retransmission follows from
        // that alone. The range is still reported to the application, so the recovery is observable as usual.
        awaitResendRequestCompleted(resendRequestReceiver);
        await().untilAsserted(() -> verify(resendRequestSender, times(missedMessagesCount()))
                .onResendRequest(any(FixSession.class), eq(testMessageType), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(resendRequestSender, never()).onSequenceReset(any(FixSession.class), anyLong(), eq(true)));
        await().untilAsserted(() -> verify(fixAcceptorApplication).validateLogon(any(FixSession.class), any(DecodedFixMessage.class), any(Executor.class)));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(targetDecoder, times(missedMessagesCount())).onDecoded(any(FixSession.class), eq(true), eq(false)));

        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 11");
        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 19");

        // section 4.4.1: "peers should not generate a ResendRequest(35=2) message based on MsgSeqNum(34) of the
        // incoming Logon(35=A) message but should expect any gaps to be filled automatically". This is what tells
        // this test apart from its siblings, which recover the very same gap through a ResendRequest.
        assertNoResendRequestSent();

        assertMessagesSendReceiveStillWorkAfterResync();
    }

    /**
     * Asserts neither side put a ResendRequest(35=2) on the wire since the loggers were last cleared.
     */
    private void assertNoResendRequestSent() {
        assertThat(initiatorLogger.getOutgoingMessages())
                .as("the initiator must not send a ResendRequest when synchronizing with NextExpectedMsgSeqNum(789)")
                .noneMatch(message -> message.contains(RESEND_REQUEST_MSG_TYPE_FIELD));
        assertThat(acceptorLogger.getOutgoingMessages())
                .as("the acceptor must not send a ResendRequest when synchronizing with NextExpectedMsgSeqNum(789)")
                .noneMatch(message -> message.contains(RESEND_REQUEST_MSG_TYPE_FIELD));
    }

    @Test
    void testResendRequestForBothInitiatorAndAcceptor() {
        // Figure 5 of section 4.3.12: both peers come back with a gap of their own, so each answers the other's
        // ResendRequest(35=2) while still awaiting the response to the one it sent. Taking the initiator down leaves
        // both ends in that state at once - neither has anywhere to send - so both keep consuming MsgSeqNum(34) into
        // their stores and both discover a gap when the connection comes back.
        logonClient();

        when(fixInitiatorApplication.onResendRequest(any(), any(), any())).thenReturn(true);
        when(fixAcceptorApplication.onResendRequest(any(), any(), any())).thenReturn(true);

        for (int i = 0; i <= LAST_DELIVERED_MESSAGE_INDEX; i++) {
            fixInitiatorSession.send(encodeTestMessage(i), null);
            fixAcceptorSession.send(encodeTestMessage(100 + i), null);
        }
        assertMessageReceived(decodedAcceptorMessages, EmailThreadID.get(), "test thread id " + LAST_DELIVERED_MESSAGE_INDEX);
        assertMessageReceived(decodedInitiatorMessages, EmailThreadID.get(), "test thread id " + (100 + LAST_DELIVERED_MESSAGE_INDEX));

        cutConnection(ConnectorType.INITIATOR);
        long initiatorFirstMissedSeqNum = initiatorMessagesStore.getOutgoingSeqNum();
        long acceptorFirstMissedSeqNum = acceptorMessagesStore.getOutgoingSeqNum();

        // neither side can deliver anything now, so both build a backlog of their own
        for (int i = LAST_DELIVERED_MESSAGE_INDEX + 1; i < MESSAGES_TO_SEND; i++) {
            fixInitiatorSession.send(encodeTestMessage(i), null);
            fixAcceptorSession.send(encodeTestMessage(100 + i), null);
        }
        long initiatorLastMissedSeqNum = initiatorMessagesStore.getOutgoingSeqNum() - 1;
        long acceptorLastMissedSeqNum = acceptorMessagesStore.getOutgoingSeqNum() - 1;

        clearApplicationsInvocations();

        reconnect(ConnectorType.INITIATOR);

        // each side asks the other for what it missed, and each answers while its own request is still outstanding
        await().untilAsserted(() -> verify(fixAcceptorApplication).onResendRequestInitiated(any(FixSession.class),
                eq(initiatorFirstMissedSeqNum), eq(initiatorLastMissedSeqNum)));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onResendRequestInitiated(any(FixSession.class),
                eq(acceptorFirstMissedSeqNum), eq(acceptorLastMissedSeqNum)));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onResendRequestTerminated(any(FixSession.class),
                eq(initiatorFirstMissedSeqNum), eq(initiatorLastMissedSeqNum)));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onResendRequestTerminated(any(FixSession.class),
                eq(acceptorFirstMissedSeqNum), eq(acceptorLastMissedSeqNum)));

        // and both backlogs come back, in both directions
        assertPossDupMessageReceived(decodedAcceptorMessages, EmailThreadID.get(), "test thread id " + (LAST_DELIVERED_MESSAGE_INDEX + 1));
        assertPossDupMessageReceived(decodedAcceptorMessages, EmailThreadID.get(), "test thread id " + (MESSAGES_TO_SEND - 1));
        assertPossDupMessageReceived(decodedInitiatorMessages, EmailThreadID.get(), "test thread id " + (100 + LAST_DELIVERED_MESSAGE_INDEX + 1));
        assertPossDupMessageReceived(decodedInitiatorMessages, EmailThreadID.get(), "test thread id " + (100 + MESSAGES_TO_SEND - 1));

        assertMessagesSendReceiveStillWorkAfterResync();
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testResendRequestWithLargeGapFill(ConnectorType connectorType) {
        logonClient();

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixSession targetFixSession = getFixSession(connectorType);
        FixMessageDecoder targetDecoder = getDecoder(testMessageType, connectorType.inverse());

        sendMessagesAndCutConnection(connectorType, i -> targetFixSession.send(encodeTestMessage(i), null));

        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 0");
        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 10");

        clearApplicationsInvocations();

        // three consecutive messages the application refuses to retransmit, which the resender has to skip with a
        // single SequenceReset gap fill rather than three
        when(resendRequestSender.onResendRequest(any(FixSession.class), eq(testMessageType), any(DecodedFixMessage.class)))
                .thenAnswer(invocationOnMock -> {
                    DecodedFixMessage d = invocationOnMock.getArgument(2);
                    String msgContent = d.toString();
                    return !msgContent.contains("test thread id 13")
                            && !msgContent.contains("test thread id 14")
                            && !msgContent.contains("test thread id 15");
                });

        reconnect(connectorType);

        awaitResendRequestCompleted(resendRequestReceiver);
        await().untilAsserted(() -> verify(resendRequestSender, times(1)).onSequenceReset(any(FixSession.class), anyLong(), eq(true)));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(targetDecoder, times(missedMessagesCount() - 3)).onDecoded(any(FixSession.class), eq(true), eq(false)));

        assertNoMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 13");
        assertNoMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 14");
        assertNoMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 15");

        assertMessagesSendReceiveStillWorkAfterResync();
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorWithLastMessageFilteredParams")
    void testResendRequestWithFilteredResendsByApplication(ConnectorType connectorType, boolean lastMessageIsAlsoFiltered) {
        assertFilteredResendsAreGapFilled(connectorType, lastMessageIsAlsoFiltered);
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorWithLastMessageFilteredParams")
    void testFilteredResendsWithEnabledNextExpectedMsgSeqNumOnLogon(ConnectorType connectorType, boolean lastMessageIsAlsoFiltered) {
        // the same filtering, recovered through NextExpectedMsgSeqNum(789) instead of a ResendRequest(35=2). The two
        // paths meet in MessagesResender.resendMessages, but only after the Logon(35=A) branch has worked the range
        // out for itself - and that arithmetic, which has to leave out the Logon that consumed a MsgSeqNum of its
        // own, is what the sibling tests never reach. Enabled on both ends, 789 being a round trip.
        setupInitiatorSessionSettings(s -> s.enabledLogonNextExpectedMsgSeqNum(true).build());
        setupAcceptorSessionSettings(s -> s.enabledLogonNextExpectedMsgSeqNum(true).build());

        assertFilteredResendsAreGapFilled(connectorType, lastMessageIsAlsoFiltered);

        // section 4.4.1: the gap is filled off the Logon alone, so neither side may have asked for a retransmission
        assertNoResendRequestSent();
    }

    /**
     * Sends a run of messages the application will accept to retransmit interleaved with a run it will decline, opens
     * a gap over them and asserts the recovery: the accepted ones come back flagged PossDupFlag(43)=Y, the declined
     * ones are covered by SequenceReset(35=4) gap fills, and the session still works afterwards.
     * <p>
     * Shared by the ResendRequest(35=2) and the NextExpectedMsgSeqNum(789) variants, which differ in what drives the
     * retransmission and not in what it has to produce.
     */
    private void assertFilteredResendsAreGapFilled(ConnectorType connectorType, boolean lastMessageIsAlsoFiltered) {
        logonClient();

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixSession targetFixSession = getFixSession(connectorType);
        FixMessageDecoder targetDecoder = getDecoder(testMessageType, connectorType.inverse());

        // even indices are TradingSessionStatusRequests, which the application declines to retransmit below, odd
        // indices are Emails, which it accepts: the resend therefore alternates gap fills and retransmissions
        sendMessagesAndCutConnection(connectorType, i -> {
            if (i % 2 == 0) {
                targetFixSession.send(targetFixSession.newEncoder(TradingSessionStatusRequestEncoder.class)
                        .begin().setTradSesReqID("trading-session-status-test-request-" + i)
                        .setSubscriptionRequestType(SubscriptionRequestType.SubscriptionRequestTypeValues.SNAPSHOT), null);
            } else {
                targetFixSession.send(encodeTestMessage(i), null);
            }
        });
        if (lastMessageIsAlsoFiltered) {
            // a declined message right at the end of the range, which has no retransmission after it to carry the
            // gap fill: the resender has to emit one of its own to cover the tail
            targetFixSession.send(targetFixSession.newEncoder(TradingSessionStatusRequestEncoder.class)
                    .begin().setTradSesReqID("trading-session-status-test-request-last")
                    .setSubscriptionRequestType(SubscriptionRequestType.SubscriptionRequestTypeValues.SNAPSHOT), null);
            lastMissedSeqNum = getFixMessagesStore(connectorType).getOutgoingSeqNum() - 1;
        }

        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 1");
        assertMessageReceived(messagesReceiverList, TradSesReqID.get(), "trading-session-status-test-request-0");
        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 9");
        assertMessageReceived(messagesReceiverList, TradSesReqID.get(), "trading-session-status-test-request-10");

        clearApplicationsInvocations();
        // from here on the loggers hold the recovery and nothing else, which is what lets the 789 variant assert that
        // no ResendRequest was part of it
        initiatorLogger.clear();
        acceptorLogger.clear();

        when(resendRequestSender.onResendRequest(any(FixSession.class), eq(testMessageType), any(DecodedFixMessage.class))).thenReturn(true);
        when(resendRequestSender.onResendRequest(any(FixSession.class), eq(MessageTypes.TradingSessionStatusRequest), any(DecodedFixMessage.class))).thenReturn(false);

        reconnect(connectorType);

        // messages 11 to 19 went missing: the odd ones (11, 13, 15, 17, 19) are Emails and come back, the even ones
        // (12, 14, 16, 18) are declined and are gap filled one by one, plus the trailing one when it is filtered too
        int expectedGapFills = lastMessageIsAlsoFiltered ? 5 : 4;
        int expectedRetransmissions = 5;

        awaitResendRequestCompleted(resendRequestReceiver);
        await().untilAsserted(() -> verify(resendRequestSender, times(expectedGapFills)).onSequenceReset(any(FixSession.class), anyLong(), eq(true)));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(targetDecoder, times(expectedRetransmissions)).onDecoded(any(FixSession.class), eq(true), eq(false)));

        assertNoMessageReceived(messagesReceiverList, TradSesReqID.get(), "trading-session-status-test-request-12");
        assertNoMessageReceived(messagesReceiverList, TradSesReqID.get(), "trading-session-status-test-request-18");
        if (lastMessageIsAlsoFiltered) {
            assertNoMessageReceived(messagesReceiverList, TradSesReqID.get(), "trading-session-status-test-request-last");
        }

        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 11");
        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 13");
        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 15");
        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 17");
        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 19");

        assertMessagesSendReceiveStillWorkAfterResync();
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testResendRequestWithMoreMessagesToResendThanAllowedPerRequest(ConnectorType connectorType) {
        // The peer chooses the range, so it chooses how much work answering costs. Past
        // maxMessagesResentPerRequest the rest is gap filled instead of replayed - section 4.8.5 lets a resender do
        // that with anything it chooses not to retransmit - so the peer still ends up synchronized.
        int maxResent = 3;
        setupSessionSettings(connectorType, s -> s.maxMessagesResentPerRequest(maxResent).build());

        logonClient();

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixSession targetFixSession = getFixSession(connectorType);
        FixMessageDecoder targetDecoder = getDecoder(testMessageType, connectorType.inverse());

        sendMessagesAndCutConnection(connectorType, i -> targetFixSession.send(encodeTestMessage(i), null));

        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 10");

        clearApplicationsInvocations();

        when(resendRequestSender.onResendRequest(any(), any(), any())).thenReturn(true);

        reconnect(connectorType);

        awaitResendRequestCompleted(resendRequestReceiver);

        // only the first few are put back on the wire, and the store is not even read past them
        await().untilAsserted(() -> verify(targetDecoder, times(maxResent)).onDecoded(any(FixSession.class), eq(true), eq(false)));
        verify(resendRequestSender, times(maxResent)).onResendRequest(any(FixSession.class), eq(testMessageType), any(DecodedFixMessage.class));

        // the remainder of the range is covered by a single gap fill rather than replayed. Awaited: the sender emits
        // it after the messages it replayed, so the decode awaited above can land before this one has been recorded
        await().untilAsserted(() -> verify(resendRequestSender, times(1))
                .onSequenceReset(any(FixSession.class), eq(lastMissedSeqNum + 1), eq(true)));

        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 11");
        assertNoMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 19");

        assertMessagesSendReceiveStillWorkAfterResync();
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testResendRequestDoesNotBlockIncomingMessagesWhileRetransmitting(ConnectorType connectorType) {
        // Answering a ResendRequest(35=2) runs away from the IO thread, so the connection keeps being read while the
        // replay is going on. Held on the IO thread, as it used to be, nothing arriving from the peer would be
        // processed until the whole range had been put back on the wire - and every other session sharing that
        // worker would wait too.
        logonClient();

        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixSession targetFixSession = getFixSession(connectorType);

        sendMessagesAndCutConnection(connectorType, i -> targetFixSession.send(encodeTestMessage(i), null));

        assertMessageReceived(getDecodedFixMessages(connectorType.inverse()), EmailThreadID.get(), "test thread id 10");

        clearApplicationsInvocations();

        // the replay is made slow, one message at a time, and the peer sends while it runs. If the retransmission
        // held the IO thread, that message could not be decoded until the replay was over.
        CountDownLatch replayStarted = new CountDownLatch(1);
        CountDownLatch messageReceivedWhileReplaying = new CountDownLatch(1);
        when(resendRequestSender.onResendRequest(any(), any(), any())).thenAnswer(invocation -> {
            if (replayStarted.getCount() > 0) {
                replayStarted.countDown();
                // the first replayed message holds the retransmission up until the peer's message has been through,
                // which is the whole point of the test. Bounded, and only once: were the replay back on the IO thread
                // this could never be released, and a bounded wait fails the assertion below rather than wedging the
                // build waiting for something that cannot happen
                messageReceivedWhileReplaying.await(REPLAY_HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            return true;
        });

        FixSession recoveringSession = getFixSession(connectorType.inverse());
        doAnswer(invocation -> {
            messageReceivedWhileReplaying.countDown();
            return null;
        }).when(resendRequestSender).onHeartbeat(any(FixSession.class), any(UTCTime.class));

        reconnect(connectorType);

        // once the replay is under way and stalled, the peer sends something the retransmitting side must still read
        assertThat(awaitLatch(replayStarted)).as("the retransmission must have started").isTrue();
        recoveringSession.testRequest("resend-not-blocking-io");

        assertThat(awaitLatch(messageReceivedWhileReplaying))
                .as("the retransmitting side must keep reading its connection while replaying")
                .isTrue();

        // and the recovery still completes normally afterwards
        awaitResendRequestCompleted(resendRequestReceiver);
        assertMessagesSendReceiveStillWorkAfterResync();
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testHardSequenceResetWhileARetransmissionIsPending(ConnectorType connectorType) {
        // Section 4.8.6 does not say what a hard SequenceReset(35=4, GapFillFlag(123) other than Y) means when it
        // lands while the receiver is still waiting on a ResendRequest(35=2) it sent. The reset is meant for disaster
        // recovery, and the only defined answer to a resend request is a gap fill, so a peer doing this is already
        // off the map - but it is what a peer that has lost its store and cannot satisfy the request would reach for.
        //
        // What staffix does today is apply the reset and leave the request outstanding, because processHardReset -
        // unlike processGapFill - never tells FixSessionStateComponent the range has been dealt with. This asserts the
        // other reading, that a reset carrying the sequence past everything asked for settles the request: the
        // application is told the recovery ended and the messages held back during it are let go.
        //
        // Heartbeats are pushed out of the way first. One arriving from the peer settles the outstanding request all
        // by itself - a Heartbeat does not manage its own sequence number, so it takes the ordinary path in
        // FixSessionImpl, which does tell the state the range has been dealt with - and at the usual five second
        // interval it beats any assertion made here. What is under test is what the reset does, not what the next
        // message to arrive happens to do.
        // the acceptor refuses anything above its acceptorUpperBoundInterval, twenty seconds by default, so that has
        // to be lifted alongside or the session never logs on at all
        setupSessionSettings(ConnectorType.INITIATOR, s -> s.heartBeatInterval(
                FixSessionSettings.HeartbeatInterval.builder().initiatorInterval(Duration.ofSeconds(60)).build()).build());
        setupSessionSettings(ConnectorType.ACCEPTOR, s -> s.heartBeatInterval(
                FixSessionSettings.HeartbeatInterval.builder().acceptorUpperBoundInterval(Duration.ofSeconds(120)).build()).build());

        logonClient();

        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixSession targetFixSession = getFixSession(connectorType);

        sendMessagesAndCutConnection(connectorType, i -> targetFixSession.send(encodeTestMessage(i), null));
        assertMessageReceived(getDecodedFixMessages(connectorType.inverse()), EmailThreadID.get(), "test thread id 10");
        clearApplicationsInvocations();

        // the replay is stalled on its first message so that the request is still outstanding when the reset lands.
        // Blocked in the application it consumes no sequence numbers meanwhile, which keeps the reset's own
        // renumbering out of the way.
        CountDownLatch replayStarted = new CountDownLatch(1);
        CountDownLatch releaseReplay = new CountDownLatch(1);
        when(resendRequestSender.onResendRequest(any(), any(), any())).thenAnswer(invocation -> {
            replayStarted.countDown();
            awaitLatch(releaseReplay);
            return true;
        });

        try {
            reconnect(connectorType);
            assertThat(awaitLatch(replayStarted)).as("the retransmission must have started").isTrue();

            // past the far end of what the peer asked for, so nothing it is waiting for can still arrive
            long newSeqNo = lastMissedSeqNum + 1;
            getFixSessionImpl(connectorType).hardSequenceReset(newSeqNo);

            // at least, not exactly: settling the request replays whatever was queued on top of the gap - the message
            // that revealed it in the first place - and each of those carries the sequence number on again
            await().untilAsserted(() -> assertThat(getFixMessagesStore(connectorType.inverse()).getIncomingSeqNum())
                    .as("the hard reset must be applied whatever else is going on")
                    .isGreaterThanOrEqualTo(newSeqNo));
            await().untilAsserted(() -> verify(resendRequestReceiver)
                    .onResendRequestTerminated(any(FixSession.class), eq(firstMissedSeqNum), eq(lastMissedSeqNum)));
        } finally {
            releaseReplay.countDown();
        }
    }

    /**
     * The replay runs on its own thread, so the connection it answers can close under it. It used to carry on and
     * fail with a NullPointerException on the next message it sent.
     */
    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testRetransmissionIsAbandonedWhenItsConnectionCloses(ConnectorType connectorType) {
        logonClient();

        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixSession targetFixSession = getFixSession(connectorType);

        sendMessagesAndCutConnection(connectorType, i -> targetFixSession.send(encodeTestMessage(i), null));
        assertMessageReceived(getDecodedFixMessages(connectorType.inverse()), EmailThreadID.get(), "test thread id 10");
        clearApplicationsInvocations();

        CountDownLatch replayStarted = new CountDownLatch(1);
        CountDownLatch releaseReplay = new CountDownLatch(1);
        when(resendRequestSender.onResendRequest(any(), any(), any())).thenAnswer(invocation -> {
            if (replayStarted.getCount() > 0) {
                replayStarted.countDown();
                awaitLatch(releaseReplay);
            }
            return true;
        });

        try {
            reconnect(connectorType);
            assertThat(awaitLatch(replayStarted)).as("the retransmission must have started").isTrue();
            cutConnection(connectorType);
        } finally {
            releaseReplay.countDown();
        }

        await().untilAsserted(() -> assertThat(getLogger(connectorType).getEvents())
                .anyMatch(event -> event.contains("Retransmission abandoned, the connection it answered has closed")));
        verify(resendRequestSender, times(1)).onResendRequest(any(FixSession.class), any(), any(DecodedFixMessage.class));

        reconnect(connectorType);
        assertPossDupMessageReceived(getDecodedFixMessages(connectorType.inverse()), EmailThreadID.get(), "test thread id 19");
        assertMessagesSendReceiveStillWorkAfterResync();
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testResendRequestWithMessagesResentPerRequestLimitDisabled(ConnectorType connectorType) {
        // maxMessagesResentPerRequest = 0 means no limit at all, not a limit of none: the whole range is put back on
        // the wire however wide it is. Worth pinning because the obvious way of writing that check - stopping once
        // as many messages as the setting allows have been sent - makes 0 the most restrictive value there is.
        setupSessionSettings(connectorType, s -> s.maxMessagesResentPerRequest(0).build());

        logonClient();

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixSession targetFixSession = getFixSession(connectorType);
        FixMessageDecoder targetDecoder = getDecoder(testMessageType, connectorType.inverse());

        sendMessagesAndCutConnection(connectorType, i -> targetFixSession.send(encodeTestMessage(i), null));

        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 10");

        clearApplicationsInvocations();

        when(resendRequestSender.onResendRequest(any(), any(), any())).thenReturn(true);

        reconnect(connectorType);

        awaitResendRequestCompleted(resendRequestReceiver);

        // every missed message comes back, and nothing had to be skipped
        await().untilAsserted(() -> verify(targetDecoder, times(missedMessagesCount())).onDecoded(any(FixSession.class), eq(true), eq(false)));
        verify(resendRequestSender, never()).onSequenceReset(any(FixSession.class), anyLong(), eq(true));

        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 11");
        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 19");

        assertMessagesSendReceiveStillWorkAfterResync();
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    // a resender that drops the retransmission leaves the peer asking for it over and over rather than failing an
    // assertion, so this one is cut short instead of holding a CI job for as long as it is let run
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void testSameMessagesRetransmittedTwice(ConnectorType connectorType) {
        // A store may hand the resender the very buffer it holds a message in, so a retransmission has to leave that
        // buffer as it found it. Reading it used to drain it, and a second request for the same message then parsed
        // nothing at all: the retransmission was silently replaced by a gap fill, the session staying consistent
        // enough for this to go unnoticed.
        logonClient();

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixSession targetFixSession = getFixSession(connectorType);
        FixMessageDecoder targetDecoder = getDecoder(testMessageType, connectorType.inverse());

        sendMessagesAndCutConnection(connectorType, i -> targetFixSession.send(encodeTestMessage(i), null));

        clearApplicationsInvocations();

        when(resendRequestSender.onResendRequest(any(), any(), any())).thenReturn(true);

        reconnect(connectorType);

        awaitResendRequestCompleted(resendRequestReceiver);
        await().untilAsserted(() -> verify(targetDecoder, times(missedMessagesCount())).onDecoded(any(FixSession.class), eq(true), eq(false)));

        clearApplicationsInvocations();

        // the peer forgets what it has just recovered, so that logging on again has it ask for those messages a
        // second time - the range now ending further on, the Logon of the first recovery having taken a number since
        getFixSessionImpl(connectorType.inverse()).adminSetIncomingSeqNum(firstMissedSeqNum);
        cutConnection(connectorType);
        reconnect(connectorType);

        await().untilAsserted(() -> verify(resendRequestReceiver)
                .onResendRequestTerminated(any(FixSession.class), eq(firstMissedSeqNum), anyLong()));

        // the same messages come back a second time rather than being gap filled away
        await().untilAsserted(() -> verify(targetDecoder, times(missedMessagesCount())).onDecoded(any(FixSession.class), eq(true), eq(false)));
        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 11");
        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 19");
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testResendRequestWithOutOfSequenceMessagesQueueLimitDisabled(ConnectorType connectorType) {
        // maxOutOfSequenceMessagesQueued = 0 means no bound on the queue, not a bound of none. Written the obvious
        // way it would be the harshest setting there is, logging the session out on the very first message that
        // arrives on top of a gap - the exact opposite of turning the restriction off.
        setupSessionSettings(connectorType.inverse(), s -> s.maxOutOfSequenceMessagesQueued(0).build());

        logonClient();

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixSession targetFixSession = getFixSession(connectorType);

        sendMessagesAndCutConnection(connectorType, i -> targetFixSession.send(encodeTestMessage(i), null));

        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 10");

        clearApplicationsInvocations();

        when(resendRequestSender.onResendRequest(any(), any(), any())).thenReturn(true);

        reconnect(connectorType);

        // the recovery runs to completion rather than the session being logged out over the queue bound
        awaitResendRequestCompleted(resendRequestReceiver);
        verify(resendRequestReceiver, never()).onLogoutInitiated(any(FixSession.class),
                eq("Too many messages received while awaiting the response to a ResendRequest"));

        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 19");

        assertMessagesSendReceiveStillWorkAfterResync();
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testResendRequestWithNoMessagesInStore(ConnectorType connectorType) {
        logonClient();

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixSession targetFixSession = getFixSession(connectorType);
        FixMessageDecoder targetDecoder = getDecoder(testMessageType, connectorType.inverse());

        sendMessagesAndCutConnection(connectorType, i -> targetFixSession.send(encodeTestMessage(i), null));

        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 0");
        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 10");

        clearApplicationsInvocations();

        // nothing left to retransmit: the whole requested range has to be covered by one SequenceReset gap fill
        getFixMessagesStore(connectorType).clearMessages();

        when(resendRequestSender.onResendRequest(any(), any(), any())).thenReturn(true);

        reconnect(connectorType);

        awaitResendRequestCompleted(resendRequestReceiver);
        await().untilAsserted(() -> verify(resendRequestSender, never()).onResendRequest(any(FixSession.class), eq(testMessageType), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(resendRequestSender, times(1)).onSequenceReset(any(FixSession.class), eq(lastMissedSeqNum + 1), eq(true)));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(targetDecoder, never()).onDecoded(any(FixSession.class), eq(true), anyBoolean()));

        assertNoMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 11");
        assertNoMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 19");

        assertMessagesSendReceiveStillWorkAfterResync();
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testResendRequestWithoutGaps(ConnectorType connectorType) {
        logonClient();

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixSession targetFixSession = getFixSession(connectorType);
        FixMessageDecoder targetDecoder = getDecoder(testMessageType, connectorType.inverse());

        sendMessagesAndCutConnection(connectorType, i -> targetFixSession.send(encodeTestMessage(i), null));

        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 0");
        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 10");

        clearApplicationsInvocations();

        when(resendRequestSender.onResendRequest(any(), any(), any())).thenReturn(true);

        reconnect(connectorType);

        awaitResendRequestCompleted(resendRequestReceiver);
        await().untilAsserted(() -> verify(resendRequestSender, times(missedMessagesCount()))
                .onResendRequest(any(FixSession.class), eq(testMessageType), any(DecodedFixMessage.class)));
        // every message of the range is retransmitted, so nothing has to be skipped
        await().untilAsserted(() -> verify(resendRequestSender, never()).onSequenceReset(any(FixSession.class), anyLong(), eq(true)));
        await().untilAsserted(() -> verify(fixAcceptorApplication).validateLogon(any(FixSession.class), any(DecodedFixMessage.class), any(Executor.class)));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(targetDecoder, times(missedMessagesCount())).onDecoded(any(FixSession.class), eq(true), eq(false)));

        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 11");
        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 19");

        assertMessagesSendReceiveStillWorkAfterResync();
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testResendRequestWithoutGapsAndNoConnectorStop(ConnectorType connectorType) {
        logonClient();

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixMessageDecoder targetDecoder = getDecoder(testMessageType, connectorType.inverse());

        for (int i = 0; i < MESSAGES_TO_SEND; i++) {
            getFixSession(connectorType).send(encodeTestMessage(i), null);
        }

        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 0");
        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 19");

        clearApplicationsInvocations();

        when(resendRequestSender.onResendRequest(any(), any(), any())).thenReturn(true);

        FixSession targetFixSession = getFixSession(connectorType.inverse());

        targetFixSession.logoutPermanently("test logout");

        await().untilAsserted(() -> verify(getFixApplication(connectorType.inverse())).onLogout(any(FixSession.class), eq("test logout"), any(DecodedFixMessage.class)));
        // the session is what has to be down before the sequence numbers below are moved, and it stays down: a
        // permanent logout reconnects an initiator but sends no Logon, so waiting on the disconnection instead would
        // be waiting on a gap that lasts one connection retry
        await().untilAsserted(() -> assertThat(targetFixSession.isLoggedIn()).isFalse());
        // and its numbering has to have settled too, which the logout callback above does not say: a session tells
        // the application about a message before it stores what it expects after it, so the peer's Logout is still
        // unaccounted for at that point and the write that accounts for it would land on top of the rewind below,
        // putting back the very sequence number the test is trying to move. Waiting for what this side expects to
        // meet what the peer will send next is waiting for exactly that write
        await().untilAsserted(() -> assertThat(getFixMessagesStore(connectorType.inverse()).getIncomingSeqNum())
                .isEqualTo(getFixMessagesStore(connectorType).getOutgoingSeqNum()));

        // rather than losing the connection, this side rewinds what it expects to receive: everything from 10 on has
        // to be replayed even though it was delivered the first time round
        firstMissedSeqNum = 10;
        lastMissedSeqNum = getFixMessagesStore(connectorType).getOutgoingSeqNum() - 1;
        getFixMessagesStore(connectorType.inverse()).setCurrentIncomingSeqNum(firstMissedSeqNum);

        targetFixSession.logon();

        awaitResendRequestCompleted(resendRequestReceiver);
        // the range covers session level messages too - the Logout just exchanged, at least - which are not
        // retransmitted but gap filled, so the retransmissions are fewer than the range is wide
        await().untilAsserted(() -> verify(resendRequestSender, atLeastOnce())
                .onResendRequest(any(FixSession.class), eq(testMessageType), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(resendRequestSender, atLeastOnce()).onSequenceReset(any(FixSession.class), anyLong(), eq(true)));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(targetDecoder, atLeastOnce()).onDecoded(any(FixSession.class), eq(true), eq(false)));

        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 8");
        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 19");

        assertMessagesSendReceiveStillWorkAfterResync();
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testResendRequestWithApplicationSendingMessagesDuringResendRequest(ConnectorType connectorType) {
        // Section 4.3.11: a peer should hold "queued or new application messages" until the session is synchronized.
        // A message handed over while the retransmission is running must therefore wait for it to finish, and take
        // its MsgSeqNum(34) then, rather than landing in the middle of the replayed range.
        logonClient();

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixSession targetFixSession = getFixSession(connectorType);
        FixMessageDecoder targetDecoder = getDecoder(testMessageType, connectorType.inverse());

        sendMessagesAndCutConnection(connectorType, i -> targetFixSession.send(encodeTestMessage(i), null));

        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 0");
        assertMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 10");

        clearApplicationsInvocations();

        // the side awaiting the retransmission hands over an application message the moment it asks for it, which is
        // the window the holding covers
        FixSession recoveringSession = getFixSession(connectorType.inverse());
        doAnswer(invocation -> {
            recoveringSession.send(encodeTestMessage(200), null);
            return null;
        }).when(resendRequestReceiver).onResendRequestInitiated(any(FixSession.class), anyLong(), anyLong());

        // recorded the moment the recovery is declared over, before anything held is let go: the message must still
        // be waiting at that point, which is the whole of what section 4.3.11 asks for
        TestingLogger recoveringLogger = connectorType.inverse().select(initiatorLogger, acceptorLogger);
        AtomicBoolean sentBeforeRecoveryEnded = new AtomicBoolean();
        doAnswer(invocation -> {
            sentBeforeRecoveryEnded.set(recoveringLogger.getOutgoingMessages().stream()
                    .anyMatch(message -> message.contains("test thread id 200")));
            return null;
        }).when(resendRequestReceiver).onResendRequestTerminated(any(FixSession.class), anyLong(), anyLong());

        when(resendRequestSender.onResendRequest(any(), any(), any())).thenReturn(true);

        reconnect(connectorType);

        awaitResendRequestCompleted(resendRequestReceiver);
        await().untilAsserted(() -> verify(targetDecoder, times(missedMessagesCount())).onDecoded(any(FixSession.class), eq(true), eq(false)));

        // it did go out, once the recovery was over
        assertMessageReceived(getDecodedFixMessages(connectorType), EmailThreadID.get(), "test thread id 200");

        assertThat(sentBeforeRecoveryEnded)
                .as("the message handed over during the recovery must have been held until it finished")
                .isFalse();

        assertMessagesSendReceiveStillWorkAfterResync();
    }

    /**
     * The IO thread ends the holding while the application keeps sending: a message handed over at that moment used
     * to be added to the held list after it had been drained, and was never sent.
     */
    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testMessagesSentWhileTheHoldingEndsAreAllDelivered(ConnectorType connectorType) throws Exception {
        logonClient();

        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixSession targetFixSession = getFixSession(connectorType);
        sendMessagesAndCutConnection(connectorType, i -> targetFixSession.send(encodeTestMessage(i), null));
        assertMessageReceived(getDecodedFixMessages(connectorType.inverse()), EmailThreadID.get(), "test thread id 10");
        clearApplicationsInvocations();
        when(resendRequestSender.onResendRequest(any(), any(), any())).thenReturn(true);

        FixSession recoveringSession = getFixSession(connectorType.inverse());
        AtomicBoolean recoveryEnded = new AtomicBoolean();
        doAnswer(invocation -> {
            recoveryEnded.set(true);
            return null;
        }).when(resendRequestReceiver).onResendRequestTerminated(any(FixSession.class), anyLong(), anyLong());
        CountDownLatch sendingStarted = new CountDownLatch(1);
        AtomicInteger sentCount = new AtomicInteger();
        Thread sender = new Thread(() -> {
            awaitLatch(sendingStarted);
            long stopAt = Long.MAX_VALUE;
            while (System.nanoTime() < stopAt) {
                recoveringSession.send(encodeTestMessage(1000 + sentCount.getAndIncrement()), null);
                // well under maxOutgoingMessagesHeldDuringRecovery for the time a recovery takes
                LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(200));
                if (recoveryEnded.get() && stopAt == Long.MAX_VALUE) {
                    stopAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20);
                }
            }
        }, "application-sender");
        doAnswer(invocation -> {
            sendingStarted.countDown();
            return null;
        }).when(resendRequestReceiver).onResendRequestInitiated(any(FixSession.class), anyLong(), anyLong());
        sender.start();

        reconnect(connectorType);
        awaitResendRequestCompleted(resendRequestReceiver);
        sender.join(TimeUnit.SECONDS.toMillis(30));

        List<String> expected = IntStream.range(1000, 1000 + sentCount.get()).mapToObj(i -> "test thread id " + i).collect(toList());
        await().untilAsserted(() -> assertThat(getDecodedFixMessages(connectorType).stream()
                .map(message -> message.getString(EmailThreadID.get(), ""))
                .filter(new HashSet<>(expected)::contains))
                .as("every message handed over around the end of the recovery must arrive, in order")
                .containsExactlyElementsOf(expected));
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testResendRequestWithApplicationSendingMessagesDuringResendRequestAndHoldingDisabled(ConnectorType connectorType) {
        // maxOutgoingMessagesHeldDuringRecovery = 0 means never hold: the message goes out where it was handed over,
        // in the middle of the recovery, which is what this engine did before the holding existed. The exact inverse
        // of testResendRequestWithApplicationSendingMessagesDuringResendRequest above.
        setupSessionSettings(connectorType.inverse(), s -> s.maxOutgoingMessagesHeldDuringRecovery(0).build());

        logonClient();

        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixSession targetFixSession = getFixSession(connectorType);

        sendMessagesAndCutConnection(connectorType, i -> targetFixSession.send(encodeTestMessage(i), null));

        assertMessageReceived(getDecodedFixMessages(connectorType.inverse()), EmailThreadID.get(), "test thread id 10");

        clearApplicationsInvocations();
        initiatorLogger.clear();
        acceptorLogger.clear();

        FixSession recoveringSession = getFixSession(connectorType.inverse());
        doAnswer(invocation -> {
            recoveringSession.send(encodeTestMessage(400), null);
            return null;
        }).when(resendRequestReceiver).onResendRequestInitiated(any(FixSession.class), anyLong(), anyLong());

        TestingLogger recoveringLogger = connectorType.inverse().select(initiatorLogger, acceptorLogger);
        AtomicBoolean sentBeforeRecoveryEnded = new AtomicBoolean();
        doAnswer(invocation -> {
            sentBeforeRecoveryEnded.set(recoveringLogger.getOutgoingMessages().stream()
                    .anyMatch(message -> message.contains("test thread id 400")));
            return null;
        }).when(resendRequestReceiver).onResendRequestTerminated(any(FixSession.class), anyLong(), anyLong());

        when(resendRequestSender.onResendRequest(any(), any(), any())).thenReturn(true);

        reconnect(connectorType);

        awaitResendRequestCompleted(resendRequestReceiver);

        assertMessageReceived(getDecodedFixMessages(connectorType), EmailThreadID.get(), "test thread id 400");
        assertThat(sentBeforeRecoveryEnded)
                .as("with holding disabled the message must have gone out during the recovery, not after it")
                .isTrue();

        assertMessagesSendReceiveStillWorkAfterResync();
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testResendRequestWithMoreMessagesSentDuringResendRequestThanCanBeHeld(ConnectorType connectorType) {
        // Holding application messages during a recovery is bounded. Past the bound the session refuses the message
        // rather than sending it into the middle of the retransmission or dropping it silently: the application is
        // told through the callback it passed to send(), and can decide for itself.
        setupSessionSettings(connectorType.inverse(), s -> s.maxOutgoingMessagesHeldDuringRecovery(1).build());

        logonClient();

        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixSession targetFixSession = getFixSession(connectorType);

        sendMessagesAndCutConnection(connectorType, i -> targetFixSession.send(encodeTestMessage(i), null));

        assertMessageReceived(getDecodedFixMessages(connectorType.inverse()), EmailThreadID.get(), "test thread id 10");

        clearApplicationsInvocations();

        // two messages handed over while recovering, where only one can be held
        FixSession recoveringSession = getFixSession(connectorType.inverse());
        List<Exception> sendingErrors = new CopyOnWriteArrayList<>();
        List<String> refusedMessages = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            for (int messageIndex = 300; messageIndex <= 301; messageIndex++) {
                recoveringSession.send(encodeTestMessage(messageIndex), null,
                        (sendingError, refusedIndex, unused) -> {
                            if (sendingError != null) {
                                sendingErrors.add(sendingError);
                                refusedMessages.add("test thread id " + refusedIndex);
                            }
                        }, messageIndex, null);
            }
            return null;
        }).when(resendRequestReceiver).onResendRequestInitiated(any(FixSession.class), anyLong(), anyLong());

        when(resendRequestSender.onResendRequest(any(), any(), any())).thenReturn(true);

        reconnect(connectorType);

        awaitResendRequestCompleted(resendRequestReceiver);

        // the first was held and went out once the recovery was over, the second was refused and never sent
        assertMessageReceived(getDecodedFixMessages(connectorType), EmailThreadID.get(), "test thread id 300");
        assertNoMessageReceived(getDecodedFixMessages(connectorType), EmailThreadID.get(), "test thread id 301");

        await().untilAsserted(() -> assertThat(sendingErrors)
                .as("the refused message must be reported to the callback its sender passed")
                .hasSize(1));
        assertThat(sendingErrors.get(0))
                .hasMessage("Too many application messages held back while the session recovers missing messages");
        assertThat(refusedMessages).containsExactly("test thread id 301");

        assertMessagesSendReceiveStillWorkAfterResync();
    }

    /**
     * EndSeqNo(16) of the one ResendRequest(35=2) {@code asker} put on the wire since its logger was last cleared.
     */
    private String endSeqNoOfResendRequestSentBy(ConnectorType asker) {
        List<String> resendRequests = getLogger(asker).getOutgoingMessages().stream()
                .filter(message -> message.contains(RESEND_REQUEST_MSG_TYPE_FIELD))
                .collect(toList());
        assertThat(resendRequests).as("exactly one ResendRequest is expected on the wire").hasSize(1);
        return FixMessageFields.valuesOf(resendRequests.get(0), 16).get(0);
    }

    /**
     * Section 4.8.2 lets a ResendRequest(35=2) name "a single message, a range of messages or all messages", and
     * {@link ResendRequestRange} is which of the last two this engine asks for. The default names the gap.
     */
    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testResendRequestNamesTheEndOfTheGapByDefault(ConnectorType connectorType) {
        logonClient();

        when(getFixApplication(connectorType).onResendRequest(any(), any(), any())).thenReturn(true);
        sendMessagesAndCutConnection(connectorType, i -> getFixSession(connectorType).send(encodeTestMessage(i), null));

        clearApplicationsInvocations();
        getLogger(connectorType.inverse()).clear();

        reconnect(connectorType);

        awaitResendRequestCompleted(getFixApplication(connectorType.inverse()));

        assertThat(endSeqNoOfResendRequestSentBy(connectorType.inverse()))
                .as("a closed range asks for the messages that are missing and no others")
                .isEqualTo(String.valueOf(lastMissedSeqNum));
    }

    /**
     * The same gap asked for open ended, which is the form QuickFIX/J sends by default (its
     * {@code ClosedResendInterval=N}) and the one some counterparties answer exclusively.
     * <p>
     * EndSeqNo(16)=0 is the FIX.4.2-and-later spelling of "everything from BeginSeqNo(7) onwards"; FIX.4.1 and
     * earlier spell it 999999, a version this engine does not speak (see
     * {@code FixAbstractAdminMessagesCodec#openEndedEndSeqNum}). The recovery itself must be unaffected: the range
     * this side is missing is still the closed one, so that is what the application is told and what the pending
     * resend ends at, however much the peer chooses to send back.
     */
    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testOpenEndedResendRequestAsksForEverythingFromTheGapOnwards(ConnectorType connectorType) {
        setupSessionSettings(connectorType.inverse(),
                s -> s.resendRequestRange(ResendRequestRange.OPEN_ENDED).build());

        logonClient();

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        FixApplication resendRequestSender = getFixApplication(connectorType);
        FixApplication resendRequestReceiver = getFixApplication(connectorType.inverse());
        FixMessageDecoder targetDecoder = getDecoder(testMessageType, connectorType.inverse());

        when(resendRequestSender.onResendRequest(any(), any(), any())).thenReturn(true);
        sendMessagesAndCutConnection(connectorType, i -> getFixSession(connectorType).send(encodeTestMessage(i), null));

        clearApplicationsInvocations();
        getLogger(connectorType.inverse()).clear();

        reconnect(connectorType);

        // the range reported to the application is the gap, not the infinity that went on the wire
        awaitResendRequestCompleted(resendRequestReceiver);
        await().untilAsserted(() -> verify(resendRequestSender, times(missedMessagesCount()))
                .onResendRequest(any(FixSession.class), eq(testMessageType), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(targetDecoder, times(missedMessagesCount())).onDecoded(any(FixSession.class), eq(true), eq(false)));

        assertThat(endSeqNoOfResendRequestSentBy(connectorType.inverse()))
                .as("EndSeqNo(16)=0 is how FIX.4.2 and later ask for everything from BeginSeqNo(7) onwards")
                .isEqualTo("0");

        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 11");
        assertPossDupMessageReceived(messagesReceiverList, EmailThreadID.get(), "test thread id 19");

        assertMessagesSendReceiveStillWorkAfterResync();
    }

    /**
     * A gap fill whose NewSeqNo(36) lands exactly on the EndSeqNo(16) that was asked for: it accounts for everything
     * <em>below</em> that number, so the last message of the range is still to come and the request is not yet
     * satisfied. Closing it there releases the messages held on top of the gap while the sequence number they wait
     * for has not been reached, and the drain then leaves them queued - with nothing left to ask for a replay, they
     * are never delivered at all and the next message the peer sends opens a gap of its own.
     * <p>
     * Driven from a raw socket because the two halves of the answer have to land in separate reads: in the same one
     * the replay happens after both have been parsed and the premature closing goes unnoticed, which is what made
     * this a coin toss against another engine rather than a failure.
     */
    @Test
    void testResendRequestIsNotClosedByAGapFillEndingOnTheLastMessageOfTheRange() throws Exception {
        startFixAcceptor();

        FixSessionId initiator = getInitiatorFixSessionSettings().build().getFixSessionId();
        try (RawFixSocketClient.Session peer = RawFixSocketClient.connect(acceptorPort, initiator.getFixVersion(),
                initiator.getSenderCompID().getValue(), initiator.getTargetCompID().getValue(), Duration.ofSeconds(10))) {
            peer.send(peer.message(MessageTypes.Logon, 1).set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));
            peer.readMessageOfType(MessageTypes.Logon, Duration.ofSeconds(10));

            // NextNumIn is 2, so a message at 5 opens a gap over 2 to 4 and is held on top of it
            peer.send(peer.message(MessageTypes.TestRequest, 5).set(TestReqID.get(), "held-on-top-of-the-gap"));

            assertThat(FixMessageFields.valuesOf(
                    peer.readMessageOfType(MessageTypes.ResendRequest, Duration.ofSeconds(10)), CoreFields.END_SEQ_NO))
                    .as("the range asked for ends at 4, the message below the one that opened the gap")
                    .containsExactly("4");

            // 2 and 3 skipped, which leaves 4 - the end of the range - still owed
            peer.send(peer.message(MessageTypes.SequenceReset, 2)
                    .set(GapFillFlag.get(), "Y").set(NewSeqNo.get(), "4"));
            await().untilAsserted(() -> assertThat(acceptorMessagesStore.getIncomingSeqNum())
                    .as("the gap fill is processed on its own, so the message closing the range lands in its own read")
                    .isEqualTo(4L));

            // the message the range actually ended on, retransmitted as such
            peer.send(peer.message(MessageTypes.Heartbeat, 4)
                    .set(CoreFields.POSS_DUP_FLAG, "Y")
                    .set(CoreFields.ORIG_SENDING_TIME, RawFixSocketClient.utcTimestamp(Instant.now().minusSeconds(1))));

            // the gap is closed for real this time, so what was held on top of it is replayed - and being a
            // TestRequest, its replay is observable on the wire
            assertThat(FixMessageFields.valuesOf(
                    peer.readMessageWithField(TestReqID.get(), "held-on-top-of-the-gap", Duration.ofSeconds(10)),
                    CoreFields.MESSAGE_TYPE))
                    .as("the message held on top of the gap must be delivered once the range is complete")
                    .containsExactly(MessageTypes.Heartbeat.code());
            await().untilAsserted(() -> assertThat(acceptorMessagesStore.getIncomingSeqNum())
                    .as("the replayed message consumed its own MsgSeqNum")
                    .isEqualTo(6L));
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }

    /**
     * A gap found while a ResendRequest(35=2) is already outstanding: it is queued without a request of its own, so
     * the recovery that closes the first gap runs into it and has to ask for it. Nothing else will - a replay is only
     * ever driven by a request completing - so the messages waiting behind it would otherwise never be delivered.
     */
    @Test
    void testGapFoundWhileRecoveringIsAskedForOnceTheFirstRangeIsComplete() throws Exception {
        startFixAcceptor();

        FixSessionId initiator = getInitiatorFixSessionSettings().build().getFixSessionId();
        try (RawFixSocketClient.Session peer = RawFixSocketClient.connect(acceptorPort, initiator.getFixVersion(),
                initiator.getSenderCompID().getValue(), initiator.getTargetCompID().getValue(), Duration.ofSeconds(10))) {
            peer.send(peer.message(MessageTypes.Logon, 1).set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));
            peer.readMessageOfType(MessageTypes.Logon, Duration.ofSeconds(10));

            // NextNumIn is 2, so a message at 5 opens a gap over 2 to 4 and is held on top of it
            peer.send(peer.message(MessageTypes.TestRequest, 5).set(TestReqID.get(), "held-first"));
            assertThat(FixMessageFields.valuesOf(
                    peer.readMessageOfType(MessageTypes.ResendRequest, Duration.ofSeconds(10)), CoreFields.END_SEQ_NO))
                    .containsExactly("4");

            // a second gap, over 6, opening while that request is still outstanding: it is queued behind the first
            // one and no ResendRequest goes out for it, section 4.5 having one recovery run at a time
            peer.send(peer.message(MessageTypes.TestRequest, 7).set(TestReqID.get(), "held-second"));

            // the first range is answered in full, which delivers the message held at 5 and brings the session up
            // against the gap over 6
            peer.send(peer.message(MessageTypes.SequenceReset, 2)
                    .set(GapFillFlag.get(), "Y").set(NewSeqNo.get(), "5"));
            peer.readMessageWithField(TestReqID.get(), "held-first", Duration.ofSeconds(10));

            String secondRequest = peer.readMessageOfType(MessageTypes.ResendRequest, Duration.ofSeconds(10));
            assertThat(FixMessageFields.valuesOf(secondRequest, CoreFields.BEGIN_SEQ_NO))
                    .as("the gap left behind the messages held while recovering has to be asked for")
                    .containsExactly("6");
            assertThat(FixMessageFields.valuesOf(secondRequest, CoreFields.END_SEQ_NO)).containsExactly("6");

            // and answering it delivers what was waiting behind it
            peer.send(peer.message(MessageTypes.SequenceReset, 6)
                    .set(GapFillFlag.get(), "Y").set(NewSeqNo.get(), "7"));
            assertThat(FixMessageFields.valuesOf(
                    peer.readMessageWithField(TestReqID.get(), "held-second", Duration.ofSeconds(10)),
                    CoreFields.MESSAGE_TYPE))
                    .as("the message held behind the second gap must be delivered once that gap is filled too")
                    .containsExactly(MessageTypes.Heartbeat.code());
            await().untilAsserted(() -> assertThat(acceptorMessagesStore.getIncomingSeqNum()).isEqualTo(8L));
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }

    /**
     * A peer whose answer runs past the end of the gap, over a message it had already delivered and which is sitting
     * in the queue waiting for that gap to close. The session layer is right to move on - the peer accounted for that
     * MsgSeqNum(34) and expects the next one - but the message is dropped without ever reaching the application, so
     * it has to be said out loud rather than left to be guessed from a hole in the application's own numbering.
     */
    @Test
    void testMessageHeldOverAGapTheAnswerRanPastIsDroppedAudibly() throws Exception {
        startFixAcceptor();

        FixSessionId initiator = getInitiatorFixSessionSettings().build().getFixSessionId();
        try (RawFixSocketClient.Session peer = RawFixSocketClient.connect(acceptorPort, initiator.getFixVersion(),
                initiator.getSenderCompID().getValue(), initiator.getTargetCompID().getValue(), Duration.ofSeconds(10))) {
            peer.send(peer.message(MessageTypes.Logon, 1).set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));
            peer.readMessageOfType(MessageTypes.Logon, Duration.ofSeconds(10));

            // NextNumIn is 2, so a message at 5 opens a gap over 2 to 4 and is held on top of it
            peer.send(peer.message(MessageTypes.TestRequest, 5).set(TestReqID.get(), "overtaken-by-the-answer"));
            peer.readMessageOfType(MessageTypes.ResendRequest, Duration.ofSeconds(10));

            // the answer covers 2 to 5, one past the range asked for, so it accounts for the held message too
            peer.send(peer.message(MessageTypes.SequenceReset, 2)
                    .set(GapFillFlag.get(), "Y").set(NewSeqNo.get(), "6"));

            await().untilAsserted(() -> assertThat(acceptorMessagesStore.getIncomingSeqNum()).isEqualTo(6L));
            await().untilAsserted(() -> assertThat(acceptorLogger.getEvents())
                    .as("a message the peer delivered and the application never saw cannot leave silently")
                    .anyMatch(event -> event.contains("Dropping the message held with MsgSeqNum 5")));
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }

    /**
     * A ResendRequest(35=2) whose answer never comes. Heartbeats keep both ends believing the session is healthy
     * while nothing held on top of the gap is delivered and nothing outgoing goes out, so the request is asked for
     * once more and then given up on rather than left waiting for good.
     */
    @Test
    void testUnansweredResendRequestIsAskedForAgainThenGivenUpOn() throws Exception {
        setupAcceptorSessionSettings(s -> s.resendRequestResponseTimeout(Duration.ofSeconds(1)).build());
        startFixAcceptor();

        FixSessionId initiator = getInitiatorFixSessionSettings().build().getFixSessionId();
        try (RawFixSocketClient.Session peer = RawFixSocketClient.connect(acceptorPort, initiator.getFixVersion(),
                initiator.getSenderCompID().getValue(), initiator.getTargetCompID().getValue(), Duration.ofSeconds(10))) {
            peer.send(peer.message(MessageTypes.Logon, 1).set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));
            peer.readMessageOfType(MessageTypes.Logon, Duration.ofSeconds(10));

            peer.send(peer.message(MessageTypes.TestRequest, 5).set(TestReqID.get(), "never-recovered"));
            assertThat(FixMessageFields.valuesOf(
                    peer.readMessageOfType(MessageTypes.ResendRequest, Duration.ofSeconds(10)), CoreFields.BEGIN_SEQ_NO))
                    .containsExactly("2");

            // nothing is answered, so the whole range is asked for a second time
            String secondRequest = peer.readMessageOfType(MessageTypes.ResendRequest, Duration.ofSeconds(10));
            assertThat(FixMessageFields.valuesOf(secondRequest, CoreFields.BEGIN_SEQ_NO))
                    .as("nothing was recovered, so the range asked for again is the one still missing")
                    .containsExactly("2");
            assertThat(FixMessageFields.valuesOf(secondRequest, CoreFields.END_SEQ_NO)).containsExactly("4");

            // and still nothing: the session is taken down rather than left stalled with heartbeats flowing
            assertThat(FixMessageFields.valuesOf(
                    peer.readMessageOfType(MessageTypes.Logout, Duration.ofSeconds(10)), CoreFields.TEXT))
                    .as("the logout says which retransmission was given up on")
                    .allSatisfy(text -> assertThat(text).contains("No answer to the ResendRequest from 2 to 4"));
        }
    }

    /**
     * A Logon(35=A) retransmitted as part of the answer to a ResendRequest(35=2). Nothing this engine sends, a Logon
     * being gap filled rather than replayed, but nothing the peer is forbidden either - and its MsgSeqNum(34) has to
     * be accounted for like any other message of the range, or everything after it is out of sequence.
     */
    @Test
    void testLogonRetransmittedInsideAResendRequestRangeConsumesItsSequenceNumber() throws Exception {
        startFixAcceptor();

        FixSessionId initiator = getInitiatorFixSessionSettings().build().getFixSessionId();
        try (RawFixSocketClient.Session peer = RawFixSocketClient.connect(acceptorPort, initiator.getFixVersion(),
                initiator.getSenderCompID().getValue(), initiator.getTargetCompID().getValue(), Duration.ofSeconds(10))) {
            peer.send(peer.message(MessageTypes.Logon, 1).set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));
            peer.readMessageOfType(MessageTypes.Logon, Duration.ofSeconds(10));

            // NextNumIn is 2, so a message at 5 opens a gap over 2 to 4 and is held on top of it
            peer.send(peer.message(MessageTypes.TestRequest, 5).set(TestReqID.get(), "held-behind-a-logon"));
            peer.readMessageOfType(MessageTypes.ResendRequest, Duration.ofSeconds(10));

            // the peer answers the range with a Logon of its own at 2, then covers what is left
            peer.send(peer.message(MessageTypes.Logon, 2)
                    .set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5")
                    .set(CoreFields.POSS_DUP_FLAG, "Y")
                    .set(CoreFields.ORIG_SENDING_TIME, RawFixSocketClient.utcTimestamp(Instant.now().minusSeconds(1))));
            peer.send(peer.message(MessageTypes.SequenceReset, 3)
                    .set(GapFillFlag.get(), "Y").set(NewSeqNo.get(), "5"));

            // the retransmitted Logon consumed 2, the gap fill covered 3 and 4, so the range is complete and what was
            // held on top of it is delivered
            assertThat(FixMessageFields.valuesOf(
                    peer.readMessageWithField(TestReqID.get(), "held-behind-a-logon", Duration.ofSeconds(10)),
                    CoreFields.MESSAGE_TYPE))
                    .as("the message held on top of the gap must be delivered once the range is complete")
                    .containsExactly(MessageTypes.Heartbeat.code());
            await().untilAsserted(() -> assertThat(acceptorMessagesStore.getIncomingSeqNum())
                    .as("the replayed message consumed its own MsgSeqNum")
                    .isEqualTo(6L));
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }

    /**
     * The other half of the open ended answer, and the half a live pairing only reaches on a read boundary: the gap
     * fill that ends it arriving once the queue on top of the gap has been replayed, so that this side is already
     * past the sequence number it covers.
     * <p>
     * Section 4.8.1 has a MsgSeqNum(34) below NextNumIn without PossDupFlag(43)=Y terminate the session, which is
     * what the peer's answer looks like here, and what a session asking a closed range would rightly do with it - the
     * peer would have no business sending it. This one asked for everything from BeginSeqNo(7) onwards, so it must
     * live with being answered past the end of its gap. Driven from a raw socket rather than against another engine:
     * whether the tail lands in the same read as the rest of the answer is up to TCP, and this is the ordering that
     * would otherwise be a coin toss.
     */
    @Test
    void testOpenEndedResendRequestToleratesTheTailArrivingAfterTheGapIsClosed() throws Exception {
        setupAcceptorSessionSettings(s -> s.resendRequestRange(ResendRequestRange.OPEN_ENDED).build());
        startFixAcceptor();

        FixSessionId initiator = getInitiatorFixSessionSettings().build().getFixSessionId();
        try (RawFixSocketClient.Session peer = RawFixSocketClient.connect(acceptorPort, initiator.getFixVersion(),
                initiator.getSenderCompID().getValue(), initiator.getTargetCompID().getValue(), Duration.ofSeconds(10))) {
            peer.send(peer.message(MessageTypes.Logon, 1).set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));
            peer.readMessageOfType(MessageTypes.Logon, Duration.ofSeconds(10));

            // NextNumIn is 2, so a message at 5 opens a gap over 2 to 4 and is held on top of it
            peer.send(peer.message(MessageTypes.TestRequest, 5).set(TestReqID.get(), "open-ended-tail"));

            assertThat(FixMessageFields.valuesOf(
                    peer.readMessageOfType(MessageTypes.ResendRequest, Duration.ofSeconds(10)), 16))
                    .as("the acceptor asks open ended, which is what invites an answer past the end of the gap")
                    .containsExactly("0");

            // answer the gap itself, and no more: the acceptor closes the request at 4 and replays the message it
            // held at 5, which is what takes NextNumIn past the tail still to come
            peer.send(peer.message(MessageTypes.SequenceReset, 2)
                    .set(GapFillFlag.get(), "Y").set(NewSeqNo.get(), "5"));
            await().untilAsserted(() -> assertThat(acceptorMessagesStore.getIncomingSeqNum())
                    .as("the held message at 5 has been replayed, so the gap fill below is now behind NextNumIn")
                    .isEqualTo(6L));

            // the tail: the peer accounting for message 5, which it had already delivered, as part of the open ended
            // range it was asked for
            peer.send(peer.message(MessageTypes.SequenceReset, 5)
                    .set(GapFillFlag.get(), "Y").set(NewSeqNo.get(), "6"));

            // it says nothing this side has not worked out already, so it changes nothing and takes nothing down
            peer.send(peer.message(MessageTypes.TestRequest, 6).set(TestReqID.get(), "still-alive"));
            assertThat(FixMessageFields.valuesOf(
                    peer.readMessageWithField(TestReqID.get(), "still-alive", Duration.ofSeconds(10)),
                    CoreFields.MESSAGE_TYPE))
                    .as("the session answers the TestRequest that follows the tail, so it survived it")
                    .containsExactly(MessageTypes.Heartbeat.code());
            // awaited rather than read on the spot: the Heartbeat above goes out from the TestRequest decoder's
            // onDecoded, which FixSessionImpl.onMessageDecoded runs before it stores what comes next, so the answer can
            // reach this thread while NextNumIn is still the value it had
            await().untilAsserted(() -> assertThat(acceptorMessagesStore.getIncomingSeqNum()).isEqualTo(7L));
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }

    /**
     * The shape of a gap fill answering a ResendRequest(35=2): PossDupFlag(43)=Y and an OrigSendingTime(122), the
     * marking that section 4.8.1 makes the difference between a message a peer may ignore and one it must terminate
     * the session over.
     * <p>
     * It matters because the peer decides the range: asked for an open ended one - what QuickFIX/J asks for by
     * default - the answer runs past the end of the gap the peer was recovering, so the gap fill closing it can
     * arrive once the peer has moved past the sequence numbers it covers. Marked, that is a duplicate it discards;
     * unmarked, it is a MsgSeqNum(34) too low without PossDupFlag(43)=Y and the peer logs us out.
     * <p>
     * Driven from a raw socket because it is the message this session <em>sends</em> that is under test, so the peer
     * has to be one that asks for a range of the test's choosing and then does nothing with the answer.
     */
    @Test
    void testGapFillAnsweringAResendRequestIsMarkedAsARetransmission() throws Exception {
        startFixAcceptor();

        FixSessionId initiator = getInitiatorFixSessionSettings().build().getFixSessionId();
        try (RawFixSocketClient.Session peer = RawFixSocketClient.connect(acceptorPort, initiator.getFixVersion(),
                initiator.getSenderCompID().getValue(), initiator.getTargetCompID().getValue(), Duration.ofSeconds(10))) {
            peer.send(peer.message(MessageTypes.Logon, 1).set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));
            peer.readMessageOfType(MessageTypes.Logon, Duration.ofSeconds(10));

            // everything from the acceptor's Logon onwards, open ended. A Logon is never retransmitted, so the whole
            // answer is the gap fill covering it
            peer.send(peer.message(MessageTypes.ResendRequest, 2)
                    .set(CoreFields.BEGIN_SEQ_NO, "1").set(CoreFields.END_SEQ_NO, "0"));

            String gapFill = peer.readMessageOfType(MessageTypes.SequenceReset, Duration.ofSeconds(10));
            assertThat(FixMessageFields.valuesOf(gapFill, CoreFields.GAP_FILL))
                    .as("the Logon is accounted for by a gap fill rather than retransmitted")
                    .containsExactly("Y");
            assertThat(FixMessageFields.valuesOf(gapFill, CoreFields.POSS_DUP_FLAG))
                    .as("a gap fill is part of a retransmission and has to say so")
                    .containsExactly("Y");
            // string order is time order for a UTCTimestamp of one precision, and the check both ends make of a
            // PossDup message is that its OrigSendingTime(122) does not run ahead of its SendingTime(52)
            assertThat(FixMessageFields.valuesOf(gapFill, CoreFields.ORIG_SENDING_TIME))
                    .as("PossDupFlag(43)=Y without OrigSendingTime(122) is rejected as a missing required field")
                    .hasSize(1)
                    .allSatisfy(origSendingTime -> assertThat(origSendingTime)
                            .isLessThanOrEqualTo(FixMessageFields.valuesOf(gapFill, CoreFields.SENDING_TIME).get(0)));
        }
    }

    private void assertMessagesSendReceiveStillWorkAfterResync() {
        fixInitiatorSession.send(encodeTestMessage(100), null);
        fixAcceptorSession.send(encodeTestMessage(101), null);

        assertMessageReceived(decodedAcceptorMessages, EmailThreadID.get(), "test thread id 100");
        assertMessageReceived(decodedInitiatorMessages, EmailThreadID.get(), "test thread id 101");

        clearApplicationsInvocations();

        await().untilAsserted(() -> verify(fixInitiatorApplication).onHeartbeat(any(FixSession.class), any(UTCTime.class)));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onHeartbeat(any(FixSession.class), any(UTCTime.class)));
    }
}
