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
package org.lolaf.staffix.impl.session;

import lombok.RequiredArgsConstructor;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.RttMeasurement;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.impl.session.codec.FixAdminMessagesCodec;
import org.lolaf.staffix.impl.threading.SchedulerThread;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Section 4.6: the heartbeat interval the Logon(35=A) settled, the TestRequest(35=1) a quiet line calls for, and the
 * disconnection when even that goes unanswered. The round trip measurements ride along, being the same exchange.
 *
 * <p>Every message in or out is a sign of life, which is why this is where the session's last sent and last received
 * times live.
 */
@RequiredArgsConstructor
class HeartbeatsComponent implements FixSessionLayerComponent, FixSessionMessageListener {

    private final FixSessionImpl fixSession;
    private final FixSessionStateComponent fixSessionStateComponent;
    private final FixSessionSettings fixSessionSettings;
    private final FixAdminMessagesCodec fixAdminMessagesCodec;
    private final RttEstimator rttEstimator;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private PluginsComponent plugins;
    private ScheduledFuture<?> heartBeatTask;
    private ScheduledFuture<?> rttMeasurementsTask;
    private int heartbeatInterval;
    private long lastMessageSentInEpochSeconds;
    private long lastMessageReceivedInEpochSeconds;
    private long pendingTestRequestSentInEpochSeconds;

    int getHeartbeatInterval() {
        return heartbeatInterval;
    }

    Optional<RttMeasurement> getMeasurement() {
        return rttEstimator.getMeasurement();
    }

    @Override
    public void onLogonCompleted(LogonCompleted logon) {
        heartbeatInterval = logon.getHeartbeatInterval();
        unscheduleTasks();
        long initialDelayMillis = 1000L - (System.currentTimeMillis() % 1000L);
        heartBeatTask = scheduler.scheduleAtFixedRate(this::manageHeartbeats, initialDelayMillis, 1000L, TimeUnit.MILLISECONDS);
        Duration probeInterval = fixSessionSettings.getRttMeasurementSettings().getProbeInterval();
        if (probeInterval != null && !probeInterval.isZero()) {
            long periodMillis = Math.max(25L, probeInterval.toMillis());
            rttMeasurementsTask = scheduler.scheduleAtFixedRate(this::sendRttMeasurementProbe, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void onLogoutReceived(String message, DecodedFixMessage logoutMessage) {
        unscheduleTasks();
    }

    @Override
    public void onLogoutProcessed(boolean cleanLogout) {
        lastMessageSentInEpochSeconds = 0;
        lastMessageReceivedInEpochSeconds = 0;
        pendingTestRequestSentInEpochSeconds = 0;
        heartbeatInterval = 0;
    }

    @Override
    public void onConnectionClosed() {
        unscheduleTasks();
    }

    @Override
    public void onSessionStopping(Deadline deadline) {
        unscheduleTasks();
    }

    @Override
    public void onMessageSent(UTCTime sendingTime) {
        lastMessageSentInEpochSeconds = sendingTime.getEpochSeconds();
    }

    @Override
    public void onMessageReceived(UTCTime receiveTime) {
        lastMessageReceivedInEpochSeconds = receiveTime.getEpochSeconds();
    }

    @Override
    public void onSessionStarted(FixSessionLayerComponents components) {
        plugins = components.get(PluginsComponent.class);
    }

    @Override
    public void onTestRequestResponseReceived(String testReqId, UTCTime sendingTime, long receiveMonotonicNanos,
                                              long receiveWallTimeNanos) {
        pendingTestRequestSentInEpochSeconds = 0;
        rttEstimator.recordHeartbeatReceived(testReqId, receiveMonotonicNanos, receiveWallTimeNanos, sendingTime.toEpochNanos())
                .ifPresent(plugins::onRttMeasurement);
    }

    private void unscheduleTasks() {
        if (heartBeatTask != null) {
            heartBeatTask.cancel(false);
            heartBeatTask = null;
        }
        if (rttMeasurementsTask != null) {
            rttMeasurementsTask.cancel(false);
            rttMeasurementsTask = null;
            rttEstimator.reset();
        }
    }

    @SchedulerThread
    private void manageHeartbeats() {
        UTCTime now = clock.now();
        if (isTestRequestResponseTimedOut(now)) {
            fixSession.logEvent("No response to TestRequest within heartbeat interval, disconnecting");
            fixSession.disconnect();
            return;
        }
        if (isTestRequestRequired(now)) {
            String testRequestId = "Heartbeat-" + now.getEpochSeconds();
            fixSession.logEvent("No message received for heartbeat interval, sending TestRequest %s", testRequestId);
            fixSession.send(fixAdminMessagesCodec.generateTestRequest(testRequestId), null, (sendingError, callbackParam1, callbackParam2) -> {
                if (sendingError == null) {
                    rttEstimator.recordTestRequestSent(testRequestId, clock.nanoTime(), clock.nowEpochNanos());
                    pendingTestRequestSentInEpochSeconds = now.getEpochSeconds();
                }
            }, null, null);
        }
        if (isHeartBeatSendingRequired(now)) {
            fixSession.send(fixAdminMessagesCodec.generateHeartbeat(null), null);
        }
    }

    /**
     * A TestRequest(35=1) asked for by the application rather than by a quiet line.
     */
    void sendTestRequest(String testRequestId) {
        fixSession.send(fixAdminMessagesCodec.generateTestRequest(testRequestId), null);
    }

    @SchedulerThread
    private void sendRttMeasurementProbe() {
        if (!fixSessionStateComponent.isLoggedIn()) {
            return;
        }
        String testReqId = fixSessionSettings.getRttMeasurementSettings().getProbeTestReqIdPrefix() + System.currentTimeMillis();
        fixSession.send(fixAdminMessagesCodec.generateTestRequest(testReqId), null, (sendingError, p1, p2) -> {
            if (sendingError == null) {
                rttEstimator.recordTestRequestSent(testReqId, clock.nanoTime(), clock.nowEpochNanos());
            }
        }, null, null);
    }

    private boolean isTestRequestRequired(UTCTime now) {
        return lastMessageReceivedInEpochSeconds + heartbeatInterval < now.getEpochSeconds();
    }

    private boolean isHeartBeatSendingRequired(UTCTime now) {
        return lastMessageSentInEpochSeconds + heartbeatInterval <= now.getEpochSeconds();
    }

    private boolean isTestRequestResponseTimedOut(UTCTime now) {
        return pendingTestRequestSentInEpochSeconds > 0
                && lastMessageReceivedInEpochSeconds < pendingTestRequestSentInEpochSeconds
                && pendingTestRequestSentInEpochSeconds + heartbeatInterval < now.getEpochSeconds();
    }
}
