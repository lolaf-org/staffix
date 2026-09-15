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
package org.lolaf.staffix.monitoring.micrometer;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.monitoring.FixMonitoringAttributes;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringContext;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.RttMeasurement;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManager.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for MicrometerMonitoringManager.
 * Tests monitoring manager lifecycle, session management, custom timers, and event tracking.
 */
class MicrometerMonitoringManagerTest {

    private MeterRegistry meterRegistry;
    private MicrometerMonitoringManager monitoringManager;
    private AtomicBoolean registryStarted;
    private AtomicBoolean registryStopped;
    private FixSession fixSession;
    private FixSessionId fixSessionId;
    private MicrometerMonitoringManagerSettings settings;
    private int msgTypeIndex = 0;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        registryStarted = new AtomicBoolean(false);
        registryStopped = new AtomicBoolean(false);
        fixSession = mock(FixSession.class);
        fixSessionId = FixSessionId.of("test", FixRegularVersion.VERSION_44, "TEST_SENDER", "TEST_TARGET");
        when(fixSession.getFixSessionId()).thenReturn(fixSessionId);
        settings = MicrometerMonitoringManagerSettings.builder()
                .instanceId("test-instance")
                .meterRegistrySupplier(() -> meterRegistry)
                .startedMeterRegistryConsumer(mr -> registryStarted.set(true))
                .stoppingMeterRegistryConsumer(mr -> registryStopped.set(true))
                .timerSetting("timer.with.no.histogram",
                        MicrometerMonitoringManagerSettings.TimerSettings.builder()
                                .publishPercentileHistogram(false)
                                .histogramsPercentiles(List.of())
                                .build())
                .build();

        monitoringManager = createManager(settings);
    }

    private MicrometerMonitoringManager createManager(MicrometerMonitoringManagerSettings settings) {
        return new MicrometerMonitoringManager(settings);
    }

    @Test
    void testStartMe_shouldInitializeMeterRegistry() {
        // When
        monitoringManager.start();

        // Then
        assertThat(registryStarted.get()).isTrue();
        assertThat(monitoringManager.isStarted()).isTrue();
    }

    @Test
    void testStopMe_shouldCleanupMeterRegistry() {
        // Given
        monitoringManager.start();
        assertThat(monitoringManager.isStarted()).isTrue();

        // When
        monitoringManager.stop(Deadline.unlimited());

        // Then
        assertThat(registryStopped.get()).isTrue();
        assertThat(monitoringManager.isStarted()).isFalse();
    }

    @Test
    void testGetInstanceId_shouldReturnConfiguredInstanceId() {
        // Then
        assertThat(monitoringManager.getInstanceId()).isEqualTo("test-instance");
    }

    @Test
    void testOnSessionCreated_shouldCreateFixEventsListener() {
        // Given
        monitoringManager.start();
        List<MessageType> messageTypes = List.of(getMessageType("A"), getMessageType("D"));

        // When
        FixSessionPlugin listener =
                monitoringManager.onSessionCreated("fix-instance-1", fixSession, messageTypes, List.of()).orElseThrow();

        // Then
        assertThat(listener).isNotNull();

        // Verify session state gauge is registered
        Gauge gauge = meterRegistry.find(SESSION_LOGON_STATE)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(0.0); // Not logged on initially

        // Verify timers are registered for each message type
        Timer readTimer = meterRegistry.find(MESSAGES_READ_LATENCY)
                .tag(FixMonitoringAttributes.FIX_MESSAGE_TYPE, "A")
                .timer();
        assertThat(readTimer).isNotNull();

        Timer writeTimer = meterRegistry.find(MESSAGES_WRITE_LATENCY)
                .tag(FixMonitoringAttributes.FIX_MESSAGE_TYPE, "D")
                .timer();
        assertThat(writeTimer).isNotNull();
    }

    @Test
    void testOnSessionCreated_shouldReuseSameListenerForSameSession() {
        // Given
        monitoringManager.start();
        List<MessageType> messageTypes = List.of(getMessageType("A"));

        // When
        FixSessionPlugin listener1 =
                monitoringManager.onSessionCreated("fix-instance-1", fixSession, messageTypes, List.of()).orElseThrow();
        FixSessionPlugin listener2 =
                monitoringManager.onSessionCreated("fix-instance-1", fixSession, messageTypes, List.of()).orElseThrow();
        monitoringManager.onSessionCreated("fix-instance-1", fixSession, messageTypes, List.of()).orElseThrow();

        // Then
        assertThat(listener1).isSameAs(listener2);
    }

    @Test
    void testOnSessionDestroyed_shouldCleanupMetrics() {
        // Given
        monitoringManager.start();

        List<MessageType> messageTypes = List.of(getMessageType("A"));

        FixSessionPlugin listener = monitoringManager.onSessionCreated("fix-instance-1", fixSession, messageTypes, List.of()).orElseThrow();

        // Verify metrics exist
        assertThat(meterRegistry.find(SESSION_LOGON_STATE)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .gauge()).isNotNull();
        assertThat(meterRegistry.find(MESSAGES_READ_LATENCY)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer()).isNotNull();
        assertThat(meterRegistry.find(MESSAGES_WRITE_LATENCY)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer()).isNotNull();

        // When
        listener.onSessionDestroyed("fix-instance-1", fixSessionId);

        // Then - metrics should be removed
        assertThat(meterRegistry.find(SESSION_LOGON_STATE)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId()).gauge()).isNull();
        assertThat(meterRegistry.find(MESSAGES_READ_LATENCY)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId()).timer()).isNull();
        assertThat(meterRegistry.find(MESSAGES_WRITE_LATENCY)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId()).timer()).isNull();
    }

    @Test
    void testGetTimer_shouldCreateCustomTimerWithTags() {
        // Given
        monitoringManager.start();

        Map<String, String> customTags = new HashMap<>();
        customTags.put("custom.tag1", "value1");
        customTags.put("custom.tag2", "value2");

        // When
        FixSessionPlugin<FixSessionsMonitoringContext, Void> listener =
                monitoringManager.onSessionCreated("fix-instance-1", fixSession, List.of(), List.of()).orElseThrow();
        org.lolaf.staffix.api.monitoring.Timer timer = listener.getPluginContext().orElseThrow().getTimer(
                "custom.timer",
                "Custom timer for testing",
                customTags);

        // Then
        assertThat(timer).isNotNull();

        // Verify timer is registered with all tags
        Timer micrometerTimer = meterRegistry.find("custom.timer")
                .tag(FixMonitoringAttributes.FIX_INSTANCE_ID, "fix-instance-1")
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .tag("custom.tag1", "value1")
                .tag("custom.tag2", "value2")
                .timer();
        assertThat(micrometerTimer).isNotNull();
    }

    @Test
    void testGetTimer_shouldReuseSameTimerForSameIdAndTags() {
        // Given
        monitoringManager.start();

        // When
        FixSessionPlugin<FixSessionsMonitoringContext, Void> listener = monitoringManager
                .onSessionCreated("fix-instance-1", fixSession, List.of(), List.of()).orElseThrow();
        org.lolaf.staffix.api.monitoring.Timer timer1 = listener.getPluginContext().orElseThrow().getTimer(
                "timer1", "Timer 1", Map.of("tag1", "value1"));

        org.lolaf.staffix.api.monitoring.Timer timer2 = listener.getPluginContext().orElseThrow().getTimer(
                "timer1", "Timer 1", Map.of("tag1", "value1"));

        // Then
        assertThat(timer1).isSameAs(timer2);
    }

    @Test
    void testGetTimer_shouldCreateDifferentTimersForDifferentTags() {
        // Given
        monitoringManager.start();

        // When
        FixSessionPlugin<FixSessionsMonitoringContext, Void> listener = monitoringManager
                .onSessionCreated("fix-instance-1", fixSession, List.of(), List.of()).orElseThrow();
        org.lolaf.staffix.api.monitoring.Timer timer1 = listener.getPluginContext().orElseThrow().getTimer(
                "timer1", "Timer 1", Map.of("tag1", "value1"));
        org.lolaf.staffix.api.monitoring.Timer timer2 = listener.getPluginContext().orElseThrow().getTimer(
                "timer1", "Timer 1", Map.of("tag1", "value2"));

        // Then
        assertThat(timer1).isNotSameAs(timer2);
    }

    @Test
    void testCustomTimer_with_custom_settings() {
        // Given
        monitoringManager.start();

        FixSessionPlugin<FixSessionsMonitoringContext, Void> listener = monitoringManager
                .onSessionCreated("fix-instance-1", fixSession, List.of(), List.of()).orElseThrow();
        org.lolaf.staffix.api.monitoring.Timer timerWithNoHistogramSettings = listener.getPluginContext().orElseThrow().getTimer(
                "timer.with.no.histogram", "Test timer", Map.of());

        org.lolaf.staffix.api.monitoring.Timer timer = listener.getPluginContext().orElseThrow().getTimer(
                "timer.normal", "Test timer", Map.of());

        // When
        timerWithNoHistogramSettings.start();
        timer.start();
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));// Simulate some work
        timer.stop();
        timerWithNoHistogramSettings.stop();

        // Then
        Timer micrometerTimerWithNoHistograms = meterRegistry.find("timer.with.no.histogram").timer();
        assertThat(micrometerTimerWithNoHistograms).isNotNull();
        assertThat(micrometerTimerWithNoHistograms.takeSnapshot().percentileValues()).isEmpty();

        Timer micrometerTimer = meterRegistry.find("timer.normal").timer();
        assertThat(micrometerTimer).isNotNull();
        assertThat(micrometerTimer.takeSnapshot().percentileValues()).isNotEmpty();

    }

    @Test
    void testCustomTimer_startAndStop_shouldRecordDuration() {
        // Given
        monitoringManager.start();

        FixSessionPlugin<FixSessionsMonitoringContext, Void> listener = monitoringManager
                .onSessionCreated("fix-instance-1", fixSession, List.of(), List.of()).orElseThrow();
        org.lolaf.staffix.api.monitoring.Timer timer = listener.getPluginContext().orElseThrow().getTimer(
                "test.timer", "Test timer", Map.of());

        // When
        timer.start();
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));// Simulate some work
        timer.stop();

        // Then
        Timer micrometerTimer = meterRegistry.find("test.timer").timer();
        assertThat(micrometerTimer).isNotNull();
        assertThat(micrometerTimer.count()).isEqualTo(1);
        assertThat(micrometerTimer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThan(9);
    }

    @Test
    void testCustomTimer_record_shouldRecordSpecifiedDuration() {
        // Given
        monitoringManager.start();

        FixSessionPlugin<FixSessionsMonitoringContext, Void> listener = monitoringManager
                .onSessionCreated("fix-instance-1", fixSession, List.of(), List.of()).orElseThrow();
        org.lolaf.staffix.api.monitoring.Timer timer = listener.getPluginContext().orElseThrow().getTimer(
                "test.timer", "Test timer", Map.of());

        // When
        timer.record(100, TimeUnit.MILLISECONDS);

        // Then
        Timer micrometerTimer = meterRegistry.find("test.timer")
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer();
        assertThat(micrometerTimer).isNotNull();
        assertThat(micrometerTimer.count()).isEqualTo(1);
        assertThat(micrometerTimer.totalTime(TimeUnit.MILLISECONDS))
                .isGreaterThanOrEqualTo(99.0)
                .isLessThan(101.0);
    }

    @Test
    void testFixEventsListener_onLogon_shouldUpdateGauge() {
        // Given
        monitoringManager.start();

        FixSessionPlugin listener =
                monitoringManager.onSessionCreated("fix-instance-1", fixSession, List.of(), List.of()).orElseThrow();

        Gauge gauge = meterRegistry.find(SESSION_LOGON_STATE)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .gauge();
        assertThat(gauge.value()).isEqualTo(0.0);

        // When
        listener.onLogon();

        // Then
        assertThat(gauge.value()).isEqualTo(1.0);
    }

    @Test
    void testFixEventsListener_onLogout_shouldUpdateGauge() {
        // Given
        monitoringManager.start();

        FixSessionPlugin listener =
                monitoringManager.onSessionCreated("fix-instance-1", fixSession, List.of(), List.of()).orElseThrow();

        listener.onLogon();
        Gauge gauge = meterRegistry.find(SESSION_LOGON_STATE)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .gauge();
        assertThat(gauge.value()).isEqualTo(1.0);

        // When
        listener.onLogout();

        // Then
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    void testFixEventsListener_onMessageReceived_shouldRecordLatency() {
        // Given
        monitoringManager.start();
        MessageType messageType = getMessageType("A");

        FixSessionPlugin<FixSessionsMonitoringContext, Void> listener =
                monitoringManager.onSessionCreated("fix-instance-1", fixSession, List.of(messageType), List.of()).orElseThrow();

        // When
        long receiveTime = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(50);
        listener.onMessageReceived(messageType, 100, receiveTime, null);

        // Then
        Timer timer = meterRegistry.find(MESSAGES_READ_LATENCY)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThan(0);
    }

    @Test
    void testFixEventsListener_onMessageSent_shouldRecordLatency() {
        // Given
        monitoringManager.start();
        MessageType messageType = getMessageType("D");

        FixSessionPlugin listener =
                monitoringManager.onSessionCreated("fix-instance-1", fixSession, List.of(messageType), List.of()).orElseThrow();

        // When
        long sendTime = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(30);
        listener.onMessageSent(messageType, 150, sendTime, null);

        // Then
        Timer timer = meterRegistry.find(MESSAGES_WRITE_LATENCY)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThan(0);
    }

    @Test
    void testFixEventsListener_onMessageReceivedWithUnknownType_shouldHandleGracefully() {
        // Given
        monitoringManager.start();
        MessageType knownType = getMessageType("A");
        MessageType unknownType = getMessageType("Z");

        FixSessionPlugin listener =
                monitoringManager.onSessionCreated("fix-instance-1", fixSession, List.of(knownType), List.of()).orElseThrow();

        // When - receive message with unknown type (should not throw exception)
        long receiveTime = System.nanoTime();
        listener.onMessageReceived(unknownType, 100, receiveTime, null);

        // Then - no timer should be created for unknown type
        Timer timer = meterRegistry.find(MESSAGES_READ_LATENCY)
                .tag(FixMonitoringAttributes.FIX_MESSAGE_TYPE, knownType.code())
                .timer();
        assertThat(timer).isNotNull();

        timer = meterRegistry.find(MESSAGES_READ_LATENCY)
                .tag(FixMonitoringAttributes.FIX_MESSAGE_TYPE, unknownType.code())
                .timer();
        assertThat(timer).isNull();
    }

    @Test
    void testOnSessionDestroyed_shouldCleanupCustomTimers() {
        // Given
        monitoringManager.start();

        FixSessionPlugin<FixSessionsMonitoringContext, Void> listener =
                monitoringManager.onSessionCreated("fix-instance-1", fixSession, List.of(), List.of()).orElseThrow();

        listener.getPluginContext().orElseThrow().getTimer(
                "custom.timer", "Custom timer", Map.of());

        // Verify timer exists
        assertThat(meterRegistry.find("custom.timer")
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer()).isNotNull();

        // When
        listener.onSessionDestroyed("fix-instance-1", fixSessionId);

        // Then - custom timer should be removed
        assertThat(meterRegistry.find("custom.timer")
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer()).isNull();
    }

    @Test
    void testMultipleSessions_shouldMaintainSeparateMetrics() {
        // Given
        monitoringManager.start();

        FixSession fixSession2 = mock(FixSession.class);
        FixSessionId fixSessionId2 = FixSessionId.of("test2", FixRegularVersion.VERSION_44, "TEST_SENDER2", "TEST_TARGET2");
        when(fixSession2.getFixSessionId()).thenReturn(fixSessionId2);
        MessageType messageType = getMessageType("A");

        // When
        FixSessionPlugin listener1 =
                monitoringManager.onSessionCreated("fix-instance-1", fixSession, List.of(messageType), List.of()).orElseThrow();
        FixSessionPlugin listener2 =
                monitoringManager.onSessionCreated("fix-instance-1", fixSession2, List.of(messageType), List.of()).orElseThrow();

        listener1.onLogon();
        listener2.onLogout();

        // Then
        Gauge gauge1 = meterRegistry.find(SESSION_LOGON_STATE)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .gauge();
        Gauge gauge2 = meterRegistry.find(SESSION_LOGON_STATE)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId2.getId())
                .gauge();

        assertThat(gauge1.value()).isEqualTo(1.0);
        assertThat(gauge2.value()).isEqualTo(0.0);
    }

    @Test
    void testCustomTimerSettings_shouldApplyToSpecificTimer() {
        // Given
        MicrometerMonitoringManagerSettings.TimerSettings customSettings =
                MicrometerMonitoringManagerSettings.TimerSettings.builder()
                        .publishPercentileHistogram(false)
                        .histogramsPercentiles(List.of(0.99))
                        .minExpectedValue(Duration.ofNanos(100))
                        .maxExpectedValue(Duration.ofMillis(10))
                        .build();

        MicrometerMonitoringManagerSettings settings = MicrometerMonitoringManagerSettings.builder()
                .instanceId("test-instance")
                .meterRegistrySupplier(() -> meterRegistry)
                .timerSetting("special.timer", customSettings)
                .build();

        MicrometerMonitoringManager customManager = new MicrometerMonitoringManager(settings);
        customManager.start();

        // When
        FixSessionPlugin<FixSessionsMonitoringContext, Void> listener =
                customManager.onSessionCreated("fix-instance-1", fixSession, List.of(), List.of()).orElseThrow();
        listener.getPluginContext().orElseThrow().getTimer("special.timer", "Special timer", Map.of());

        // Then
        Timer timer = meterRegistry.find("special.timer")
                .timer();
        assertThat(timer).isNotNull();

        customManager.stop(Deadline.unlimited());
    }

    @Test
    void testReadLatencyDisabled_shouldNotRegisterReadTimers() {
        // Given
        MicrometerMonitoringManager manager = createManager(settings.toBuilder()
                .readLatencyEnabled(false)
                .build());
        manager.start();
        MessageType messageType = getMessageType("A");

        // When
        manager.onSessionCreated("fix-instance-1", fixSession, List.of(messageType), List.of());

        // Then
        assertThat(meterRegistry.find(MESSAGES_READ_LATENCY)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer()).isNull();
        // Write timer should still be registered (enabled by default)
        assertThat(meterRegistry.find(MESSAGES_WRITE_LATENCY)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer()).isNotNull();

        manager.stop(Deadline.unlimited());
    }

    @Test
    void testWriteLatencyDisabled_shouldNotRegisterWriteTimers() {
        // Given
        MicrometerMonitoringManager manager = createManager(settings.toBuilder()
                .writeLatencyEnabled(false)
                .build());
        manager.start();
        MessageType messageType = getMessageType("D");

        // When
        manager.onSessionCreated("fix-instance-1", fixSession, List.of(messageType), List.of());

        // Then
        assertThat(meterRegistry.find(MESSAGES_WRITE_LATENCY)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer()).isNull();
        // Read timer should still be registered (enabled by default)
        assertThat(meterRegistry.find(MESSAGES_READ_LATENCY)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer()).isNotNull();

        manager.stop(Deadline.unlimited());
    }

    @Test
    void testDecodingLatencyDisabledByDefault_shouldNotRegisterDecodingTimers() {
        // Given - default settings (decodingLatencyEnabled = false)
        monitoringManager.start();
        MessageType messageType = getMessageType("A");

        // When
        monitoringManager.onSessionCreated("fix-instance-1", fixSession, List.of(messageType), List.of());

        // Then
        assertThat(meterRegistry.find(MESSAGES_DECODING_LATENCY)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer()).isNull();
    }

    @Test
    void testEncodingLatencyDisabledByDefault_shouldNotRegisterEncodingTimers() {
        // Given - default settings (encodingLatencyEnabled = false)
        monitoringManager.start();
        MessageType messageType = getMessageType("D");

        // When
        monitoringManager.onSessionCreated("fix-instance-1", fixSession, List.of(messageType), List.of());

        // Then
        assertThat(meterRegistry.find(MESSAGES_ENCODING_LATENCY)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer()).isNull();
    }

    @Test
    void testDecodingLatencyEnabled_shouldRecordDecodingLatency() {
        // Given
        MicrometerMonitoringManager manager = createManager(settings.toBuilder()
                .decodingLatencyEnabled(true)
                .build());
        manager.start();
        MessageType messageType = getMessageType("A");

        FixSessionPlugin<FixSessionsMonitoringContext, Void> listener =
                manager.onSessionCreated("fix-instance-1", fixSession, List.of(messageType), List.of()).orElseThrow();

        // When
        long receiveTime = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(50);
        listener.onMessageDecodingFinished(messageType, receiveTime, null);

        // Then
        Timer timer = meterRegistry.find(MESSAGES_DECODING_LATENCY)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(50);

        manager.stop(Deadline.unlimited());
    }

    @Test
    void testEncodingLatencyEnabled_shouldRecordEncodingLatency() {
        // Given
        MicrometerMonitoringManager manager = createManager(settings.toBuilder()
                .encodingLatencyEnabled(true)
                .build());
        manager.start();
        MessageType messageType = getMessageType("D");

        FixSessionPlugin<FixSessionsMonitoringContext, Void> listener =
                manager.onSessionCreated("fix-instance-1", fixSession, List.of(messageType), List.of()).orElseThrow();

        // When
        long encodingTime = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(30);
        listener.onMessageEncodingFinished(messageType, encodingTime, null);

        // Then
        Timer timer = meterRegistry.find(MESSAGES_ENCODING_LATENCY)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(30);

        manager.stop(Deadline.unlimited());
    }

    @Test
    void testRttAndClockOffsetDisabledByDefault_shouldNotRegisterInstruments() {
        monitoringManager.start();

        monitoringManager.onSessionCreated("fix-instance-1", fixSession, List.of(), List.of());

        assertThat(meterRegistry.find(SESSION_RTT)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer()).isNull();
        assertThat(meterRegistry.find(SESSION_CLOCK_OFFSET)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .gauge()).isNull();
    }

    @Test
    void testOnRttMeasurement_shouldRecordRttAndSignedClockOffset() {

        MicrometerMonitoringManager manager = createManager(settings.toBuilder()
                .rttLatencyEnabled(true)
                .clockOffsetEnabled(true)
                .build());
        manager.start();

        FixSessionPlugin<FixSessionsMonitoringContext, Void> listener =
                manager.onSessionCreated("fix-instance-1", fixSession, List.of(), List.of()).orElseThrow();

        Duration rtt = Duration.ofMillis(7);
        // Negative offset must be visible on the Gauge — DistributionSummary would silently drop it.
        Duration clockOffset = Duration.ofMillis(-3);
        listener.onRttMeasurement(new RttMeasurement(rtt, clockOffset, UTCTime.of(1_700_000_000_000_000_000L)));

        Timer rttTimer = meterRegistry.find(SESSION_RTT)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer();
        assertThat(rttTimer).isNotNull();
        assertThat(rttTimer.count()).isEqualTo(1);
        assertThat(rttTimer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(7.0);

        Gauge offsetGauge = meterRegistry.find(SESSION_CLOCK_OFFSET)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .gauge();
        assertThat(offsetGauge).isNotNull();
        assertThat(offsetGauge.value()).isEqualTo((double) clockOffset.toNanos());

        // A subsequent measurement should overwrite the gauge with the latest value.
        listener.onRttMeasurement(new RttMeasurement(rtt, Duration.ofMillis(5),
                UTCTime.of(1_700_000_000_000_000_000L)));
        assertThat(offsetGauge.value()).isEqualTo((double) Duration.ofMillis(5).toNanos());

        manager.stop(Deadline.unlimited());
    }

    @Test
    void testOnSessionDestroyed_shouldCleanupRttInstruments() {
        MicrometerMonitoringManager manager = createManager(settings.toBuilder()
                .rttLatencyEnabled(true)
                .clockOffsetEnabled(true)
                .build());
        manager.start();

        FixSessionPlugin<FixSessionsMonitoringContext, Void> listener =
                manager.onSessionCreated("fix-instance-1", fixSession, List.of(), List.of()).orElseThrow();

        assertThat(meterRegistry.find(SESSION_RTT)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer()).isNotNull();
        assertThat(meterRegistry.find(SESSION_CLOCK_OFFSET)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .gauge()).isNotNull();

        listener.onSessionDestroyed("fix-instance-1", fixSessionId);

        assertThat(meterRegistry.find(SESSION_RTT)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .timer()).isNull();
        assertThat(meterRegistry.find(SESSION_CLOCK_OFFSET)
                .tag(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId())
                .gauge()).isNull();

        manager.stop(Deadline.unlimited());
    }

    private MessageType getMessageType(String code) {
        final int index = msgTypeIndex++;
        return new MessageType() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public boolean isAdmin() {
                return false;
            }

            @Override
            public boolean isStorable() {
                return true;
            }

            @Override
            public byte[] serialized() {
                return new byte[0];
            }

            @Override
            public int getAsInt() {
                return index;
            }
        };
    }
}
