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
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.UTCTime;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/**
 * Scenario 4 - Send Heartbeat message (applicable to all FIX systems). Mandatory.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario4 extends AbstractScenario {

    @Override
    FixSessionSettings.FixSessionSettingsBuilder<?, ?> getInitiatorFixSessionSettings() {
        return super.getInitiatorFixSessionSettings()
                .heartBeatInterval(FixSessionSettings.HeartbeatInterval.builder()
                        .initiatorInterval(Duration.ofSeconds(2))
                        .build());
    }

    @Test
    void A_no_data_sent_during_preset_heartbeat_interval() {
        // Condition/Stimulus: No data sent during preset heartbeat interval (HeartBtInt(108) field).
        // Expected Behavior: Send Heartbeat(35=0) message.
        logonClient();

        // With no application data flowing, each side emits a Heartbeat(35=0) once the heartbeat interval elapses.
        await().untilAsserted(() -> verify(fixAcceptorApplication).onHeartbeat(any(FixSession.class), any(UTCTime.class)));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onHeartbeat(any(FixSession.class), any(UTCTime.class)));

        // The idle interval must produce a plain Heartbeat, not a TestRequest(35=1).
        verify(fixAcceptorApplication, never()).onTestRequest(any(FixSession.class), any(), any(UTCTime.class));
        verify(fixInitiatorApplication, never()).onTestRequest(any(FixSession.class), any(), any(UTCTime.class));
    }

    @Test
    void B_testrequest_message_received() {
        // Condition/Stimulus: TestRequest(35=1) message received.
        // Expected Behavior: Send Heartbeat(35=0) message with TestRequest(35=1) message's TestReqID(112).
        logonClient();

        String testReqID = "Scenario4TestReqID";
        fixInitiatorSession.testRequest(testReqID);

        // The acceptor (system under test) receives the TestRequest ...
        await().untilAsserted(() -> verify(fixAcceptorApplication).onTestRequest(any(FixSession.class), eq(testReqID), any(UTCTime.class)));
        // ... and answers with a Heartbeat echoing the TestReqID, which the initiator sees as a test request response.
        await().untilAsserted(() -> verify(fixInitiatorApplication).onTestRequestResponse(any(FixSession.class), eq(testReqID), any(UTCTime.class)));
    }
}
