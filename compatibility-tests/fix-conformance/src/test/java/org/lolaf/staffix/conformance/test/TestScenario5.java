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
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.fix44.fields.TestReqID;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Scenario 5 - Receive Heartbeat message (applicable to all FIX systems). Mandatory.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario5 extends AbstractScenario {

    @Test
    void valid_heartbeat_message() throws Exception {
        // Condition/Stimulus: Valid Heartbeat(35=0) message.
        // Expected Behavior: Accept Heartbeat(35=0) message.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            session.send(session.message(MessageTypes.Heartbeat, 2));

            // the Heartbeat reaches the application, so it was accepted rather than disregarded
            await().untilAsserted(() -> verify(fixAcceptorApplication).onHeartbeat(any(FixSession.class), any(UTCTime.class)));

            // accepting it means consuming its sequence number and answering nothing
            await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(3));
            verify(fixAcceptorApplication, never())
                    .onMessageReject(any(FixSession.class), any(), anyInt(), anyLong(), anyInt(), any());
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();

            // and the session carries on: the next in-sequence message is still processed normally
            session.send(session.message(MessageTypes.TestRequest, 3).set(TestReqID.get(), "Scenario5"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario5", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat)
                    .doesNotHaveMsgType(MessageTypes.Reject)
                    .doesNotHaveMsgType(MessageTypes.Logout);
        }
    }
}
