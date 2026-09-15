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
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.fix44.fields.Text;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Scenario 13 - Receive Logout message (applicable to all FIX systems). Mandatory.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario13 extends AbstractScenario {

    @Test
    void A_receive_valid_logout_acknowledgement_in_response_to_logout_request() throws Exception {
        // Condition/Stimulus: Receive valid Logout(35=5) acknowledgement in response to a Logout(35=5) request.
        // Expected Behavior: Disconnect without sending a message.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // put the acceptor in the "logout requested" state, so the Logout it receives is an acknowledgement
            fixAcceptorSession.logoutPermanently("Scenario13A");
            assertThatFixMessage(session.readMessageOfType(MessageTypes.Logout, DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Logout);

            session.send(session.message(MessageTypes.Logout, 2).set(Text.get(), "Scenario13A-ack"));

            // nothing is sent in answer to the acknowledgement, the acceptor just drops the connection
            assertThat(session.readMessage(Duration.ofMillis(500))).isEmpty();
            assertThat(session.isClosedByPeer(DEFAULT_TIMEOUT)).isTrue();
            await().untilAsserted(() -> verify(fixAcceptorApplication).onDisconnected(any(FixSession.class)));
        }
    }

    @Test
    void B_receive_valid_logout_request_message_unsolicited() throws Exception {
        // Condition/Stimulus: Receive valid Logout(35=5) request message unsolicited.
        // Expected Behavior:
        //   1. Send Logout(35=5) acknowledgement message.
        //   2. Wait for counterparty to disconnect up to 10 seconds. If max exceeded, disconnect and generate an error
        //      condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            session.send(session.message(MessageTypes.Logout, 2).set(Text.get(), "Scenario13B"));

            // 1. the unsolicited Logout is answered with a Logout acknowledgement
            assertThatFixMessage(session.readMessageOfType(MessageTypes.Logout, DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Logout);
            await().untilAsserted(() -> verify(fixAcceptorApplication)
                    .onLogout(any(FixSession.class), eq("Scenario13B"), any(DecodedFixMessage.class)));

            // 2. the acceptor then leaves the disconnection to us rather than closing straight away
            assertThat(session.isClosedByPeer(Duration.ofMillis(500))).isFalse();
        }

        // and it notices once we do disconnect
        await().untilAsserted(() -> verify(fixAcceptorApplication).onDisconnected(any(FixSession.class)));
    }
}
