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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.betty.api.Client;
import org.lolaf.betty.api.ClientBuilder;
import org.lolaf.betty.api.io.IOEventsListener;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.betty.api.io.IOWorkersGroup;
import org.lolaf.betty.api.settings.IOWorkersGroupSettings;
import org.lolaf.betty.api.stats.IOStats;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixInitiator;
import org.lolaf.staffix.api.FixInitiatorBuilder;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.admin.AdminApi.ResetFixSessionMode;
import org.lolaf.staffix.api.admin.FixInitiatorTargets;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;
import org.lolaf.staffix.impl.executor.MessageExecutorsRuntime;
import org.lolaf.staffix.impl.session.FixSessionImpl;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.lolaf.staffix.impl.FixAcceptorImpl.*;

/**
 * The initiator: dials out, and keeps dialling.
 *
 * <p>Reconnect is the normal case rather than an error path - a counterparty restarts, a network blips - so the
 * session outlives the connection and resumes on the sequence numbers the store kept.
 */
@Slf4j
public class FixInitiatorImpl extends Startable.SimpleStartable<FixInitiator> implements FixInitiator, FixSessionAdminControl,
        FixSessionsSettingsStore.Listener {

    private final FixInitiatorBuilder fixInitiatorBuilder;
    private final MessageExecutorsRuntime messageExecutorsRuntime;
    private final Function<FixSessionSettings, FixSessionRuntimeDependencies> fixSessionRuntimeDependencies;
    private final FixSessionsObserver fixSessionsObserver;
    private final List<FixSessionsSettingsStore> fixSessionsSettingsStores;
    private final Map<FixSessionId, FixSessionSettings> targetsSettings;
    private final Object lifecycleLock;
    private FixInitiatorTarget activeFixInitiatorTarget;
    private FixSessionSettings fixSessionSettings;
    private ScheduledExecutorService scheduledExecutorService;
    private FixSessionImpl fixSession;
    private Client ioClient;
    private boolean targetRunning;

    FixInitiatorImpl(FixInitiatorBuilder fixInitiatorBuilder, Function<FixSessionSettings, FixSessionRuntimeDependencies> fixRuntimeDependenciesProvider,
                     List<FixSessionsSettingsStore> fixSessionsSettingsStores, FixSessionsObserver fixSessionsObserver) {
        validateTargets(fixInitiatorBuilder, fixSessionsSettingsStores);
        this.targetsSettings = new LinkedHashMap<>();
        fixInitiatorBuilder.getTargets().forEach(target -> targetsSettings.put(target.getFixSessionId(),
                findSettings(target.getFixSessionId(), fixSessionsSettingsStores).orElseThrow()));
        this.lifecycleLock = new Object();
        this.activeFixInitiatorTarget = fixInitiatorBuilder.getTargets().get(0);
        this.fixSessionSettings = targetsSettings.get(activeFixInitiatorTarget.getFixSessionId());
        this.fixSessionsSettingsStores = List.copyOf(fixSessionsSettingsStores);
        this.fixSessionRuntimeDependencies = fixRuntimeDependenciesProvider;
        this.fixSessionsObserver = fixSessionsObserver;
        this.fixInitiatorBuilder = fixInitiatorBuilder;
        this.messageExecutorsRuntime = new MessageExecutorsRuntime(fixInitiatorBuilder.getMessageExecutorSettings().toBuilder()
                .instanceId(fixInitiatorBuilder.getInstanceId())
                .build());
    }

    private static void validateTargets(FixInitiatorBuilder fixInitiatorBuilder, List<FixSessionsSettingsStore> fixSessionsSettingsStores) {
        if (fixInitiatorBuilder.getMainTarget() == null) {
            throw new IllegalStateException("FixInitiatorBuilder requires a mainTarget");
        }
        String instanceId = fixInitiatorBuilder.getInstanceId();
        Set<FixSessionId> fixSessionIds = new HashSet<>();
        List<FixSessionId> unknownFixSessionIds = new ArrayList<>();
        for (FixInitiatorTarget target : fixInitiatorBuilder.getTargets()) {
            FixSessionId fixSessionId = target.getFixSessionId();
            if (fixSessionId == null) {
                throw new IllegalStateException("Initiator '" + instanceId + "' has a target without a FixSessionId");
            }
            if (!fixSessionIds.add(fixSessionId)) {
                throw new IllegalStateException("Initiator '" + instanceId + "' configures FIX session " + fixSessionId
                        + " more than once, give its other addresses to the same target instead");
            }
            if (target.getConnectAddresses().isEmpty()) {
                throw new IllegalStateException("Initiator '" + instanceId + "' has no connect address for FIX session " + fixSessionId);
            }
            if (findSettings(fixSessionId, fixSessionsSettingsStores).isEmpty()) {
                unknownFixSessionIds.add(fixSessionId);
            }
        }
        if (!unknownFixSessionIds.isEmpty()) {
            throw new IllegalStateException("Unable to find any FixSessionSettings in stores for fix sessions "
                    + unknownFixSessionIds + ", available fix session ids are: " +
                    fixSessionsSettingsStores.stream().flatMap(s -> s.getSettings().stream()).map(FixSessionSettings::getFixSessionId).collect(Collectors.toList()));
        }
    }

    private static Optional<FixSessionSettings> findSettings(FixSessionId fixSessionId, List<FixSessionsSettingsStore> fixSessionsSettingsStores) {
        return fixSessionsSettingsStores.stream()
                .flatMap(s -> s.find(fixSessionId, FixSession.FixSessionType.INITIATOR).stream())
                .findFirst();
    }

    @Override
    public boolean isConnected() {
        return fixSession.isConnected();
    }

    @Override
    public FixSession getSession() {
        return fixSession;
    }

    @Override
    public List<FixSessionId> getFixSessionIds() {
        return fixInitiatorBuilder.getTargets().stream()
                .map(FixInitiatorTarget::getFixSessionId)
                .collect(Collectors.toList());
    }

    @Override
    public void switchTo(FixSessionId fixSessionId) {
        FixInitiatorTarget target = fixInitiatorBuilder.getTargets().stream()
                .filter(t -> t.getFixSessionId().equals(fixSessionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("FIX session " + fixSessionId + " is not a target of initiator '"
                        + fixInitiatorBuilder.getInstanceId() + "', its targets are: " + getFixSessionIds()));
        synchronized (lifecycleLock) {
            if (activeFixInitiatorTarget.getFixSessionId().equals(fixSessionId)) {
                return;
            }
            FixSessionSettings targetSettings = targetsSettings.get(fixSessionId);
            if (targetSettings == null) {
                throw new IllegalStateException("FIX session " + fixSessionId + " settings were removed from its store");
            }
            // start() and stop() flip isStarted before taking the lock, so only targetRunning says a target runs
            boolean running = isStarted() && targetRunning;
            log.info("Switching initiator '{}' from FIX session {} to {}", fixInitiatorBuilder.getInstanceId(),
                    activeFixInitiatorTarget.getFixSessionId(), fixSessionId);
            if (running) {
                stopTarget("Fix initiator switching to " + fixSessionId, Deadline.of(fixInitiatorBuilder.getShutdownMaxDelay()));
            }
            activeFixInitiatorTarget = target;
            fixSessionSettings = targetSettings;
            if (running) {
                startSwitchedTarget();
            }
        }
    }

    private void startSwitchedTarget() {
        try {
            startTarget();
        } catch (RuntimeException failure) {
            log.warn("Initiator '{}' failed to start FIX session {} after a switch, stopping it", fixInitiatorBuilder.getInstanceId(),
                    fixSessionSettings.getFixSessionId());
            stop(Deadline.of(fixInitiatorBuilder.getShutdownMaxDelay()));
            throw failure;
        }
    }

    FixInitiatorTargets getFixInitiatorTargets() {
        synchronized (lifecycleLock) {
            return FixInitiatorTargets.builder()
                    .instanceId(fixInitiatorBuilder.getInstanceId())
                    .activeFixSessionId(activeFixInitiatorTarget.getFixSessionId())
                    .targets(fixInitiatorBuilder.getTargets())
                    .build();
        }
    }

    private ScheduledExecutorService getScheduler() {
        return fixInitiatorBuilder.getScheduledExecutorService() != null ? fixInitiatorBuilder.getScheduledExecutorService() : scheduledExecutorService;
    }

    @Override
    protected void startMe() throws StartStopException {
        synchronized (lifecycleLock) {
            log.info("Starting initiator for FIX session {}", fixSessionSettings.getFixSessionId());
            startInitiatorResources();
            try {
                startTarget();
            } catch (RuntimeException failure) {
                // a failed start() never reaches stopMe
                fixSessionsSettingsStores.forEach(s -> s.unregister(this));
                stopInitiatorResources(Deadline.of(fixInitiatorBuilder.getShutdownMaxDelay()));
                throw failure;
            }
            log.info("Started initiator for FIX session {}", fixSessionSettings.getFixSessionId());
        }
    }

    private void startInitiatorResources() {
        if (fixInitiatorBuilder.getScheduledExecutorService() == null) {
            scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Staffix-client-scheduler-" + fixInitiatorBuilder.getInstanceId());
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((t1, e) -> log.error("Uncaught exception occurred in thread {}", t1, e));
                return t;
            });
        }
        messageExecutorsRuntime.start();
        fixSessionsSettingsStores.forEach(store -> store.register(this));
    }

    private void startTarget() {
        FixSessionRuntimeDependencies runtimeDependencies = fixSessionRuntimeDependencies.apply(fixSessionSettings);
        fixSession = new FixSessionImpl(fixInitiatorBuilder.getInstanceId(), fixSessionSettings,
                runtimeDependencies, getScheduler(), fixInitiatorBuilder.getIoSettings(), messageExecutorsRuntime, fixInitiatorBuilder.getClock());
        boolean registered = false;
        try {
            fixSession.start(runtimeDependencies);
            ioClient = newIoClient();
            // Announce the session before the client starts dialling: this is what puts it in the engine's
            // FixSessionRegistry, and a session that is already connecting must not be missing from it.
            fixSessionsObserver.onSessionRegistered(fixSession);
            registered = true;
            ioClient.start();
        } catch (RuntimeException failure) {
            releaseFailedTarget(registered, failure);
            throw failure;
        }
        targetRunning = true;
    }

    private void releaseFailedTarget(boolean registered, RuntimeException failure) {
        Deadline releaseDeadline = Deadline.of(fixInitiatorBuilder.getShutdownMaxDelay());
        try {
            if (registered) {
                fixSessionsObserver.onSessionUnregistered(fixSession);
            }
            if (ioClient != null) {
                ioClient.stop(releaseDeadline.fromRemainingTime(0.5));
            }
            fixSession.stopProtocol("Fix initiator failed to start", releaseDeadline.fromRemainingTime(0.7));
        } catch (RuntimeException stopFailure) {
            failure.addSuppressed(stopFailure);
        } finally {
            ioClient = null;
        }
        try {
            fixSession.releaseResources(releaseDeadline);
        } catch (RuntimeException releaseFailure) {
            failure.addSuppressed(releaseFailure);
        }
    }

    private Client newIoClient() {
        IOWorkersGroup ioWorkerGroup = fixInitiatorBuilder.getIoWorkersGroup();
        if (ioWorkerGroup == null) {
            ioWorkerGroup = IOWorkersGroupSettings.builder().id(fixInitiatorBuilder.getInstanceId()).build().newInstance();
        }
        boolean hasConfiguredPluginsWithTimeMeasurementRequired = fixSession.hasConfiguredPluginsWithTimeMeasurementRequired();
        return ClientBuilder.builder()
                .id(fixInitiatorBuilder.getInstanceId())
                .SSLSettings(fixInitiatorBuilder.getSslSettings())
                .connectAddresses(activeFixInitiatorTarget.getConnectAddresses())
                .scheduledExecutorService(getScheduler())
                .connectionRetry(fixInitiatorBuilder.getConnectionRetry())
                .ioSettings(fixInitiatorBuilder.getIoSettings().toBuilder()
                        .readDirectBuffer(false) // make sure we don't use direct buffer as we need access to underlying byte array when parsing message for perfs reason
                        .writeIoBufferPoolSettings(addBufferedWritesPoolZoneIfNeeded(fixInitiatorBuilder.getIoSettings()))
                        .socketOptions(addTcpOptionsIfNeeded(fixInitiatorBuilder.getIoSettings()))
                        .trackReceiveTime(hasConfiguredPluginsWithTimeMeasurementRequired)
                        .build())
                .ioWorkersGroup(ioWorkerGroup)
                .ioStatsProvider(getIoStatsProvider(hasConfiguredPluginsWithTimeMeasurementRequired))
                .ioEventsListener(new IOEventsListenerImpl(fixSession)).build().newInstance();
    }

    private IOStats.IOStatsProvider getIoStatsProvider(boolean monitoringEnabled) {
        if (!monitoringEnabled) {
            return newIoSession -> new IOStats() {
                @Override
                public long getTimeInNanos(Operation operation) {
                    return 0;
                }

                @Override
                public void onMessageSent(IOSession ioSession, ByteBuffer sentMessage, Object messageSendingContext, long localSendingStartTimeInNanos) {
                    fixSession.onMessageSent(sentMessage, messageSendingContext, localSendingStartTimeInNanos);
                }
            };
        }
        return newIoSession -> new IOStats() {
            @Override
            public long getTimeInNanos(Operation operation) {
                if (operation.equals(Operation.IO_MESSAGE_WRITE)) {
                    return System.nanoTime();
                }
                return 0;
            }

            @Override
            public void onMessageSent(IOSession ioSession, ByteBuffer sentMessage, Object messageSendingContext, long localSendingStartTimeInNanos) {
                fixSession.onMessageSent(sentMessage, messageSendingContext, localSendingStartTimeInNanos);
            }
        };
    }

    @Override
    public FixInitiator stop() {
        return stop(Deadline.of(fixInitiatorBuilder.getShutdownMaxDelay()));
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        synchronized (lifecycleLock) {
            log.info("Stopping initiator for FIX session {}", fixSessionSettings.getFixSessionId());
            fixSessionsSettingsStores.forEach(s -> s.unregister(this));
            stopTarget("Fix initiator stop", stopDeadline);
            stopInitiatorResources(stopDeadline);
            log.info("Stopped initiator for FIX session {}", fixSessionSettings.getFixSessionId());
        }
    }

    private void stopInitiatorResources(Deadline stopDeadline) {
        scheduledExecutorService = stopOwnSchedulerIfNeeded(scheduledExecutorService, fixInitiatorBuilder.getInstanceId(),
                stopDeadline.fromRemainingTime(0.3));
        messageExecutorsRuntime.stop(stopDeadline);
    }

    private void stopTarget(String reason, Deadline stopDeadline) {
        if (!targetRunning) {
            return;
        }
        targetRunning = false;
        fixSessionsObserver.onSessionUnregistered(fixSession);
        fixSession.stopProtocol(reason, stopDeadline.fromRemainingTime(0.7));
        ioClient.stop(stopDeadline.fromRemainingTime(0.8));
        fixSession.releaseResources(stopDeadline);
        ioClient = null;
    }

    private boolean isTarget(FixSessionSettings settings) {
        return settings.getFixSessionType() == FixSession.FixSessionType.INITIATOR
                && fixInitiatorBuilder.getTargets().stream().anyMatch(t -> t.getFixSessionId().equals(settings.getFixSessionId()));
    }

    private boolean isActive(FixSessionSettings settings) {
        return activeFixInitiatorTarget.getFixSessionId().equals(settings.getFixSessionId());
    }

    @Override
    public void onAddedSession(FixSessionSettings settings) {
        synchronized (lifecycleLock) {
            // targets and their settings are resolved at build time, so an add is a re-add of a removed one
            if (isTarget(settings)) {
                targetsSettings.put(settings.getFixSessionId(), settings);
                if (isActive(settings)) {
                    fixSessionSettings = settings;
                }
            }
        }
    }

    @Override
    public void onRemovedSession(FixSessionSettings settings) {
        synchronized (lifecycleLock) {
            if (!isTarget(settings)) {
                return;
            }
            targetsSettings.remove(settings.getFixSessionId());
            if (isActive(settings) && settings.isDisconnectOnRemove() && isStarted() && fixSession != null) {
                log.info("FIX session {} settings removed from the store, disconnecting", settings.getFixSessionId());
                stop(Deadline.of(fixInitiatorBuilder.getShutdownMaxDelay()));
            }
        }
    }

    /**
     * The policy comes from {@code oldSettings} - the ones the session is running under - see
     * {@link FixSessionSettings#isRestartLiveSessionOnUpdate()}.
     */
    @Override
    public void onUpdatedSession(FixSessionSettings oldSettings, FixSessionSettings newSettings) {
        synchronized (lifecycleLock) {
            if (!isTarget(oldSettings)) {
                return;
            }
            targetsSettings.put(newSettings.getFixSessionId(), newSettings);
            if (!isActive(newSettings)) {
                return;
            }
            fixSessionSettings = newSettings;
            boolean live = isStarted() && fixSession != null && fixSession.isConnected();
            if (live && oldSettings.isRestartLiveSessionOnUpdate()) {
                log.info("FIX session {} settings updated, restarting the live session", newSettings.getFixSessionId());
                stop(Deadline.of(fixInitiatorBuilder.getShutdownMaxDelay()));
                start();
            }
            // otherwise the new settings are held and the next connection is built from them
        }
    }

    // AdminApi implementation

    private void validateSessionId(FixSessionId fixSessionId) {
        if (!fixSessionSettings.getFixSessionId().equals(fixSessionId)) {
            throw new IllegalArgumentException("Unknown session ID: " + fixSessionId
                    + ", managed session is: " + fixSessionSettings.getFixSessionId());
        }
    }

    @Override
    public void logonSession(FixSessionId fixSessionId) {
        validateSessionId(fixSessionId);
        fixSession.logon();
    }

    @Override
    public void logoutSession(FixSessionId fixSessionId) {
        validateSessionId(fixSessionId);
        fixSession.logoutPermanently("Admin API logout");
    }

    @Override
    public void resetSession(FixSessionId fixSessionId, ResetFixSessionMode resetFixSessionMode) {
        validateSessionId(fixSessionId);
        fixSession.adminResetSequence(resetFixSessionMode);
    }

    @Override
    public void setIncomingSeqNum(FixSessionId fixSessionId, long incomingSeqNum) {
        validateSessionId(fixSessionId);
        fixSession.adminSetIncomingSeqNum(incomingSeqNum);
    }

    @Override
    public void setOutgoingSeqNum(FixSessionId fixSessionId, long outgoingSeqNum) {
        validateSessionId(fixSessionId);
        fixSession.adminSetOutgoingSeqNum(outgoingSeqNum);
    }

    @Override
    public void sendFixMessage(FixSessionId fixSessionId, String fixMessage, char separator, boolean possDupFlag) {
        validateSessionId(fixSessionId);
        fixSession.adminSendFixMessage(fixMessage, separator, possDupFlag);
    }

    @Override
    public long getIncomingSeqNum(FixSessionId fixSessionId) {
        validateSessionId(fixSessionId);
        return fixSession.adminGetIncomingSeqNum();
    }

    @Override
    public long getOutgoingSeqNum(FixSessionId fixSessionId) {
        validateSessionId(fixSessionId);
        return fixSession.adminGetOutgoingSeqNum();
    }

    @Override
    public List<FixSessionSettings> getManagedFixSessionsSettings() {
        return List.of(fixSessionSettings);
    }

    @Override
    public List<FixSession> getManagedFixSessions() {
        return fixSession == null ? List.of() : List.of(fixSession);
    }

    @Override
    public boolean isInitiator() {
        return true;
    }

    private static class IOEventsListenerImpl implements IOEventsListener {

        private final FixSessionImpl fixSession;
        private List<Certificate> remotePeerCertificates;

        public IOEventsListenerImpl(FixSessionImpl fixSession) {
            this.fixSession = fixSession;
        }

        @Override
        public void onSSLHandshake(IOSession session, Certificate[] remotePeerCertificates) {
            if (remotePeerCertificates != null) {
                this.remotePeerCertificates = Arrays.asList(remotePeerCertificates);
            }
        }

        @Override
        public void onTask(IOSession session, Runnable task, BiConsumer<Runnable, Exception> taskCallback) {
            fixSession.onIOThreadTask(task, taskCallback);
        }

        @Override
        public boolean isReadyToConnect(InetSocketAddress remoteAddress) {
            return fixSession.isReadyToConnect();
        }

        @Override
        public void onConnected(IOSession session) {
            if (!fixSession.onConnection(session, remotePeerCertificates)) {
                fixSession.disconnect();
            }
            remotePeerCertificates = null;
        }

        @Override
        public void onDisconnected(IOSession session) {
            fixSession.onDisconnection();
        }

        @Override
        public void onRead(IOSession session, ByteBuffer message, long localReceiveTimeInNanos) {
            fixSession.onMessageRead(message, localReceiveTimeInNanos);
        }

        @Override
        public void onWatermarkEvent(IOSession session, boolean highWatermarkReached, long bytesLeftToWrite) {
            fixSession.onWatermarkEvent(highWatermarkReached, bytesLeftToWrite);
        }
    }
}