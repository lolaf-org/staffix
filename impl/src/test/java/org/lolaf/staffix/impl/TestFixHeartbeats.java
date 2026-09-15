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
package org.lolaf.staffix.impl;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.UTCTime;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class TestFixHeartbeats extends AbstractFixTests {


    @Override
    FixSessionSettings.FixSessionSettingsBuilder getInitiatorFixSessionSettings() {
        return super.getInitiatorFixSessionSettings().heartBeatInterval(FixSessionSettings.HeartbeatInterval.builder()
                .initiatorInterval(Duration.ofSeconds(2))
                .build());
    }

    @Test
    void testSendTestRequest() {
        logonClient();

        fixInitiatorSession.testRequest("initiatorTestRequest");

        await().untilAsserted(() -> verify(fixAcceptorApplication).onTestRequest(any(FixSession.class), eq("initiatorTestRequest"), any(UTCTime.class)));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onTestRequestResponse(any(FixSession.class), eq("initiatorTestRequest"), any(UTCTime.class)));

        fixAcceptorSession.testRequest("acceptorTestRequest");

        await().untilAsserted(() -> verify(fixInitiatorApplication).onTestRequest(any(FixSession.class), eq("acceptorTestRequest"), any(UTCTime.class)));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onTestRequestResponse(any(FixSession.class), eq("acceptorTestRequest"), any(UTCTime.class)));
    }

    @Test
    void testHeartBeatsAreSent() {
        logonClient();

        // heartbeats keep coming every HeartBeatInterval, so the count only ever grows: awaiting an exact count is a
        // race that can never recover once a second one has landed
        await().untilAsserted(() -> verify(fixAcceptorApplication, atLeastOnce()).onHeartbeat(any(FixSession.class), any(UTCTime.class)));
        await().untilAsserted(() -> verify(fixInitiatorApplication, atLeastOnce()).onHeartbeat(any(FixSession.class), any(UTCTime.class)));

        verify(fixAcceptorApplication, never()).onTestRequest(any(FixSession.class), any(), any(UTCTime.class));
        verify(fixInitiatorApplication, never()).onTestRequest(any(FixSession.class), any(), any(UTCTime.class));
    }

    @Test
    void testHeartbeatSuppressedWhenMessageSentRecently() {
        logonClient();

        for (int i = 0; i < 4; i++) {
            fixInitiatorSession.send(encodeTestMessage(i), null);
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
        }

        verify(fixAcceptorApplication, never()).onHeartbeat(any(FixSession.class), any(UTCTime.class));
    }

    @Test
    void testTestRequestSentWhenPeerIsSilent() {
        logonClient();

        verify(fixAcceptorApplication, never()).onHeartbeat(any(FixSession.class), any(UTCTime.class));
        verify(fixInitiatorApplication, never()).onHeartbeat(any(FixSession.class), any(UTCTime.class));

        // Advance initiator clock ~1 minute into the future so that isTestRequestRequired
        // returns true on the next scheduler tick.
        fixInitiatorClock.toFixedTime(Instant.now().plus(Duration.ofMinutes(1)));

        await().untilAsserted(() -> verify(fixAcceptorApplication)
                .onTestRequest(any(FixSession.class), startsWith("Heartbeat-"), any(UTCTime.class)));

        await().untilAsserted(() -> verify(fixInitiatorApplication)
                .onTestRequestResponse(any(FixSession.class), startsWith("Heartbeat-"), any(UTCTime.class)));

        fixInitiatorClock.toNonFixedTime();

        // the minute the initiator clock jumped makes its scheduler emit a burst of heartbeats, so several are already
        // in flight by now: assert they resume, not how many there are
        await().untilAsserted(() -> verify(fixAcceptorApplication, atLeastOnce()).onHeartbeat(any(FixSession.class), any(UTCTime.class)));
        await().untilAsserted(() -> verify(fixInitiatorApplication, atLeastOnce()).onHeartbeat(any(FixSession.class), any(UTCTime.class)));
    }
}