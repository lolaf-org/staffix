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
package org.lolaf.staffix.conformance.test;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.fix44.encoders.TradingSessionStatusRequestEncoder;
import org.lolaf.staffix.fix44.fields.*;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Scenario 16 - Queue outgoing messages (applicable to all FIX systems). Mandatory.
 * <p>
 * The acceptor is the system under test and the peer is driven over a raw socket, which is what lets the test drop
 * the connection abruptly - no Logout, the way a real disconnection happens - and come back on a second one.
 * <p>
 * Of the two queuing approaches the scenario allows, staffix implements the second: a message handed to a
 * disconnected session is still encoded and consumes its MsgSeqNum(34), then goes to the message store, so the
 * outgoing sequence runs on while the session is down. The peer discovers the resulting gap when it reconnects and
 * asks for the range back.
 * <p>
 * Case B drives the synchronization with a ResendRequest(35=2), which is what step 4.b.iii of the scenario
 * prescribes. Case C covers the same recovery through NextExpectedMsgSeqNum(789) instead - the extended feature of
 * section 4.4.1, where the queued messages come back off the Logon(35=A) alone and no ResendRequest is ever sent.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario16 extends AbstractScenario {

    private static final String QUEUED_MESSAGE_A = "Scenario16-A";
    private static final String QUEUED_MESSAGE_B = "Scenario16-B";

    private void sendApplicationMessage(String id) {
        fixAcceptorSession.send(fixAcceptorSession.newEncoder(TradingSessionStatusRequestEncoder.class)
                .begin().setTradSesReqID(id)
                .setSubscriptionRequestType(SubscriptionRequestType.SubscriptionRequestTypeValues.SNAPSHOT), null);
    }

    /**
     * Brings the acceptor to the condition both cases start from: a session that was established once, whose peer
     * then vanished, and which has been handed two application messages while it had nowhere to send them. The
     * acceptor's outgoing sequence ends up at 4 - 1 went to the first Logon reply, 2 and 3 to the queued messages.
     */
    private void queueTwoMessagesWhileDisconnected() throws Exception {
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            await().untilAsserted(() -> assertThat(fixAcceptorSession).isNotNull());
            assertThat(session.isClosedByPeer(java.time.Duration.ofMillis(200))).isFalse();
        }
        // the socket was closed abruptly and without a Logout, so the acceptor has to notice the disconnection
        // before it can be handed anything - and before a second connection would be turned away as a MultipleLogon
        await().untilAsserted(() -> assertThat(fixAcceptorSession.isConnected()).isFalse());

        sendApplicationMessage(QUEUED_MESSAGE_A);
        sendApplicationMessage(QUEUED_MESSAGE_B);
        await().untilAsserted(() -> assertThat(acceptorMessageStore.getOutgoingSeqNum()).isEqualTo(4));
    }

    /**
     * Checks one message came back as a retransmission of the one that was queued: same MsgSeqNum(34) as when it
     * was queued, flagged as a possible duplicate, and carrying both timestamps required of a resend.
     */
    private void assertResentAsQueued(RawFixSocketClient.Session session, String tradSesReqId, String expectedSeqNum) throws Exception {
        String resent = session.readMessageWithField(TradSesReqID.get(), tradSesReqId, DEFAULT_TIMEOUT);
        assertThatFixMessage(resent)
                .containsFieldWithValue(MsgSeqNum.get(), expectedSeqNum)
                .containsFieldWithValue(PossDupFlag.get(), "Y")
                .containsField(OrigSendingTime.get());
        // the scenario's note: SendingTime(52) is the time the message is sent, the time it was queued having moved
        // to OrigSendingTime(122). Both are UTC timestamps of the same fixed width, so they compare as text.
        assertThat(RawFixSocketClient.fieldValue(resent, SendingTime.get()))
                .isGreaterThan(RawFixSocketClient.fieldValue(resent, OrigSendingTime.get()));
    }

    @Test
    void A_message_to_send_or_queue_while_disconnected() throws Exception {
        // Condition/Stimulus: Message to send/queue while disconnected.
        // Expected Behavior: Queue outgoing messages. Note there are two valid approaches:
        //   1. Queue without regard to MsgSeqNum(34).
        //        a. Store data for messages.
        //   2. Queue each message with the next MsgSeqNum(34) value.
        //        b. Store data for messages in such a manner as to use and "consume" the next MsgSeqNum(34).
        //   Note: SendingTime(52) must contain the time the message is sent, not the time the message was queued.
        queueTwoMessagesWhileDisconnected();

        // approach 2: each queued message consumed the next MsgSeqNum as it was queued, 2 then 3
        assertThat(acceptorMessageStore.getOutgoingSeqNum()).isEqualTo(4);

        // the application is told neither of them reached the wire
        verify(fixAcceptorApplication, times(2)).onMessageSendingFailure(any(FixSession.class), any(MessageType.class),
                any(ByteBuffer.class), any());
        // and they are kept for the reconnection rather than dropped
        assertThat(acceptorLogger.getEvents())
                .anyMatch(event -> event.contains("will be eventually resent on remote session reconnection"));

        // queueing does not bring the session back by itself
        assertThat(fixAcceptorSession.isConnected()).isFalse();
    }

    @Test
    void B_reconnect_with_queued_messages() throws Exception {
        // Condition/Stimulus: Reconnect with queued messages.
        // Expected Behavior:
        //   1. Complete logon process (establish transport layer connection and exchange Logon(35=A) messages).
        //   2. Synchronize the FIX session, if required (inbound Logon(35=A) message MsgSeqNum(34) > NextNumIn).
        //   3. Recommended short delay, or use TestRequest(35=1) or Heartbeat(35=0) message to verify FIX session
        //      synchronization completed.
        //   4. Note there are two valid queuing approaches.
        //        a. Queue without regard to MsgSeqNum(34):
        //             i.   Send queued messages with new MsgSeqNum(34) values (greater than Logon(35=A) message's
        //                  MsgSeqNum(34)).
        //        b. Queue each message with the next MsgSeqNum(34) value:
        //             ii.  (Note: Logon(35=A) message's MsgSeqNum(34) will be greater than the queued messages'
        //                  MsgSeqNum(34)).
        //             iii. Counterparty will issue ResendRequest(35=2) requesting the range of missed messages.
        //             iv.  Resend each queued message with PossDupFlag(43) set to Y.
        //   Note: SendingTime(52) must contain the time the message is sent, not the time the message was queued.

        // without this the resend is refused message by message and turns into a SequenceReset gap fill
        when(fixAcceptorApplication.onResendRequest(any(FixSession.class), any(), any())).thenReturn(true);

        queueTwoMessagesWhileDisconnected();

        try (RawFixSocketClient.Session session = rawInitiatorConnect()) {
            // 1. complete the logon process. rawInitiatorLogon() cannot serve here: it hardcodes MsgSeqNum 1, and
            // the acceptor kept its sequence across the disconnection so it now expects 2
            session.send(session.message(MessageTypes.Logon, 2)
                    .set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));

            // 2. the Logon that comes back sits at MsgSeqNum 4, past the 2 and 3 the queued messages took: that is
            // 4.b.ii, and it is what reveals the gap to us. It carries no NextExpectedMsgSeqNum(789) because we sent
            // none, so synchronizing the session is ours to do
            assertThatFixMessage(session.readMessageOfType(MessageTypes.Logon, DEFAULT_TIMEOUT))
                    .containsFieldWithValue(MsgSeqNum.get(), "4")
                    .doesNotContainField(NextExpectedMsgSeqNum.get());

            // 3. iii. ask for the range we missed
            session.send(session.message(MessageTypes.ResendRequest, 3)
                    .set(BeginSeqNo.get(), "2").set(EndSeqNo.get(), "3"));

            // 4. iv. both queued messages come back, at the MsgSeqNum they were queued with, as possible duplicates
            assertResentAsQueued(session, QUEUED_MESSAGE_A, "2");
            assertResentAsQueued(session, QUEUED_MESSAGE_B, "3");

            // the resends reused their original sequence numbers instead of consuming new ones: the only number
            // spent on this connection so far is the 4 of the Logon reply
            assertThat(acceptorMessageStore.getOutgoingSeqNum()).isEqualTo(5);

            // 3. and the session is verifiably synchronized afterwards
            session.send(session.message(MessageTypes.TestRequest, 4).set(TestReqID.get(), "Scenario16-live"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario16-live", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }

    @Test
    void C_reconnect_with_queued_messages_synchronized_with_next_expected_msg_seq_num() throws Exception {
        // Same condition and stimulus as B - reconnect with queued messages - recovered through the extended feature
        // of section 4.4.1 rather than through a ResendRequest(35=2):
        //   "the time required to resume a FIX session over a new FIX connection can be reduced by alerting the peer
        //   to the next message that is expected using NextExpectedMsgSeqNum(789) in the Logon(35=A) message. Any
        //   missing messages can be retransmitted by the peer without requiring to use ResendRequest(35=2) messages."
        when(fixAcceptorApplication.onResendRequest(any(FixSession.class), any(), any())).thenReturn(true);

        queueTwoMessagesWhileDisconnected();

        try (RawFixSocketClient.Session session = rawInitiatorConnect()) {
            // this time the Logon says what we expect next: 2, the first of the two messages queued while we were away
            session.send(session.message(MessageTypes.Logon, 2)
                    .set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5")
                    .set(NextExpectedMsgSeqNum.get(), "2"));

            // the acknowledgement still sits at MsgSeqNum 4, and carries what the acceptor expects from us in turn
            assertThatFixMessage(session.readMessageOfType(MessageTypes.Logon, DEFAULT_TIMEOUT))
                    .containsFieldWithValue(MsgSeqNum.get(), "4")
                    .containsFieldWithValue(NextExpectedMsgSeqNum.get(), "3");

            // both queued messages come back off the Logon alone, at the MsgSeqNum they were queued with
            assertResentAsQueued(session, QUEUED_MESSAGE_A, "2");
            assertResentAsQueued(session, QUEUED_MESSAGE_B, "3");

            // nothing was asked for: no ResendRequest(35=2) was needed to get them back
            assertThat(acceptorLogger.getOutgoingMessages())
                    .noneMatch(message -> message.contains(CoreFields.FIELD_SEPARATOR + "35=2" + CoreFields.FIELD_SEPARATOR));

            // the retransmissions reused their original sequence numbers: only the 4 of the Logon reply was spent
            assertThat(acceptorMessageStore.getOutgoingSeqNum()).isEqualTo(5);

            // and the session is verifiably synchronized afterwards
            session.send(session.message(MessageTypes.TestRequest, 3).set(TestReqID.get(), "Scenario16-live-789"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario16-live-789", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }
}
