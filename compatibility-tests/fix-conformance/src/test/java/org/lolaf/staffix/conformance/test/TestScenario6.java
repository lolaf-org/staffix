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
import org.lolaf.staffix.fix44.fields.EncryptMethod;
import org.lolaf.staffix.fix44.fields.HeartBtInt;
import org.lolaf.staffix.fix44.fields.TestReqID;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Scenario 6 - Send Test Request (applicable to all FIX systems). Mandatory.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario6 extends AbstractScenario {

    @Test
    void no_data_received_during_preset_heartbeat_interval_plus_reasonable_period() throws Exception {
        // Condition/Stimulus: No data received during preset heartbeat interval (HeartBtInt(108)) + "some reasonable
        //   period of time" (use 20% of HeartBtInt(108)).
        // Expected Behavior:
        //   1. Send TestRequest(35=1) message.
        //   2. Track and verify that a Heartbeat(35=0) message with the same TestReqID(112) is received (may not be the
        //      next message received).
        fixAcceptor.start();
        // logged on by hand rather than through rawInitiatorLogon() so that HeartBtInt(108) is one second: the raw
        // client then stays silent and the acceptor reaches its idle threshold in about 1.2s instead of 6s
        try (RawFixSocketClient.Session session = rawInitiatorConnect()) {
            session.send(session.message(MessageTypes.Logon, 1)
                    .set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "1"));
            assertThatFixMessage(session.readMessage(DEFAULT_TIMEOUT)).hasMsgType(MessageTypes.Logon);

            // staying silent past HeartBtInt + 20% makes the acceptor probe the connection with a TestRequest, whose
            // TestReqID it generates itself (the acceptor also emits plain Heartbeats meanwhile, hence reading by type)
            String testRequest = session.readMessageOfType(MessageTypes.TestRequest, DEFAULT_TIMEOUT);
            assertThatFixMessage(testRequest).hasMsgType(MessageTypes.TestRequest);
            String testReqId = RawFixSocketClient.fieldValue(testRequest, TestReqID.get());

            // answering it with a Heartbeat carrying that same TestReqID is what the acceptor is tracking
            session.send(session.message(MessageTypes.Heartbeat, 2).set(TestReqID.get(), testReqId));

            await().untilAsserted(() -> verify(fixAcceptorApplication)
                    .onTestRequestResponse(any(FixSession.class), eq(testReqId), any(UTCTime.class)));

            // the probe is satisfied, so the session is kept rather than dropped as unresponsive
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }
}
