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
import org.lolaf.staffix.api.codec.SessionRejectReasonCodes;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.fix44.fields.RefSeqNum;
import org.lolaf.staffix.fix44.fields.SessionRejectReason;
import org.lolaf.staffix.fix44.fields.TestReqID;
import org.lolaf.staffix.fix44.fields.Text;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

/**
 * Scenario 7 - Receive Reject message (applicable to all FIX systems). Mandatory.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario7 extends AbstractScenario {

    @Test
    void valid_reject_message() throws Exception {
        // Condition/Stimulus: Valid Reject(35=3) message.
        // Expected Behavior:
        //   1. Increment NextNumIn.
        //   2. Continue accepting messages.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // a Reject the peer sends about the acceptor's own Logon, which is MsgSeqNum(34) 1 on this session
            session.send(session.message(MessageTypes.Reject, 2)
                    .set(RefSeqNum.get(), "1")
                    .set(SessionRejectReason.get(), String.valueOf(SessionRejectReasonCodes.OTHER.getCode()))
                    .set(Text.get(), "Scenario7"));

            // the Reject is handed to the application rather than answered: a Reject is never itself rejected
            await().untilAsserted(() -> verify(fixAcceptorApplication).onMessageReject(any(FixSession.class),
                    eq("Scenario7"), eq(SessionRejectReasonCodes.OTHER.getCode()), eq(1L), anyInt(), any()));

            // NextNumIn is incremented ...
            await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(3));
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();

            // ... and messages keep being accepted afterwards
            session.send(session.message(MessageTypes.TestRequest, 3).set(TestReqID.get(), "Scenario7-next"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario7-next", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat)
                    .doesNotHaveMsgType(MessageTypes.Reject)
                    .doesNotHaveMsgType(MessageTypes.Logout);
        }
    }
}
