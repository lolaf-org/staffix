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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringContext;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.RttMeasurement;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.tests.TestingFixSessionMonitoringManagerSettings;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TestFixRttMeasurement extends AbstractFixTests {

    @SuppressWarnings("unchecked")
    private FixSessionPlugin<FixSessionsMonitoringContext, Void> initiatorPlugin = mock(FixSessionPlugin.class);

    @Override
    FixSessionSettings.FixSessionSettingsBuilder<?, ?> getInitiatorFixSessionSettings() {
        return super.getInitiatorFixSessionSettings()
                .fixSessionPluginsInstanceId(FixSessionsMonitoringManager.class, InstanceProvider.DEFAULT_INSTANCE_ID)
                .rttMeasurementSettings(FixSessionSettings.RttMeasurementSettings.builder()
                        .probeInterval(Duration.ofMillis(100))
                        .emaTimeWindow(Duration.ofSeconds(2))
                        .maxAcceptedRtt(Duration.ofSeconds(2))
                        .probeTestReqIdPrefix("RTT-")
                        .build());
    }

    @BeforeEach
    @Override
    @SuppressWarnings("unchecked")
    void setup() {
        super.setup();
        FixSessionsMonitoringManager initiatorMonitoringManager = mock(FixSessionsMonitoringManager.class);
        initiatorPlugin = mock(FixSessionPlugin.class);
        when(initiatorMonitoringManager.matchesPluginClass(FixSessionsMonitoringManager.class)).thenReturn(true);
        doReturn(Optional.of(initiatorPlugin)).when(initiatorMonitoringManager)
                .onSessionCreated(anyString(), any(), any(), any());
        initiatorFixEngine.stop(Deadline.unlimited());
        initiatorFixEngine = initiatorFixEngineBuilder.toBuilder()
                .fixSessionsPlugin(TestingFixSessionMonitoringManagerSettings.builder()
                        .mock(initiatorMonitoringManager)
                        .build())
                .build()
                .instance().start();
        fixInitiator = initiatorFixEngine.newInitiator(fixInitiatorBuilder);
    }

    @Test
    void rttIsMeasuredOnInitiatorAfterProbesAreExchanged() {
        logonClient();

        // The initiator emits RTT probes every 100ms; wait until at least a few have been answered.
        await().untilAsserted(() -> verify(fixAcceptorApplication, atLeast(2))
                .onTestRequest(any(FixSession.class), startsWith("RTT-"), any(UTCTime.class)));
        await().untilAsserted(() -> verify(fixInitiatorApplication, atLeast(2))
                .onTestRequestResponse(any(FixSession.class), startsWith("RTT-"), any(UTCTime.class)));

        await().untilAsserted(() -> assertThat(fixInitiatorSession.getRttMeasurement()).isPresent());

        RttMeasurement measurement = fixInitiatorSession.getRttMeasurement().orElseThrow();
        // Loopback RTT on a sane machine is well under a second; the maxAcceptedRtt filter would
        // also have dropped anything above 2s.
        assertThat(measurement.getRoundTripTime()).isNotNull().isPositive().isLessThan(Duration.ofSeconds(1));

        // Two JVMs in the same process share the wall clock, so the measured offset is essentially noise
        // bounded by RTT/2. Just verify it's a sensible magnitude.
        assertThat(measurement.getClockOffset()).isNotNull();
        assertThat(measurement.getClockOffset().abs()).isLessThan(Duration.ofSeconds(1));

        assertThat(measurement.getSampleTime()).isNotNull();

        // The session plugin should also have been notified for each accepted sample.
        await().untilAsserted(() -> verify(initiatorPlugin, atLeastOnce()).onRttMeasurement(any(RttMeasurement.class)));
    }

    @Test
    void rttMeasurementIsDisabledByDefault() {
        setupInitiatorSessionSettings(b -> b.rttMeasurementSettings(
                FixSessionSettings.RttMeasurementSettings.builder().build()).build());
        logonClient();

        // Wait long enough that probes would have fired if the task had been scheduled.
        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(fixInitiatorSession.getRttMeasurement()).isEmpty());
        verify(fixAcceptorApplication, org.mockito.Mockito.never())
                .onTestRequest(any(FixSession.class), startsWith("RTT-"), any(UTCTime.class));
    }
}
