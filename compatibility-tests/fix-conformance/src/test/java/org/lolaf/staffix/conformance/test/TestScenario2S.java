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
import org.lolaf.staffix.fix44.fields.*;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.Mockito.*;

/**
 * Scenario 2S - Receive any message other than a Logon message (sellside-oriented / session acceptor). Mandatory.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario2S extends AbstractScenario {

    @Test
    void first_message_received_is_not_a_logon() throws Exception {
        // Condition/Stimulus: First message received is not a Logon(35=A) message.
        // Expected Behavior:
        //   1. Log an error "First message not a logon".
        //   2. Disconnect.
        // Note: the engine additionally tolerates Logout(35=5) and ResendRequest(35=2) as a first message, so this test
        // sends a Heartbeat(35=0) to exercise the "not a valid first message" path.
        fixAcceptor.start();

        try (RawFixSocketClient.Session session = rawInitiatorConnect()) {
            session.send(session.message(MessageTypes.Heartbeat, 1));

            await().untilAsserted(() -> verify(acceptorLogger)
                    .logEvent(any(), eq("First message received is not logon or logout: %s, disconnecting"), any()));

            // Reject(35=3) referencing the offending message (RefSeqNum(45), RefMsgType(372) and
            // SessionRejectReason(373) set to 11, Invalid MsgType) ...
            assertThatFixMessage(session.readMessage(DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Reject)
                    .containsFieldWithValue(MsgSeqNum.get(), "1")
                    .containsFieldWithValue(SenderCompID.get(), "TARGET44_TEST")
                    .containsFieldWithValue(TargetCompID.get(), "SENDER44_TEST")
                    .containsFieldWithValue(RefSeqNum.get(), "1")
                    .containsFieldWithValue(RefMsgType.get(), MessageTypes.Heartbeat.code())
                    .containsFieldWithValue(SessionRejectReason.get(), "11")
                    .containsFieldWithValue(Text.get(), "First received message is not logon or logout");

            // ... then a Logout(35=5) referencing the same error ...
            assertThatFixMessage(session.readMessage(DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Logout)
                    .containsFieldWithValue(MsgSeqNum.get(), "2")
                    .containsFieldWithValue(SenderCompID.get(), "TARGET44_TEST")
                    .containsFieldWithValue(TargetCompID.get(), "SENDER44_TEST")
                    .containsFieldWithValue(Text.get(), "First received message is not logon or logout");

            // ... and the connection is dropped.
            assertThat(session.isClosedByPeer(DEFAULT_TIMEOUT)).isTrue();
        }
    }
}
