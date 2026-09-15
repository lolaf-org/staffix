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
import org.lolaf.staffix.fix44.fields.GapFillFlag;
import org.lolaf.staffix.fix44.fields.NewSeqNo;
import org.lolaf.staffix.fix44.fields.SessionRejectReason;
import org.lolaf.staffix.fix44.fields.TestReqID;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;

/**
 * Scenario 11 - Receive Sequence Reset (Reset) (applicable to all FIX systems). Mandatory.
 * <p>
 * A SequenceReset(35=4) with GapFillFlag(123)=N (or absent) is a hard reset: it forces NextNumIn to NewSeqNo(36)
 * without regard to its own MsgSeqNum(34).
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario11 extends AbstractScenario {

    @Test
    void A_reset_newseqno_gt_nextnumin() throws Exception {
        // Condition/Stimulus: Receive SequenceReset(35=4) with GapFillFlag(123)=N and NewSeqNo(36) > NextNumIn.
        // Expected Behavior: accept the message without regard to its MsgSeqNum(34) and set NextNumIn to NewSeqNo(36).
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // NextNumIn is 2 after logon. A hard reset jumps it forward to 5 regardless of the reset's own MsgSeqNum,
            // deliberately sent here as 99 to exercise the "without regard to MsgSeqNum" rule.
            session.send(session.message(MessageTypes.SequenceReset, 99)
                    .set(GapFillFlag.get(), "N").set(NewSeqNo.get(), "5"));

            // NextNumIn is forced to NewSeqNo, ignoring the reset's own MsgSeqNum of 99
            await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(5));

            // the very next in-sequence message is now expected at 5: a TestRequest there is accepted and answered
            session.send(session.message(MessageTypes.TestRequest, 5).set(TestReqID.get(), "Scenario11A"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario11A", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);
        }
    }

    @Test
    void B_reset_newseqno_eq_nextnumin() throws Exception {
        // Condition/Stimulus: Receive SequenceReset(35=4) with GapFillFlag(123)=N and NewSeqNo(36) = NextNumIn.
        // Expected Behavior: accept the message; a warning may be generated.
        // The core requirement is acceptance: the redundant reset is neither rejected nor fatal and the session
        // keeps running. staffix does not emit a distinct warning for the NewSeqNo == NextNumIn case (it logs the
        // same "Processing hard SequenceReset" event as any other reset), which the spec only recommends.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            session.send(session.message(MessageTypes.SequenceReset, 2)
                    .set(GapFillFlag.get(), "N").set(NewSeqNo.get(), "2"));

            await().untilAsserted(() -> assertThat(acceptorLogger.getEvents())
                    .anyMatch(event -> event.contains("SequenceReset") && event.contains("NewSeqNum 2")));
            assertThat(session.isClosedByPeer(Duration.ofMillis(500))).isFalse();
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }

    @Test
    void C_reset_newseqno_lt_nextnumin() throws Exception {
        // Condition/Stimulus: Receive SequenceReset(35=4) with GapFillFlag(123)=N and NewSeqNo(36) < NextNumIn.
        // Expected Behavior:
        //   1. Accept the message without regard to its MsgSeqNum(34).
        //   2. Send Reject(35=3) with SessionRejectReason(373)=5 (Value is incorrect for this tag).
        //   3. Do NOT change NextNumIn.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // NextNumIn is 2 after logon; a reset back to 1 is out of range and must be rejected, not applied
            session.send(session.message(MessageTypes.SequenceReset, 2)
                    .set(GapFillFlag.get(), "N").set(NewSeqNo.get(), "1"));

            assertThatFixMessage(session.readMessageOfType(MessageTypes.Reject, DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Reject)
                    .containsFieldWithValue(SessionRejectReason.get(), "5");

            // NextNumIn is untouched and the session keeps going
            assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(2);
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }
}
