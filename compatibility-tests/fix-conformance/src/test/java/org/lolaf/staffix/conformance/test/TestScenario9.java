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
import org.lolaf.staffix.fix44.fields.*;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;

/**
 * Scenario 9 - Synchronize sequence numbers (applicable to all FIX systems). Optional.
 * <p>
 * After an application failure that lost session state, a peer may resynchronize in either of two ways, and both are
 * covered here with the acceptor as the system under test:
 * <ul>
 *     <li>resetting both sequence numbers to 1 and reconnecting with a Logon(35=A) carrying ResetSeqNumFlag(141)=Y;</li>
 *     <li>recovering its numbering from a backup that lags behind, and telling the acceptor where it has got to with
 *     NextExpectedMsgSeqNum(789) - the extended feature of section 4.4.1 - so the acceptor replays what is missing
 *     without a ResendRequest(35=2) ever being sent.</li>
 * </ul>
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario9 extends AbstractScenario {

    @Test
    void application_failure_that_caused_loss_of_session_state() throws Exception {
        // Condition/Stimulus: application failure that caused loss of session state.
        // Expected Behavior (reset variant): both peers reset NextNumIn and NextNumOut to 1 and start a new session.
        fixAcceptor.start();
        // simulate an established session: the acceptor already expects a high inbound sequence number and would
        // otherwise treat a Logon at MsgSeqNum 1 as far too low
        acceptorMessageStore.setCurrentIncomingSeqNum(10);
        acceptorMessageStore.setCurrentOutgoingSeqNum(10);

        try (RawFixSocketClient.Session session = rawInitiatorConnect()) {
            // the peer lost its state and reconnects, resetting the sequence with ResetSeqNumFlag(141)=Y at MsgSeqNum 1
            session.send(session.message(MessageTypes.Logon, 1)
                    .set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5").set(ResetSeqNumFlag.get(), "Y"));

            // the acceptor accepts the reset and acknowledges the Logon at the reset sequence number 1
            assertThatFixMessage(session.readMessageOfType(MessageTypes.Logon, DEFAULT_TIMEOUT))
                    .containsFieldWithValue(MsgSeqNum.get(), "1")
                    .containsFieldWithValue(ResetSeqNumFlag.get(), "Y");
            // the logged-in state is updated asynchronously after the acknowledgement is sent
            await().untilAsserted(() -> assertThat(fixAcceptorSession.isLoggedIn()).isTrue());

            // both sides are resynchronized at 1: the next in-sequence message from the peer (seq 2) is accepted
            session.send(session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario9-live"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario9-live", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);
            // the reset started the incoming sequence at 1: the Logon consumed 1 and the TestRequest 2
            await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(3));
        }
    }

    @Test
    void application_failure_recovered_from_a_lagging_backup() throws Exception {
        // Condition/Stimulus: application failure that caused loss of session state, recovered from a backup whose
        //   numbering lags behind what the peer actually sent.
        // Expected Behavior (NextExpectedMsgSeqNum variant, section 4.4.1): the peer states what it expects next on
        //   its Logon(35=A); the acceptor compares it with NextNumOut and, being ahead, retransmits the difference
        //   "without requiring to use ResendRequest(35=2) messages".
        fixAcceptor.start();
        // an established session: the acceptor has sent 4 messages and received 9
        acceptorMessageStore.setCurrentIncomingSeqNum(10);
        acceptorMessageStore.setCurrentOutgoingSeqNum(5);

        try (RawFixSocketClient.Session session = rawInitiatorConnect()) {
            // the peer comes back from a backup that only knows about the acceptor's first two messages, and says so
            session.send(session.message(MessageTypes.Logon, 10)
                    .set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5")
                    .set(NextExpectedMsgSeqNum.get(), "3"));

            // the acknowledgement carries what the acceptor expects from us in turn, and consumes MsgSeqNum 5
            assertThatFixMessage(session.readMessageOfType(MessageTypes.Logon, DEFAULT_TIMEOUT))
                    .containsFieldWithValue(MsgSeqNum.get(), "5")
                    .containsFieldWithValue(NextExpectedMsgSeqNum.get(), "11");

            // 3 and 4 are recovered off the Logon alone. Nothing was stored for them - the acceptor's outgoing
            // history was faked through the store above - so the range comes back as a SequenceReset gap fill, which
            // is the message recovery rule of section 4.8.5 applied to messages that cannot be retransmitted
            assertThatFixMessage(session.readMessageOfType(MessageTypes.SequenceReset, DEFAULT_TIMEOUT))
                    .containsFieldWithValue(MsgSeqNum.get(), "3")
                    .containsFieldWithValue(GapFillFlag.get(), "Y")
                    .containsFieldWithValue(NewSeqNo.get(), "5");

            // no ResendRequest was needed to get the peer back in step
            assertThat(acceptorLogger.getOutgoingMessages())
                    .noneMatch(message -> message.contains(CoreFields.FIELD_SEPARATOR + "35=2" + CoreFields.FIELD_SEPARATOR));

            // and the session is verifiably synchronized afterwards
            session.send(session.message(MessageTypes.TestRequest, 11).set(TestReqID.get(), "Scenario9-789-live"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario9-789-live", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }
}
