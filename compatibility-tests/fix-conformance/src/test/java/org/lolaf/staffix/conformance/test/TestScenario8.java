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
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.fix44.encoders.TradingSessionStatusRequestEncoder;
import org.lolaf.staffix.fix44.fields.*;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Scenario 8 - Receive Resend Request message (applicable to all FIX systems). Mandatory.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario8 extends AbstractScenario {

    /**
     * Makes the acceptor send one application message, identified by its TradSesReqID(335).
     */
    private void sendApplicationMessage(String id) {
        fixAcceptorSession.send(fixAcceptorSession.newEncoder(TradingSessionStatusRequestEncoder.class)
                .begin().setTradSesReqID(id)
                .setSubscriptionRequestType(SubscriptionRequestType.SubscriptionRequestTypeValues.SNAPSHOT), null);
    }

    /**
     * Drives the acceptor into a known outgoing history and returns the raw session to drive the ResendRequest with.
     * After the Logon acknowledgement (MsgSeqNum 1) the acceptor sends, in order:
     * <ul>
     *     <li>2: an application message,</li>
     *     <li>3: a Heartbeat, an admin message which must never be retransmitted,</li>
     *     <li>4: an application message.</li>
     * </ul>
     */
    private RawFixSocketClient.Session acceptorWithApplicationAndAdminHistory() throws Exception {
        // without this the application declines every retransmission and the engine legitimately gap fills them all
        when(fixAcceptorApplication.onResendRequest(any(FixSession.class), any(), any())).thenReturn(true);
        fixAcceptor.start();
        RawFixSocketClient.Session session = rawInitiatorLogon();
        await().untilAsserted(() -> assertThat(fixAcceptorSession).isNotNull());

        sendApplicationMessage("Scenario8-first");
        assertThatFixMessage(session.readMessageWithField(TradSesReqID.get(), "Scenario8-first", DEFAULT_TIMEOUT))
                .containsFieldWithValue(MsgSeqNum.get(), "2");

        // a TestRequest makes the acceptor answer with a Heartbeat, giving it an admin message at MsgSeqNum 3
        session.send(session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario8-gap"));
        assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario8-gap", DEFAULT_TIMEOUT))
                .hasMsgType(MessageTypes.Heartbeat)
                .containsFieldWithValue(MsgSeqNum.get(), "3");

        sendApplicationMessage("Scenario8-second");
        assertThatFixMessage(session.readMessageWithField(TradSesReqID.get(), "Scenario8-second", DEFAULT_TIMEOUT))
                .containsFieldWithValue(MsgSeqNum.get(), "4");
        return session;
    }

    @Test
    void valid_resend_request() throws Exception {
        // Condition/Stimulus: Valid ResendRequest(35=2).
        // Expected Behavior: Respond with application layer messages and SequenceReset(35=4) with GapFillFlag(123)=Y
        //   for session layer messages in request range according to message recovery rules.
        try (RawFixSocketClient.Session session = acceptorWithApplicationAndAdminHistory()) {
            // ask for everything the acceptor sent after its Logon acknowledgement
            session.send(session.message(MessageTypes.ResendRequest, 3).set(7, "2").set(16, "4"));

            List<String> resent = List.of(session.readMessage(DEFAULT_TIMEOUT),
                    session.readMessage(DEFAULT_TIMEOUT), session.readMessage(DEFAULT_TIMEOUT));

            // the application message keeps its original MsgSeqNum and is flagged as a possible duplicate (4.8.4)
            assertThatFixMessage(resent.get(0))
                    .containsFieldWithValue(MsgSeqNum.get(), "2")
                    .containsFieldWithValue(TradSesReqID.get(), "Scenario8-first")
                    .containsFieldWithValue(PossDupFlag.get(), "Y");

            // the Heartbeat is a session layer message which must not be retransmitted: it is skipped with a
            // SequenceReset(35=4) GapFillFlag(123)=Y whose NewSeqNo(36) points at the next message to come (4.8.5)
            assertThatFixMessage(resent.get(1))
                    .hasMsgType(MessageTypes.SequenceReset)
                    .containsFieldWithValue(MsgSeqNum.get(), "3")
                    .containsFieldWithValue(GapFillFlag.get(), "Y")
                    .containsFieldWithValue(NewSeqNo.get(), "4");

            assertThatFixMessage(resent.get(2))
                    .containsFieldWithValue(MsgSeqNum.get(), "4")
                    .containsFieldWithValue(TradSesReqID.get(), "Scenario8-second")
                    .containsFieldWithValue(PossDupFlag.get(), "Y");
        }
    }

    @Test
    void resend_request_for_a_bounded_range_stops_at_endseqno() throws Exception {
        // Beyond the single conformance case: section 4.8.2 allows requesting "a single message, a range of messages
        // or all messages subsequent to a particular message", and 4.8.5 says the resender retransmits "messages
        // requested by the peer". A bounded request must therefore not replay past its EndSeqNo(16).
        try (RawFixSocketClient.Session session = acceptorWithApplicationAndAdminHistory()) {
            // only MsgSeqNum 2 to 3 is asked for, while the acceptor has sent up to MsgSeqNum 4
            session.send(session.message(MessageTypes.ResendRequest, 3).set(7, "2").set(16, "3"));

            assertThatFixMessage(session.readMessage(DEFAULT_TIMEOUT))
                    .containsFieldWithValue(MsgSeqNum.get(), "2")
                    .containsFieldWithValue(TradSesReqID.get(), "Scenario8-first")
                    .containsFieldWithValue(PossDupFlag.get(), "Y");

            // MsgSeqNum 3 is the Heartbeat, gap filled up to 4 which is the first message NOT requested
            assertThatFixMessage(session.readMessage(DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.SequenceReset)
                    .containsFieldWithValue(MsgSeqNum.get(), "3")
                    .containsFieldWithValue(GapFillFlag.get(), "Y")
                    .containsFieldWithValue(NewSeqNo.get(), "4");

            // nothing beyond MsgSeqNum 3 was requested, so the second application message must not be replayed
            assertThatFixMessage(session.readMessage(DEFAULT_TIMEOUT))
                    .doesNotContainFieldWithValue(TradSesReqID.get(), "Scenario8-second");
        }
    }

    @Test
    void resend_request_for_a_single_message_is_honoured() throws Exception {
        // Section 4.8.2: "The ResendRequest(35=2) may be used to request retransmission of a single message", which
        // is expressed with BeginSeqNo(7) equal to EndSeqNo(16).
        try (RawFixSocketClient.Session session = acceptorWithApplicationAndAdminHistory()) {
            session.send(session.message(MessageTypes.ResendRequest, 3).set(7, "4").set(16, "4"));

            assertThatFixMessage(session.readMessage(DEFAULT_TIMEOUT))
                    .containsFieldWithValue(MsgSeqNum.get(), "4")
                    .containsFieldWithValue(TradSesReqID.get(), "Scenario8-second")
                    .containsFieldWithValue(PossDupFlag.get(), "Y")
                    .doesNotHaveMsgType(MessageTypes.Reject);
        }
    }

    /**
     * Asserts that everything the acceptor sent after its Logon acknowledgement comes back: the two application
     * messages, and a gap fill in place of the Heartbeat between them.
     */
    private void assertWholeHistoryRetransmitted(RawFixSocketClient.Session session) throws Exception {
        assertThatFixMessage(session.readMessage(DEFAULT_TIMEOUT))
                .containsFieldWithValue(MsgSeqNum.get(), "2")
                .containsFieldWithValue(TradSesReqID.get(), "Scenario8-first")
                .containsFieldWithValue(PossDupFlag.get(), "Y");
        assertThatFixMessage(session.readMessage(DEFAULT_TIMEOUT))
                .hasMsgType(MessageTypes.SequenceReset)
                .containsFieldWithValue(MsgSeqNum.get(), "3")
                .containsFieldWithValue(GapFillFlag.get(), "Y")
                .containsFieldWithValue(NewSeqNo.get(), "4");
        assertThatFixMessage(session.readMessage(DEFAULT_TIMEOUT))
                .containsFieldWithValue(MsgSeqNum.get(), "4")
                .containsFieldWithValue(TradSesReqID.get(), "Scenario8-second")
                .containsFieldWithValue(PossDupFlag.get(), "Y");
    }

    @Test
    void resend_request_open_ended_with_endseqno_zero() throws Exception {
        // Section 4.8.2 allows requesting "all messages subsequent to a particular message", which FIX 4.2 and later
        // spell with EndSeqNo(16) = 0. Everything from BeginSeqNo(7) on has to come back, capped at what was sent.
        try (RawFixSocketClient.Session session = acceptorWithApplicationAndAdminHistory()) {
            session.send(session.message(MessageTypes.ResendRequest, 3).set(7, "2").set(16, "0"));

            assertWholeHistoryRetransmitted(session);
        }
    }

    @Test
    void resend_request_open_ended_with_endseqno_999999() throws Exception {
        // The same open ended request in its pre-FIX 4.2 spelling, which the engine treats identically.
        try (RawFixSocketClient.Session session = acceptorWithApplicationAndAdminHistory()) {
            session.send(session.message(MessageTypes.ResendRequest, 3).set(7, "2").set(16, "999999"));

            assertWholeHistoryRetransmitted(session);
        }
    }

    @Test
    void resend_request_beginning_past_the_last_message_sent() throws Exception {
        // Nothing in that range was ever sent - the acceptor's last MsgSeqNum(34) is 4 - so there is nothing to
        // retransmit. The range must still be accounted for, which section 4.8.5 leaves to a gap fill, so that the
        // peer ends up expecting the message right after it rather than waiting forever.
        try (RawFixSocketClient.Session session = acceptorWithApplicationAndAdminHistory()) {
            session.send(session.message(MessageTypes.ResendRequest, 3).set(7, "5").set(16, "8"));

            assertThatFixMessage(session.readMessage(DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.SequenceReset)
                    .containsFieldWithValue(GapFillFlag.get(), "Y")
                    .containsFieldWithValue(NewSeqNo.get(), "5")
                    .doesNotHaveMsgType(MessageTypes.Reject);

            // and the session carries on
            session.send(session.message(MessageTypes.TestRequest, 4).set(TestReqID.get(), "Scenario8-past-end"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario8-past-end", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }

    @Test
    void resend_request_with_every_message_declined_by_the_application() throws Exception {
        // Section 4.8.5 leaves what to retransmit to the application. When it declines the lot, the whole requested
        // range is covered by a single SequenceReset gap fill rather than one per message, and nothing is replayed.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            await().untilAsserted(() -> assertThat(fixAcceptorSession).isNotNull());

            // declining is the default of the mock, made explicit here since it is the point of this case
            when(fixAcceptorApplication.onResendRequest(any(FixSession.class), any(), any())).thenReturn(false);

            sendApplicationMessage("Scenario8-declined-first");
            assertThatFixMessage(session.readMessageWithField(TradSesReqID.get(), "Scenario8-declined-first", DEFAULT_TIMEOUT))
                    .containsFieldWithValue(MsgSeqNum.get(), "2");
            sendApplicationMessage("Scenario8-declined-second");
            assertThatFixMessage(session.readMessageWithField(TradSesReqID.get(), "Scenario8-declined-second", DEFAULT_TIMEOUT))
                    .containsFieldWithValue(MsgSeqNum.get(), "3");

            session.send(session.message(MessageTypes.ResendRequest, 2).set(7, "2").set(16, "3"));

            // one gap fill for the pair, starting where the range does and pointing past its end
            assertThatFixMessage(session.readMessage(DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.SequenceReset)
                    .containsFieldWithValue(MsgSeqNum.get(), "2")
                    .containsFieldWithValue(GapFillFlag.get(), "Y")
                    .containsFieldWithValue(NewSeqNo.get(), "4");

            // the session is synchronized afterwards, and neither message was replayed
            session.send(session.message(MessageTypes.TestRequest, 3).set(TestReqID.get(), "Scenario8-declined-live"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario8-declined-live", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }
}
