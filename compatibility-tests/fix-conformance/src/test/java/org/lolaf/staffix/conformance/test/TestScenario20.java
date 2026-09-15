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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scenario 20 - Simultaneous Resend request test (applicable to all FIX systems). Mandatory.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario20 extends AbstractScenario {

    private void sendApplicationMessage(String id) {
        fixAcceptorSession.send(fixAcceptorSession.newEncoder(TradingSessionStatusRequestEncoder.class)
                .begin().setTradSesReqID(id)
                .setSubscriptionRequestType(SubscriptionRequestType.SubscriptionRequestTypeValues.SNAPSHOT), null);
    }

    @Test
    void receive_resend_request_while_awaiting_responses_to_own_resend_request() throws Exception {
        // Condition/Stimulus: Receive a ResendRequest(35=2) message while having sent and awaiting the complete set of
        //   responses to a ResendRequest(35=2) message of our own.
        // Expected Behavior:
        //   1. Perform the resend of the requested messages.
        //   2. Send a ResendRequest(35=2) to request the still missing messages if a gap still exists.
        when(fixAcceptorApplication.onResendRequest(any(FixSession.class), any(), any())).thenReturn(true);
        fixAcceptor.start();

        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            await().untilAsserted(() -> assertThat(fixAcceptorSession).isNotNull());

            // the acceptor sends two application messages, taking its outgoing sequence to 2 and 3
            sendApplicationMessage("Scenario20-A");
            assertThatFixMessage(session.readMessageWithField(TradSesReqID.get(), "Scenario20-A", DEFAULT_TIMEOUT))
                    .containsFieldWithValue(MsgSeqNum.get(), "2");
            sendApplicationMessage("Scenario20-B");
            assertThatFixMessage(session.readMessageWithField(TradSesReqID.get(), "Scenario20-B", DEFAULT_TIMEOUT))
                    .containsFieldWithValue(MsgSeqNum.get(), "3");

            // create a gap on the acceptor's incoming side: a message at MsgSeqNum 5 skips 2, 3, 4 so the acceptor
            // asks for them and starts awaiting its own resend
            session.send(session.message(MessageTypes.TestRequest, 5).set(TestReqID.get(), "Scenario20-gap"));
            assertThatFixMessage(session.readMessageOfType(MessageTypes.ResendRequest, DEFAULT_TIMEOUT))
                    .containsFieldWithValue(BeginSeqNo.get(), "2");

            // while the acceptor is awaiting the resend of 2..4, send it a ResendRequest of our own for its two
            // application messages. It arrives in sequence (MsgSeqNum 2), which also starts filling the acceptor's
            // own gap.
            session.send(session.message(MessageTypes.ResendRequest, 2)
                    .set(BeginSeqNo.get(), "2").set(EndSeqNo.get(), "3"));

            // 1. the acceptor performs the resend: both application messages come back as possible duplicates
            assertThatFixMessage(session.readMessageWithField(TradSesReqID.get(), "Scenario20-A", DEFAULT_TIMEOUT))
                    .containsFieldWithValue(MsgSeqNum.get(), "2")
                    .containsFieldWithValue(PossDupFlag.get(), "Y");
            assertThatFixMessage(session.readMessageWithField(TradSesReqID.get(), "Scenario20-B", DEFAULT_TIMEOUT))
                    .containsFieldWithValue(MsgSeqNum.get(), "3")
                    .containsFieldWithValue(PossDupFlag.get(), "Y");

            // 2. the acceptor's own resend request survived handling the incoming one: it is still awaiting the
            // rest of its range (3..4, since the incoming ResendRequest at MsgSeqNum 2 filled slot 2). Fill that
            // remaining gap with a SequenceReset gap fill and the acceptor completes its own recovery.
            session.send(session.message(MessageTypes.SequenceReset, 3)
                    .set(GapFillFlag.get(), "Y").set(NewSeqNo.get(), "5"));
            await().untilAsserted(() -> verify(fixAcceptorApplication)
                    .onResendRequestTerminated(any(FixSession.class), anyLong(), anyLong()));

            // the TestRequest that revealed the gap back at the start was queued rather than dropped - section 4.5
            // state table row 11 - so completing the recovery is what finally gets it processed, and answered
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario20-gap", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);

            // the session is fully resynchronized: a following in-sequence message is accepted and answered. It sits
            // at MsgSeqNum 6, the queued TestRequest having consumed the 5 it was sent with
            session.send(session.message(MessageTypes.TestRequest, 6).set(TestReqID.get(), "Scenario20-live"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario20-live", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }
}
