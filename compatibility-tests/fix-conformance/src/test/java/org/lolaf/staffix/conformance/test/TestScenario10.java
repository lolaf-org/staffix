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
import org.lolaf.staffix.fix44.fields.*;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Scenario 10 - Receive Sequence Reset (Gap Fill) (applicable to all FIX systems). Mandatory.
 * <p>
 * A SequenceReset(35=4) with GapFillFlag(123)=Y is a gap fill: unlike a hard reset it is a genuine message that is
 * subject to MsgSeqNum(34) processing, sent by a peer to skip over messages it does not retransmit while answering a
 * ResendRequest(35=2).
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario10 extends AbstractScenario {

    /**
     * Drives the acceptor into having an outstanding ResendRequest, which a gap fill only makes sense in response to.
     * After logon (NextNumIn 2) an out of sequence message at {@code triggerSeqNum} makes the acceptor ask for the
     * missing range; the ResendRequest it sends is returned.
     */
    private String makeAcceptorRequestResend(RawFixSocketClient.Session session, long triggerSeqNum) throws Exception {
        session.send(session.message(MessageTypes.TestRequest, triggerSeqNum).set(TestReqID.get(), "Scenario10-trigger"));
        return session.readMessageOfType(MessageTypes.ResendRequest, DEFAULT_TIMEOUT);
    }

    @Test
    void A_gapfill_newseqno_gt_msgseqnum_and_msgseqnum_gt_nextnumin() throws Exception {
        // Condition/Stimulus: gap fill with NewSeqNo(36) > MsgSeqNum(34) and MsgSeqNum(34) > NextNumIn.
        // Expected Behavior: issue a ResendRequest(35=2) to fill the gap between NextNumIn and the received
        //   MsgSeqNum(34).
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // NextNumIn is 2; the gap fill arrives at MsgSeqNum 3, one ahead, so 2 is still missing
            session.send(session.message(MessageTypes.SequenceReset, 3)
                    .set(GapFillFlag.get(), "Y").set(NewSeqNo.get(), "4"));

            // the acceptor asks for the still missing message 2
            assertThatFixMessage(session.readMessageOfType(MessageTypes.ResendRequest, DEFAULT_TIMEOUT))
                    .containsFieldWithValue(BeginSeqNo.get(), "2");
            assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(2);
        }
    }

    @Test
    void B_gapfill_newseqno_gt_msgseqnum_and_msgseqnum_eq_nextnumin() throws Exception {
        // Condition/Stimulus: gap fill with NewSeqNo(36) > MsgSeqNum(34) and MsgSeqNum(34) = NextNumIn.
        // Expected Behavior: set the next expected sequence number to NewSeqNo(36).
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            makeAcceptorRequestResend(session, 5);

            // the gap fill answers the ResendRequest in sequence (MsgSeqNum 2 = NextNumIn), skipping up to 5
            session.send(session.message(MessageTypes.SequenceReset, 2)
                    .set(GapFillFlag.get(), "Y").set(NewSeqNo.get(), "5"));

            // NextNumIn jumps to 5, so the message the acceptor already buffered at 5 completes the recovery
            await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isGreaterThanOrEqualTo(5));
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }

    @Test
    void C_gapfill_msgseqnum_lt_nextnumin_with_possdup() throws Exception {
        // Condition/Stimulus: gap fill with MsgSeqNum(34) < NextNumIn and PossDupFlag(43)=Y.
        // Expected Behavior: ignore the message.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // move NextNumIn to 3 with an in-sequence TestRequest
            session.send(session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario10C-bump"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario10C-bump", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);

            // a gap fill re-covering an already processed sequence, flagged as a possible duplicate, is ignored
            session.send(session.message(MessageTypes.SequenceReset, 2).set(PossDupFlag.get(), "Y")
                    .origSendingTime(Instant.now().minusSeconds(1))
                    .set(GapFillFlag.get(), "Y").set(NewSeqNo.get(), "3"));

            // the session is unaffected: a following in-sequence TestRequest is still answered and NextNumIn is 3
            session.send(session.message(MessageTypes.TestRequest, 3).set(TestReqID.get(), "Scenario10C-live"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario10C-live", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }

    @Test
    void D_gapfill_msgseqnum_lt_nextnumin_without_possdup() throws Exception {
        // Condition/Stimulus: gap fill with MsgSeqNum(34) < NextNumIn and without PossDupFlag(43)=Y.
        // Expected Behavior: send a Logout(35=5) with "MsgSeqNum too low, expecting X received Y", then disconnect.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // move NextNumIn to 3
            session.send(session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario10D-bump"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario10D-bump", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);

            // a gap fill re-covering an already processed sequence WITHOUT PossDupFlag is a fatal error
            session.send(session.message(MessageTypes.SequenceReset, 2)
                    .set(GapFillFlag.get(), "Y").set(NewSeqNo.get(), "3"));

            assertThatFixMessage(session.readMessageOfType(MessageTypes.Logout, Duration.ofSeconds(3)))
                    .hasMsgType(MessageTypes.Logout);
            assertThat(session.isClosedByPeer(DEFAULT_TIMEOUT)).isTrue();
        }
    }

    @Test
    void E_gapfill_covering_only_part_of_the_requested_range() throws Exception {
        // Beyond the scenario's four cases: section 4.8.5 lets the answer to a ResendRequest(35=2) mix retransmitted
        // messages and gap fills, so a gap fill may land inside the requested range rather than at its end. The
        // recovery has to carry on from where it leaves off instead of being taken for complete.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // NextNumIn is 2, and a message at 6 makes the acceptor ask for 2 to 5
            assertThatFixMessage(makeAcceptorRequestResend(session, 6))
                    .containsFieldWithValue(BeginSeqNo.get(), "2")
                    .containsFieldWithValue(EndSeqNo.get(), "5");

            // answer only the first half: a gap fill from 2 covering up to 4, leaving 4 and 5 still owed
            session.send(session.message(MessageTypes.SequenceReset, 2)
                    .set(GapFillFlag.get(), "Y").set(NewSeqNo.get(), "4"));
            await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(4));

            // the recovery is not over: the acceptor must still be waiting rather than treating the range as filled
            verify(fixAcceptorApplication, never()).onResendRequestTerminated(any(FixSession.class), anyLong(), anyLong());

            // finish the range with a real message at 4 and a gap fill for 5
            session.send(session.message(MessageTypes.TestRequest, 4).set(TestReqID.get(), "Scenario10E-resent"));
            session.send(session.message(MessageTypes.SequenceReset, 5)
                    .set(GapFillFlag.get(), "Y").set(NewSeqNo.get(), "6"));

            // now it completes, and the message that opened the gap at 6 is processed off the queue
            await().untilAsserted(() -> verify(fixAcceptorApplication)
                    .onResendRequestTerminated(any(FixSession.class), eq(2L), eq(5L)));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario10-trigger", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);

            // and the session runs on from there
            session.send(session.message(MessageTypes.TestRequest, 7).set(TestReqID.get(), "Scenario10E-live"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario10E-live", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }
}
