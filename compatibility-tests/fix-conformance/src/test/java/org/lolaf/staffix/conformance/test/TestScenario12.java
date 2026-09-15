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
import org.lolaf.staffix.fix44.fields.Text;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Scenario 12 - Initiate logout process (applicable to all FIX systems). Mandatory.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario12 extends AbstractScenario {

    @Test
    void initiate_logout() throws Exception {
        // Condition/Stimulus: Initiate logout.
        // Expected Behavior:
        //   1. Send Logout(35=5) message.
        //   2. Wait for counterparty to respond with Logout(35=5) message LogoutAckThreshold seconds (Note: may not be
        //      received if communications problem exists). If not received, generate a warning condition in test output.
        //   3. Disconnect.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            fixAcceptorSession.logoutPermanently("Scenario12");

            // 1. the acceptor sends the Logout carrying the reason it was given
            String logout = session.readMessageOfType(MessageTypes.Logout, DEFAULT_TIMEOUT);
            assertThatFixMessage(logout)
                    .hasMsgType(MessageTypes.Logout)
                    .containsFieldWithValue(Text.get(), "Scenario12");
            verify(fixAcceptorApplication).onLogoutInitiated(any(FixSession.class), eq("Scenario12"));

            // 2. it then waits for the acknowledgement rather than dropping the connection straight away
            assertThat(session.isClosedByPeer(java.time.Duration.ofMillis(500))).isFalse();

            // 3. and disconnects once acknowledged
            session.send(session.message(MessageTypes.Logout, 2).set(Text.get(), "Scenario12-ack"));
            assertThat(session.isClosedByPeer(DEFAULT_TIMEOUT)).isTrue();
            await().untilAsserted(() -> verify(fixAcceptorApplication).onDisconnected(any(FixSession.class)));
        }
    }
}
