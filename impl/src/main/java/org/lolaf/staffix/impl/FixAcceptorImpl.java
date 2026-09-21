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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.betty.api.Server;
import org.lolaf.betty.api.ServerBuilder;
import org.lolaf.betty.api.io.IOEventsListener;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.betty.api.io.IOWorkersGroup;
import org.lolaf.betty.api.settings.IOBufferPoolSettings;
import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.betty.api.settings.IOWorkersGroupSettings;
import org.lolaf.betty.api.stats.IOStats;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.api.FixAcceptor;
import org.lolaf.staffix.api.FixAcceptorBuilder;
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.admin.AdminApi.ResetFixSessionMode;
import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.codec.FixMessageEncodersPool;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.*;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.api.version.FixVersion;
import org.lolaf.staffix.api.version.FixtVersion;
import org.lolaf.staffix.codec.serde.IntSerde;
import org.lolaf.staffix.impl.executor.MessageExecutorsRuntime;
import org.lolaf.staffix.impl.session.FixSessionImpl;

import javax.net.ssl.SSLHandshakeException;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The acceptor: one listening socket, and a session per counterparty that logs on to it.
 *
 * <p>An inbound connection is anonymous until its Logon arrives, so the CompIDs are read straight out of the
 * raw bytes to find which configured session it is - before any decoder exists for it.
 */
@Slf4j
public class FixAcceptorImpl extends Startable.SimpleStartable<FixAcceptor> implements FixAcceptor, FixSessionAdminControl, FixSessionsSettingsStore.Listener {

    // short window to let a best-effort rejection reply (Logout) flush before the connection is torn down
    private static final Duration REJECTION_REPLY_FLUSH_DELAY = Duration.ofSeconds(1);

    private static final int DEFAULT_BUFFERED_WRITES_BUFFER_SIZE = Integer.parseInt(System.getProperty("staffix.DefaultBufferedWritesBufferSize", "8192"));
    private static final int DEFAULT_BUFFERED_WRITES_POOL_SIZE = Integer.parseInt(System.getProperty("staffix.DefaultBufferedWritesPoolSize", "4"));

    private final Set<FixSessionImpl> connectedSessions;
    private final Set<FixSessionSettings> configuredSessionsSettings;
    private final Map<FixSessionId, FixSessionImpl> configuredSessions;
    private final Map<Class<?>, String> encodersPoolIdsForBroadCasting;
    private final MessageExecutorsRuntime messageExecutorsRuntime;
    private final FixAcceptorBuilder fixAcceptorBuilder;
    private final IdleStrategy broadcastMessageIdleStrategy;
    private final Function<FixSessionSettings, FixSessionRuntimeDependencies> fixRuntimeDependenciesSupplier;
    private final Collection<FixSessionsSettingsStore> fixSessionsSettingsStores;
    private final FixSessionsObserver fixSessionsObserver;
    private FixSessionImpl[] connectedSessionsArray;
    private ScheduledExecutorService scheduledExecutorService;
    private Server ioServer;
    private boolean shuttingDown;

    FixAcceptorImpl(FixAcceptorBuilder fixAcceptorBuilder, Function<FixSessionSettings, FixSessionRuntimeDependencies> fixRuntimeDependenciesSupplier,
                    Collection<FixSessionsSettingsStore> fixSessionsSettingsStores, FixSessionsObserver fixSessionsObserver) {
        this.configuredSessionsSettings = new HashSet<>();
        this.connectedSessions = Collections.synchronizedSet(new HashSet<>());
        this.connectedSessionsArray = connectedSessions.toArray(new FixSessionImpl[0]);
        this.configuredSessions = new ConcurrentHashMap<>();
        this.encodersPoolIdsForBroadCasting = new IdentityHashMap<>();
        this.fixAcceptorBuilder = fixAcceptorBuilder;
        this.fixRuntimeDependenciesSupplier = fixRuntimeDependenciesSupplier;
        this.fixSessionsObserver = fixSessionsObserver;

        this.broadcastMessageIdleStrategy = new BackoffIdleStrategy();
        this.messageExecutorsRuntime = new MessageExecutorsRuntime(fixAcceptorBuilder.getMessageExecutorSettings().toBuilder()
                .instanceId(fixAcceptorBuilder.getInstanceId())
                .build());

        List<String> targetFixSessionsSettingsStores = fixAcceptorBuilder.getTargetFixSessionsSettingsStoreInstancesIds();
        if (targetFixSessionsSettingsStores.isEmpty()) {
            targetFixSessionsSettingsStores = List.of(InstanceProvider.DEFAULT_INSTANCE_ID);
        }
        this.fixSessionsSettingsStores = new ArrayList<>();
        for (String instanceId : targetFixSessionsSettingsStores) {
            this.fixSessionsSettingsStores.add(fixSessionsSettingsStores.stream().filter(s -> s.getInstanceId().equals(instanceId))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unable to find a FixSessionsSettingsStore instance id " + instanceId)));
        }
    }

    static IOBufferPoolSettings addBufferedWritesPoolZoneIfNeeded(IOSettings ioSettings) {
        IOBufferPoolSettings ioBufferPoolSettings = ioSettings.getWriteIoBufferPoolSettings();
        if (ioBufferPoolSettings.getZones().stream().anyMatch(z -> z.getBufferSize() >= DEFAULT_BUFFERED_WRITES_BUFFER_SIZE)) {
            return ioBufferPoolSettings;
        }
        return ioSettings.getWriteIoBufferPoolSettings().toBuilder()
                .zone(IOBufferPoolSettings.IOBufferPoolZone.builder()
                        .bufferSize(DEFAULT_BUFFERED_WRITES_BUFFER_SIZE)
                        .poolSize(DEFAULT_BUFFERED_WRITES_POOL_SIZE)
                        .build()).build();
    }

    static Map<SocketOption, Object> addTcpOptionsIfNeeded(IOSettings ioSettings) {
        Map<SocketOption, Object> opts = ioSettings.getSocketOptions();
        if (!opts.isEmpty() && !opts.containsKey(StandardSocketOptions.TCP_NODELAY)) {
            log.info("Provided socket options settings does not contain TCP_NODELAY, enable it for optimal network latency");
        }
        return opts.isEmpty() ? Map.of(StandardSocketOptions.TCP_NODELAY, true) : opts;
    }

    static ScheduledExecutorService stopOwnSchedulerIfNeeded(ScheduledExecutorService ownScheduler, String instanceId, Deadline deadline) {
        if (ownScheduler == null) {
            return null;
        }
        ownScheduler.shutdownNow();
        try {
            // Deadline floors at zero and awaitTermination(0) does not wait at all
            if (!ownScheduler.awaitTermination(Math.max(1L, deadline.getRemainingTime().toMillis()), TimeUnit.MILLISECONDS)) {
                log.warn("Timed out waiting for the scheduler of {} to stop", instanceId);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /**
     * @return whether the settings describe a session this acceptor manages, as opposed to the other end of one that
     * happens to share the settings store.
     */
    private static boolean isAcceptorSession(FixSessionSettings settings) {
        return settings.getFixSessionType() == FixSession.FixSessionType.ACCEPTOR;
    }

    @Override
    protected void startMe() throws StartStopException {
        if (fixAcceptorBuilder.getScheduledExecutorService() == null) {
            scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Staffix-server-scheduler-" + fixAcceptorBuilder.getInstanceId());
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((t1, e) -> log.error("Uncaught exception occurred in thread {}", t1, e));
                return t;
            });
        }
        messageExecutorsRuntime.start();
        fixSessionsSettingsStores.forEach(store -> {
            // Only this acceptor's own sessions: a store may hold both ends of a session - JvmWarmup and the Spring
            // Boot starter both run a loopback pair off one store - and an acceptor standing up the INITIATOR-typed
            // entries too would build a full FixSessionImpl for each, decoders, logger and plugins included, that
            // nothing ever connects and that then shows up in getSessions() and broadcast. The type is already the
            // contract elsewhere: FixInitiatorImpl resolves its settings with find(id, INITIATOR), and
            // findTargetSession looks a connection up with find(id, ACCEPTOR).
            List<FixSessionSettings> settings = store.getSettings().stream()
                    .filter(FixAcceptorImpl::isAcceptorSession)
                    .collect(Collectors.toList());
            configuredSessionsSettings.addAll(settings);
            settings.forEach(this::setupNewFixSession);
            store.register(this);
        });

        IOWorkersGroup ioWorkerGroup = fixAcceptorBuilder.getIoWorkersGroup();
        if (ioWorkerGroup == null) {
            ioWorkerGroup = IOWorkersGroupSettings.builder()
                    .id(fixAcceptorBuilder.getInstanceId())
                    .build().newInstance();
        }

        boolean monitoringEnabled = configuredSessions.values().stream().anyMatch(FixSessionImpl::hasConfiguredPluginsWithTimeMeasurementRequired);
        ioServer = ServerBuilder.builder()
                .id(fixAcceptorBuilder.getInstanceId())
                .ioSettings(fixAcceptorBuilder.getIoSettings().toBuilder()
                        .readDirectBuffer(false) // make sure we don't use direct buffer as we need access to underlying byte array when parsing message for perfs reason
                        .writeIoBufferPoolSettings(addBufferedWritesPoolZoneIfNeeded(fixAcceptorBuilder.getIoSettings()))
                        .socketOptions(addTcpOptionsIfNeeded(fixAcceptorBuilder.getIoSettings()))
                        .trackReceiveTime(monitoringEnabled)
                        .build())
                .bindAddress(fixAcceptorBuilder.getBindAddress())
                .serverSSLSettings(fixAcceptorBuilder.getServerSSLSettings())
                .ioWorkersGroup(ioWorkerGroup)
                .acceptorIoWorkerGroup(fixAcceptorBuilder.getAcceptorIoWorkerGroup())
                .ioEventsListener(new IOEventsListenerImpl())
                .ioStatsProvider(getIoStatsProvider(monitoringEnabled))
                .build().newInstance();
        shuttingDown = false;
        ioServer.start();
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
                    FixSessionImpl fixSession = ioSession.getAttachment();
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
                FixSessionImpl fixSession = ioSession.getAttachment();
                fixSession.onMessageSent(sentMessage, messageSendingContext, localSendingStartTimeInNanos);
            }
        };
    }

    private ScheduledExecutorService getScheduler() {
        return fixAcceptorBuilder.getScheduledExecutorService() != null ? fixAcceptorBuilder.getScheduledExecutorService() : scheduledExecutorService;
    }

    private void setupNewFixSession(FixSessionSettings settings) {
        FixSessionRuntimeDependencies runtimeDependencies = fixRuntimeDependenciesSupplier.apply(settings);
        FixSessionImpl fixSessionImpl = new FixSessionImpl(fixAcceptorBuilder.getInstanceId(), settings,
                runtimeDependencies, getScheduler(), fixAcceptorBuilder.getIoSettings(), messageExecutorsRuntime, fixAcceptorBuilder.getClock());
        fixSessionImpl.start(runtimeDependencies);
        configuredSessions.put(settings.getFixSessionId(), fixSessionImpl);
        fixSessionsObserver.onSessionRegistered(fixSessionImpl);
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        shuttingDown = true;
        ioServer.stopListening();
        fixSessionsSettingsStores.forEach(s -> s.unregister(this));
        configuredSessions.values().forEach(fixSessionsObserver::onSessionUnregistered);

        stopSessionsProtocol(stopDeadline);
        ioServer.stop(stopDeadline);
        scheduledExecutorService = stopOwnSchedulerIfNeeded(scheduledExecutorService, fixAcceptorBuilder.getInstanceId(),
                stopDeadline.fromRemainingTime(0.3));
        messageExecutorsRuntime.stop(stopDeadline);

        ioServer = null;
        connectedSessions.clear();
        connectedSessionsArray = connectedSessions.toArray(new FixSessionImpl[0]);
        configuredSessions.clear();
        configuredSessionsSettings.clear();
        log.info("FIX server {} is stopped", fixAcceptorBuilder.getInstanceId());
    }

    /**
     * Logs every session out and takes its connection down, holding nothing back: the stores and the loggers stay open
     * until {@link #stopMe(Deadline)} has stopped the IO server and each session has released its own resources, so a
     * message still being delivered while this runs is recorded rather than written into released memory.
     */
    private void stopSessionsProtocol(Deadline stopDeadline) {
        if (configuredSessions.isEmpty()) {
            return;
        }
        ExecutorService sessionsStopService = Executors.newFixedThreadPool(
                Math.min(configuredSessions.size(), Runtime.getRuntime().availableProcessors()),
                r -> new Thread(r, "FIX-server-sessions-parallel-shutdown"));
        Deadline sessionStopDeadline = stopDeadline.fromRemainingTime(0.7);
        CountDownLatch countDownLatch = new CountDownLatch(configuredSessions.size());
        log.info("FIX server stopping {} FIX sessions", configuredSessions.size());
        configuredSessions.values().forEach(fixSession ->
                sessionsStopService.submit(() -> {
                    try {
                        fixSession.stop("FIX server stop", sessionStopDeadline);
                    } finally {
                        countDownLatch.countDown();
                    }
                }));
        try {
            Duration remainingTime = sessionStopDeadline.getRemainingTime();
            if (countDownLatch.await(remainingTime.toMillis(), TimeUnit.MILLISECONDS)) {
                log.info("FIX server sessions are stopped");
            } else {
                log.warn("FIX server sessions hare still not stopped after {} ms, continuing shutdown sequence", remainingTime.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sessionsStopService.shutdown();
    }

    @Override
    public FixAcceptor stop() {
        return stop(Deadline.of(fixAcceptorBuilder.getShutdownMaxDelay()));
    }

    @Override
    public Set<FixSession> getConnectedSessions() {
        return Collections.unmodifiableSet(connectedSessions);
    }

    @Override
    public Set<FixSession> getSessions() {
        return new HashSet<>(configuredSessions.values());
    }

    @Override
    public void broadcast(FixMessageEncoder<?> encoder, UTCTime sendingTime, Predicate<FixSession> fixSessionPredicate, boolean connectedSessionsOnly) {
        Stream<FixSessionImpl> targetSessions = connectedSessionsOnly ? Arrays.stream(connectedSessionsArray) : configuredSessions.values().stream();
        String poolId = encodersPoolIdsForBroadCasting.get(encoder.getClass());
        if (poolId == null) {
            poolId = "broadcast_" + encoder.getClass().getSimpleName();
            encodersPoolIdsForBroadCasting.put(encoder.getClass(), poolId);
        }
        String poolIdFinal = poolId;
        if (fixSessionPredicate == null) {
            targetSessions.forEach(s -> sendBroadcastedMessage(poolIdFinal, encoder, sendingTime, s));
        } else {
            targetSessions.forEach(s -> {
                if (fixSessionPredicate.test(s)) {
                    sendBroadcastedMessage(poolIdFinal, encoder, sendingTime, s);
                }
            });
        }
        encoder.release();
    }

    private void sendBroadcastedMessage(String poolId, FixMessageEncoder<?> encoder, UTCTime sendingTime, FixSessionImpl s) {
        FixMessageEncodersPool<?> pool = s.newEncodersPool(poolId, false, encoder.getClass());
        s.send(pool.borrowBlocking(broadcastMessageIdleStrategy).begin().copy(encoder), sendingTime);
    }

    @Override
    public Set<FixSessionSettings> getConfiguredSessionsSettings() {
        return Collections.unmodifiableSet(configuredSessionsSettings);
    }

    @Override
    public void onAddedSession(FixSessionSettings settings) {
        // Same filter as the initial load: a store adding an initiator session at runtime is not this acceptor's.
        if (isAcceptorSession(settings)) {
            // Kept in step with onRemovedSession, which takes the settings back out: without this a session added
            // after start up was managed but absent from getConfiguredSessionsSettings().
            configuredSessionsSettings.add(settings);
            setupNewFixSession(settings);
        }
    }

    @Override
    public void onRemovedSession(FixSessionSettings settings) {

        stopManaging(settings, settings.isDisconnectOnRemove());
    }

    /**
     * Whether the settings replacing the current ones can be applied without touching the running session.
     * <p>
     * The policy comes from {@code oldSettings} - the ones the session is running under - because it describes how
     * that session may be treated, and that session is the one a restart would disturb. See
     * {@link FixSessionSettings#isRestartLiveSessionOnUpdate()}.
     */
    @Override
    public void onUpdatedSession(FixSessionSettings oldSettings, FixSessionSettings newSettings) {
        if (!isAcceptorSession(oldSettings) && !isAcceptorSession(newSettings)) {
            return;
        }
        FixSessionImpl runningSession = configuredSessions.get(oldSettings.getFixSessionId());
        boolean restart = oldSettings.isRestartLiveSessionOnUpdate() || runningSession == null
                || !runningSession.isConnected();
        if (restart) {
            stopManaging(oldSettings, true);
            onAddedSession(newSettings);
            return;
        }
        // the running session keeps the settings it was created with; the new ones are managed and are what the next
        // session created for this id will be built from
        configuredSessionsSettings.remove(oldSettings);
        configuredSessionsSettings.add(newSettings);
    }

    /**
     * Takes the settings out of the managed collection and, when asked to, takes the live session down with them.
     * Leaving the session up means the engine no longer manages it: it runs until it drops on its own.
     */
    private void stopManaging(FixSessionSettings settings, boolean disconnect) {
        configuredSessionsSettings.remove(settings);
        FixSessionImpl fixSession = configuredSessions.remove(settings.getFixSessionId());
        if (fixSession != null && disconnect) {
            fixSession.onSessionRemoved();
            fixSessionsObserver.onSessionUnregistered(fixSession);
        }
    }

    private FixSessionImpl findSession(FixSessionId fixSessionId) {
        FixSessionImpl session = configuredSessions.get(fixSessionId);
        if (session == null) {
            throw new IllegalArgumentException("Unknown session ID: " + fixSessionId
                    + ", managed sessions are: " + configuredSessions.keySet());
        }
        return session;
    }

    @Override
    public void logonSession(FixSessionId fixSessionId) {
        findSession(fixSessionId).logon();
    }

    @Override
    public void logoutSession(FixSessionId fixSessionId) {
        findSession(fixSessionId).logout("Admin API logout");
    }

    @Override
    public void resetSession(FixSessionId fixSessionId, ResetFixSessionMode resetFixSessionMode) {
        findSession(fixSessionId).adminResetSequence(resetFixSessionMode);
    }

    @Override
    public void setIncomingSeqNum(FixSessionId fixSessionId, long incomingSeqNum) {
        findSession(fixSessionId).adminSetIncomingSeqNum(incomingSeqNum);
    }

    @Override
    public void setOutgoingSeqNum(FixSessionId fixSessionId, long outgoingSeqNum) {
        findSession(fixSessionId).adminSetOutgoingSeqNum(outgoingSeqNum);
    }

    @Override
    public void sendFixMessage(FixSessionId fixSessionId, String fixMessage, char separator, boolean possDupFlag) {
        findSession(fixSessionId).adminSendFixMessage(fixMessage, separator, possDupFlag);
    }

    @Override
    public long getIncomingSeqNum(FixSessionId fixSessionId) {
        return findSession(fixSessionId).adminGetIncomingSeqNum();
    }

    @Override
    public long getOutgoingSeqNum(FixSessionId fixSessionId) {
        return findSession(fixSessionId).adminGetOutgoingSeqNum();
    }

    @Override
    public List<FixSessionSettings> getManagedFixSessionsSettings() {
        return new ArrayList<>(configuredSessionsSettings);
    }

    @Override
    public List<FixSession> getManagedFixSessions() {
        return new ArrayList<>(configuredSessions.values());
    }

    @Override
    public boolean isInitiator() {
        return false;
    }

    @Slf4j
    private static class FixSessionIdFixMessageParser {

        private static final byte EQUALS = '=';

        public static ReceivedFixSessionId receivedFixSessionId(ByteBuffer message) throws RejectedSessionException {
            ReceivedFixSessionId.ReceivedFixSessionIdBuilder sessionIdBuilder = ReceivedFixSessionId.builder();
            int limit = message.limit();
            int startPosition = message.position();
            byte[] messageContent = message.array();
            int currentPosition = startPosition;
            while (currentPosition < limit) {
                int nextEqualsPosition = findNextPosition(messageContent, currentPosition, limit, EQUALS);
                if (nextEqualsPosition == -1) {
                    throw new UnknownFixSessionException();
                }
                int messageTagLen = nextEqualsPosition - currentPosition;
                int messageTag = IntSerde.deserializeUnsigned(messageContent, currentPosition, messageTagLen);
                int nextDelimiterPosition = findNextPosition(messageContent, nextEqualsPosition, limit, CoreFields.FIELD_SEPARATOR_BYTE);
                if (nextDelimiterPosition == -1) {
                    throw new UnknownFixSessionException();
                }
                int valueStartOffset = nextEqualsPosition + 1;
                int valueLength = nextDelimiterPosition - nextEqualsPosition - 1;
                String tagValue = new String(messageContent, valueStartOffset, valueLength, SerDe.CHARSET);
                if (messageTag == CoreFields.BEGIN_STRING) {
                    FixRegularVersion fixVersion = FixRegularVersion.fromString(tagValue).orElse(null);
                    sessionIdBuilder.fixVersion(fixVersion);
                    if (fixVersion == null) {
                        sessionIdBuilder.fixtVersion(FixtVersion.fromString(tagValue).orElse(null));
                    }
                    if (sessionIdBuilder.fixVersion == null && sessionIdBuilder.fixtVersion == null) {
                        throw new UnsupportedFixVersionException(tagValue);
                    }
                } else if (messageTag == CoreFields.DEFAULT_APPL_VER_ID) {
                    // do not fail on an unknown/unsupported DefaultApplVerID(1137): keep the raw value so the session can
                    // still be resolved by comp ids and answered with an INVALID_UNSUPPORTED_APPL_VER reject
                    sessionIdBuilder.defaultApplVerId(tagValue);
                    FixApplVerID.forCode(tagValue).ifPresent(sessionIdBuilder::fixApplVerId);
                } else if (messageTag == CoreFields.SENDER_COMP_ID) {
                    sessionIdBuilder.targetCompID(tagValue);  // inverted
                } else if (messageTag == CoreFields.SENDER_SUB_ID) {
                    sessionIdBuilder.targetSubID(tagValue);  // inverted
                } else if (messageTag == CoreFields.SENDER_LOCATION_ID) {
                    sessionIdBuilder.targetLocationID(tagValue);  // inverted
                } else if (messageTag == CoreFields.TARGET_COMP_ID) {
                    sessionIdBuilder.senderCompID(tagValue); // inverted
                } else if (messageTag == CoreFields.TARGET_SUB_ID) {
                    sessionIdBuilder.senderSubID(tagValue); // inverted
                } else if (messageTag == CoreFields.TARGET_LOCATION_ID) {
                    sessionIdBuilder.senderLocationID(tagValue); // inverted
                }
                if (log.isDebugEnabled()) {
                    log.debug("Processing {}={}", messageTag, new String(messageContent, valueStartOffset, valueLength, SerDe.CHARSET));
                }
                currentPosition = nextDelimiterPosition + 1;
            }
            return sessionIdBuilder.build();
        }

        private static int findNextPosition(byte[] messageContent, int startPosition, int limit, byte toFind) {
            for (int i = startPosition; i < limit; i++) {
                if (messageContent[i] == toFind) {
                    return i;
                }
            }
            return -1;
        }
    }

    @Getter
    @Builder
    @AllArgsConstructor

    private static class ReceivedFixSessionId {
        private final FixRegularVersion fixVersion;
        private final FixtVersion fixtVersion;
        private final FixApplVerID fixApplVerId;
        private final String defaultApplVerId;
        private final String senderCompID;
        private final String senderSubID;
        private final String senderLocationID;
        private final String targetCompID;
        private final String targetSubID;
        private final String targetLocationID;

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            if (fixVersion != null) {
                builder.append(fixVersion);
            }
            if (fixtVersion != null) {
                builder.append(fixtVersion);
            }
            addIfProvided(builder, senderCompID);
            addIfProvided(builder, senderSubID);
            addIfProvided(builder, senderLocationID);
            addIfProvided(builder, targetCompID);
            addIfProvided(builder, targetSubID);
            addIfProvided(builder, targetLocationID);
            return builder.toString();
        }

        private void addIfProvided(StringBuilder sb, String value) {
            if (value != null) {
                sb.append(":").append(value);
            }
        }
    }

    private class IOEventsListenerImpl implements IOEventsListener {

        private List<Certificate> remotePeerCertificates;

        @Override
        public void onConnected(IOSession session) {
            if (shuttingDown) {
                log.info("Disconnecting session {} as server is shutting down", session.getSocketAddress());
                session.stop(Deadline.immediate());
            }
        }

        @Override
        public void onDisconnected(IOSession session) {
            FixSessionImpl fixSession = session.getAttachment();
            if (fixSession != null) {
                fixSession.onDisconnection();
                session.setAttachment(null);
                connectedSessions.remove(fixSession);
                connectedSessionsArray = connectedSessions.toArray(new FixSessionImpl[0]);
            }
        }

        @Override
        public void onSSLHandshake(IOSession session, Certificate[] remotePeerCertificates) {
            if (remotePeerCertificates != null) {
                this.remotePeerCertificates = Arrays.asList(remotePeerCertificates);
            }
        }

        @Override
        public void onFailedSSLHandshake(IOSession session, SSLHandshakeException exception) {
            fixAcceptorBuilder.getFixSessionEventsListener().onFailedSSLHandshake(session.getSocketAddress(), exception);
        }

        @Override
        public void onTask(IOSession session, Runnable task, BiConsumer<Runnable, Exception> taskCallback) {
            session.<FixSessionImpl>getAttachment().onIOThreadTask(task, taskCallback);
        }

        @Override
        public void onRead(IOSession session, ByteBuffer message, long localReceiveTimeInNanos) {
            FixSessionImpl fixSession = session.getAttachment();
            if (fixSession == null) {
                AtomicReference<FixSessionId> detectedFixSession = new AtomicReference<>();
                AtomicReference<ReceivedFixSessionId> receivedFixSession = new AtomicReference<>();
                try {
                    fixSession = findTargetSession(session, message, detectedFixSession, receivedFixSession);
                    session.setAttachment(fixSession);
                    connectedSessions.add(fixSession);
                    connectedSessionsArray = connectedSessions.toArray(new FixSessionImpl[0]);
                    if (!fixSession.onConnection(session, remotePeerCertificates)) {
                        throw new SessionNotAcceptingConnectionsException();
                    }
                    fixAcceptorBuilder.getFixSessionEventsListener().onFixSessionAccepted(detectedFixSession.get());
                } catch (RejectedSessionException ex) {
                    fixAcceptorBuilder.getFixSessionEventsListener().onFixSessionRejected(detectedFixSession.get(), ex);
                    message.clear();
                    respondToRejectionAndClose(session, receivedFixSession.get(), ex);
                    return;
                } catch (Exception ex) {
                    log.error("Failed to create session, abnormal situation", ex);
                    message.clear();
                    session.stop(Deadline.immediate());
                    return;
                } finally {
                    remotePeerCertificates = null;
                }
            }
            fixSession.onMessageRead(message, localReceiveTimeInNanos);
        }

        @Override
        public void onWatermarkEvent(IOSession session, boolean highWatermarkReached, long bytesLeftToWrite) {
            session.<FixSessionImpl>getAttachment().onWatermarkEvent(highWatermarkReached, bytesLeftToWrite);
        }

        private FixSessionId resolveMatchedSessionId(ReceivedFixSessionId receivedFixSessionId, FixVersion fixVersion) {
            FixSessionId matchedFixSessionId = configuredSessions.keySet().stream().filter(id -> id.matches(fixVersion,
                    receivedFixSessionId.getFixApplVerId(),
                    receivedFixSessionId.getSenderCompID(),
                    receivedFixSessionId.getSenderSubID(),
                    receivedFixSessionId.getSenderLocationID(),
                    receivedFixSessionId.getTargetCompID(),
                    receivedFixSessionId.getTargetSubID(),
                    receivedFixSessionId.getTargetLocationID()
            )).findFirst().orElse(null);
            if (matchedFixSessionId != null || receivedFixSessionId.getFixtVersion() == null) {
                return matchedFixSessionId;
            }
            // FIXT logon whose DefaultApplVerID(1137) is unknown or not configured for this session: resolve the
            // session by comp ids only so the logon handler can reply with an INVALID_UNSUPPORTED_APPL_VER reject
            // instead of silently dropping the connection
            return configuredSessions.keySet().stream().filter(id -> id.matchesIgnoringApplVerId(
                    receivedFixSessionId.getFixtVersion(),
                    receivedFixSessionId.getSenderCompID(),
                    receivedFixSessionId.getSenderSubID(),
                    receivedFixSessionId.getSenderLocationID(),
                    receivedFixSessionId.getTargetCompID(),
                    receivedFixSessionId.getTargetSubID(),
                    receivedFixSessionId.getTargetLocationID()
            )).findFirst().orElse(null);
        }

        private FixSessionImpl findTargetSession(IOSession session, ByteBuffer message, AtomicReference<FixSessionId> detectedFixSession,
                                                 AtomicReference<ReceivedFixSessionId> receivedFixSession) throws RejectedSessionException {
            ReceivedFixSessionId receivedFixSessionId = FixSessionIdFixMessageParser.receivedFixSessionId(message);
            // expose the parsed identity so a rejected connection can still be answered before being closed
            receivedFixSession.set(receivedFixSessionId);

            FixVersion fixVersion = receivedFixSessionId.getFixVersion() != null ? receivedFixSessionId.getFixVersion() : receivedFixSessionId.getFixtVersion();
            FixSessionId matchedFixSessionId = resolveMatchedSessionId(receivedFixSessionId, fixVersion);
            if (matchedFixSessionId == null) {
                throw new UnknownFixSessionException(receivedFixSessionId.toString());
            }

            detectedFixSession.set(matchedFixSessionId);
            FixSessionImpl match = configuredSessions.get(matchedFixSessionId);
            // some store may create sessions on the fly depending on given FixSessionId
            for (FixSessionsSettingsStore store : fixSessionsSettingsStores) {
                Optional<FixSessionSettings> settingsMatch = store.find(matchedFixSessionId, FixSession.FixSessionType.ACCEPTOR);
                if (settingsMatch.isPresent()) {
                    // store will call onSessionAdded on listeners if it did create one on the fly
                    match = configuredSessions.get(settingsMatch.get().getFixSessionId());
                    break;
                }
            }

            if (match.getDesiredState().equals(FixSessionState.DISCONNECTED)) {
                // admission control, alongside the checks below: a session that must not be up takes no connection,
                // and refusing it here is what keeps the session itself - its sequence numbers, its store, its
                // application callbacks - out of a connection it is not going to serve
                throw new SessionNotAcceptingConnectionsException();
            }
            if (Arrays.stream(connectedSessionsArray).anyMatch(s -> s.getFixSessionId().equals(matchedFixSessionId))) {
                throw new MultipleLogonException();
            }
            checkIPAndCertificatesWhiteList(session, match.getFixSessionSettings());
            return match;
        }

        /**
         * Sends a best-effort session level reply (currently a Logout) before closing a rejected connection, then closes.
         * Some rejections deliberately stay silent: IP/certificate/auth rejections must never reveal a reason to an
         * unauthorized peer, and protocol-version mismatches cannot be framed in a reply both sides would agree on.
         */
        private void respondToRejectionAndClose(IOSession session, ReceivedFixSessionId received, RejectedSessionException ex) {
            if (received != null && ex.shouldSendLogout()) {
                try {
                    byte[] reply = encodeLogoutReply(received, ex);
                    if (reply != null) {
                        session.disableStats();
                        session.send(reply);
                        // give the IO layer a short window to flush the reply before tearing down the connection
                        session.stop(Deadline.of(REJECTION_REPLY_FLUSH_DELAY));
                        return;
                    }
                } catch (Exception encodeError) {
                    log.warn("Failed to encode rejection reply for {}, closing connection silently", received, encodeError);
                }
            }
            session.stop(Deadline.immediate());
        }

        private byte[] encodeLogoutReply(ReceivedFixSessionId received, RejectedSessionException ex) {
            FixVersion version = received.getFixVersion() != null ? received.getFixVersion() : received.getFixtVersion();
            if (version == null || received.getSenderCompID() == null || received.getTargetCompID() == null) {
                return null;
            }
            // borrow a configured session of the same FIX version to encode the reply (registries/dictionary must match)
            FixSessionImpl donor = configuredSessions.values().stream()
                    .filter(s -> s.getFixSessionId().getFixVersion().equals(version))
                    .findFirst().orElse(null);
            if (donor == null) {
                return null;
            }
            return donor.encodeStandaloneLogout(buildReplySessionId(received, donor), ex.getMessage());
        }

        private FixSessionId buildReplySessionId(ReceivedFixSessionId received, FixSessionImpl donor) {
            // the parser already stored comp ids inverted (sender = us, target = peer), exactly what an outgoing header needs
            FixSessionId.FixSessionIdBuilder builder = FixSessionId.FixSessionIdBuilder.builder()
                    .id("rejection-reply")
                    .senderCompID(received.getSenderCompID())
                    .senderSubID(received.getSenderSubID())
                    .senderLocationID(received.getSenderLocationID())
                    .targetCompID(received.getTargetCompID())
                    .targetSubID(received.getTargetSubID())
                    .targetLocationID(received.getTargetLocationID())
                    .build();
            if (received.getFixtVersion() != null) {
                return FixSessionId.ofFIXT11((FixApplVerID) donor.getFixSessionId().getDefaultApplVerID(), builder);
            }
            return FixSessionId.of(received.getFixVersion(), builder);
        }

        private void checkIPAndCertificatesWhiteList(IOSession session, FixSessionSettings sessionSettings) throws RejectedSessionException {
            if (!sessionSettings.getAllowedAddresses().isEmpty()
                    && sessionSettings.getAllowedAddresses().stream().noneMatch(ipAddress -> ipAddress.equals(session.getSocketAddress().getAddress()))) {
                throw new RejectedIpException(session.getSocketAddress().getAddress(), sessionSettings.getAllowedAddresses());
            }
            if (!sessionSettings.getAllowedCertificates().isEmpty()) {
                if (remotePeerCertificates == null) {
                    throw new RejectedCertificateException(null, sessionSettings.getAllowedCertificates());
                }
                if (sessionSettings.getAllowedCertificates().stream().noneMatch(certificate -> remotePeerCertificates.contains(certificate))) {
                    throw new RejectedCertificateException(remotePeerCertificates, sessionSettings.getAllowedCertificates());
                }
            }
        }
    }
}