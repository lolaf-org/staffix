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

import org.lolaf.staffix.api.serde.SerDe;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.betty.api.io.IOWriter;
import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.ringos.rb.RingBuffer;
import org.lolaf.ringos.rb.RingBufferFactory;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.codec.*;
import org.lolaf.staffix.api.executor.MessageExecutor;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.VoidMessageLogger;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.*;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.PluginContext;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.api.version.FixtVersion;
import org.lolaf.staffix.codec.decoders.*;
import org.lolaf.staffix.codec.encoders.GenericFixMessageEncoder;
import org.lolaf.staffix.impl.FailSafeFixApplication;
import org.lolaf.staffix.impl.FailSafeFixMessageDecoder;
import org.lolaf.staffix.impl.FixMessageEncodersPoolImpl;
import org.lolaf.staffix.impl.FixSessionRuntimeDependencies;
import org.lolaf.staffix.impl.executor.MessageExecutorsRuntime;
import org.lolaf.staffix.impl.executor.SessionMessageExecutors;
import org.lolaf.staffix.impl.session.codec.*;
import org.lolaf.staffix.serde.ByteArraySerde;

import javax.security.auth.Subject;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * The session state machine: logon and logout, heartbeats and test requests, sequence numbers, resend requests
 * and gap fills.
 *
 * <p>This is where the FIX session-layer specification lives; the comments through it cite the sections they
 * implement, and the conformance scenarios in {@code fix-conformance} are the tests for them.
 */
@Slf4j
public class FixSessionImpl implements FixSession, FixMessageParserEventsListener, FixMessageEncodingListener {

    private static final IOException NO_CONNECTED_SESSION = new IOException("No connected session");
    private static final BiConsumer<Runnable, Exception> IGNORE_TASK_RESULT = (task, error) -> {
        if (error == NO_CONNECTED_SESSION || error instanceof EOFException) {
            log.debug("Skipped FIX session task {}, the connection is gone: {}", task.getClass().getName(), error.getMessage());
        } else if (error != null) {
            log.error("Failed to execute FIX session task {}", task.getClass().getName(), error);
        }
    };

    @Getter
    private final FixSessionId fixSessionId;
    private final FixAdminMessagesCodec fixAdminMessagesCodec;
    private final FixMessageParser fixMessageParser;
    private final FixApplication fixApplication;
    private final Map<MessageType, FixMessageDecoder> decoders;
    @Getter
    private final FixSessionSettings fixSessionSettings;
    private final TimeUnit sendingTimeAccuracy;
    private final ScheduledExecutorService scheduler;
    private final FixMessagesLogger.Logger fixMessagesLogger;
    private final FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore;
    private final FixSessionImplState fixSessionImplState;
    private final ResendRecovery resendRecovery;
    private final IOWriter.MessageSentCallback<FixSessionFixMessageContext> messageSentCallback;
    private final IOWriter.MessageSentCallback<FixSessionBufferedFixMessageContext> bufferedMessageSentCallback;
    private final Function<MessageType, FixMessageDecoder> fixMessageDecoderProvider;
    private final LongSupplier nextExpectedIncomingSeqNumSupplier;
    @Getter
    private final Clock clock;
    private final RingBuffer<FixSessionFixMessageContext> messageSendingContexts;
    private final RingBuffer<FixSessionBufferedFixMessageContext> bufferMessageSendingContexts;
    private final List<FixSessionFixMessageContext> bufferedMessageSendingContexts;
    private final MessageTypeRegistry messageTypeRegistry;
    @Getter
    private final FieldsRegistry fieldsRegistry;
    @Getter
    private final FixSessionRegistry fixSessionRegistry;
    private final FixMessageEncoderFactory fixMessageEncoderFactory;
    private final IdleStrategy pollBlockingIdleStrategy;
    private final Map<String, FixMessageEncodersPool<?>> allocatedEncodersPool;
    private final SessionMessageExecutors messageExecutors;
    private final FixSessionScheduleManager fixSessionScheduleManager;
    private final IntFunction<ByteBuffer> encodersAllocator;
    private final String fixInstanceId;
    @Getter
    private final Set<MessageType> outgoingMessageTypes;
    private final RttEstimator rttEstimator;
    private IntFunction<ByteBuffer> byteBufferBorrower;
    private FixMessageParser outOfSequenceMessagesParser;
    private boolean holdingOutgoingMessages;
    private ExecutorService resendExecutor;
    @Getter
    private Collection<Certificate> remoteCertificates;
    private IOSession ioSession;
    private ScheduledFuture<?> heartBeatTask;
    private ScheduledFuture<?> sessionTimeCheckTask;
    private ScheduledFuture<?> logonOrLogoutCheckTask;
    private ScheduledFuture<?> cancelOnDisconnectTask;
    private ScheduledFuture<?> rttMeasurementsTask;
    private boolean firstMessageIsLogonOrLogoutCheck;
    private boolean connectionHeldLogged;
    /**
     * Built on first administrative send and kept: it holds a stateful parser, and such sends are rare enough that one
     * instance for the session is all they need.
     */
    private AdminFixMessageTransformer adminFixMessageTransformer;
    private Clock encodersClock;
    @Getter
    @Setter
    private Subject authenticatedSubject;
    private FixSessionPlugin<?, ?>[] fixSessionPlugins;

    public FixSessionImpl(String fixInstanceId,
                          FixSessionSettings fixSessionSettings,
                          FixSessionRuntimeDependencies fixSessionRuntimeDependencies,
                          ScheduledExecutorService scheduledExecutorService,
                          IOSettings ioSettings,
                          MessageExecutorsRuntime messageExecutorsRuntime,
                          Clock providedClock) {
        this.fixInstanceId = fixInstanceId;
        this.allocatedEncodersPool = new ConcurrentHashMap<>();
        this.pollBlockingIdleStrategy = new BackoffIdleStrategy();
        this.fixSessionSettings = fixSessionSettings;
        this.fixSessionId = fixSessionSettings.getFixSessionId();
        this.fixApplication = new FailSafeFixApplication(fixSessionRuntimeDependencies.getFixApplicationFactory().getInstance(fixSessionSettings.getFixApplicationInstanceId()));
        this.fixSessionRegistry = fixSessionRuntimeDependencies.getFixSessionRegistry();
        this.sendingTimeAccuracy = fixSessionSettings.getSendingTimeAccuracy();
        this.messageSentCallback = this::messageSentCallback;
        this.bufferedMessageSentCallback = this::bufferedMessagesSentCallback;
        this.decoders = new IdentityHashMap<>();

        FixRegularVersion dicFixVersion = null;
        if (fixSessionId.getFixVersion() instanceof FixtVersion) {
            dicFixVersion = FixApplVerID.getFixVersionForCode(fixSessionId.getDefaultApplVerID().getCode());
        } else if (fixSessionId.getFixVersion() instanceof FixRegularVersion) {
            dicFixVersion = (FixRegularVersion) fixSessionId.getFixVersion();
        }

        FixDictionaryId fixDictionaryId = FixDictionaryId.of(fixSessionSettings.getDictionaryId(), dicFixVersion);
        FieldsRegistry localFieldsRegistry = FixTFieldsRegistry.get(fixDictionaryId, fixSessionId.getFixVersion());
        this.fixMessageEncoderFactory = FixMessageEncoderFactory.Registry.getInstance(fixDictionaryId);
        this.fieldsRegistry = fixSessionSettings.getValidationSettings().isAllowUnknownFields()
                || fixSessionSettings.getValidationSettings().isAllowUserDefinedFields() ?
                new AddingFieldsOnTheFlyRegistry(fixSessionSettings.getValidationSettings(), localFieldsRegistry) : localFieldsRegistry;
        if (fixSessionSettings.getCancelOnDisconnectSettings().isEnabled()) {
            fieldsRegistry.addUserDefinedField(fixSessionSettings.getCancelOnDisconnectSettings().getCancelOnDisconnectTypeFieldCode(),
                    FieldType.MULTIPLESTRINGVALUE, FieldLocation.BODY);
            fieldsRegistry.addUserDefinedField(fixSessionSettings.getCancelOnDisconnectSettings().getCodTimeoutWindowFieldCode(),
                    FieldType.INT, FieldLocation.BODY);
        }
        this.scheduler = scheduledExecutorService;
        this.messageTypeRegistry = FixTMessageTypeRegistry.get(fixDictionaryId, fixSessionId.getFixVersion());
        this.clock = providedClock == null ? ClockImpl.get() : providedClock;
        this.fixSessionScheduleManager = new FixSessionScheduleManager(fixSessionSettings, clock);
        this.fixSessionImplState = new FixSessionImplState(this, fixSessionSettings.getFixSessionType().equals(FixSessionType.ACCEPTOR),
                fixSessionSettings.getDesiredSessionState(), fixSessionScheduleManager);
        this.resendRecovery = fixSessionImplState.getResendRecovery();
        this.fixSessionMessagesStore = new FailSafeFixSessionMessagesStore(fixSessionRuntimeDependencies.getFixMessagesStore().getStore(fixSessionId));
        this.fixAdminMessagesCodec = buildFixAdminMessagesCodec(fixDictionaryId);
        this.fixMessagesLogger = fixSessionRuntimeDependencies.getFixMessagesLogger() != null
                ? fixSessionRuntimeDependencies.getFixMessagesLogger().getLogger(fixInstanceId, fixSessionId, messageTypeRegistry) : VoidMessageLogger.getInstance();
        this.fixMessageParser = new FixMessageParser(fixSessionId.invert(), messageTypeRegistry, fieldsRegistry, this.fixMessagesLogger,
                fixSessionSettings.getValidationSettings(), clock, this);
        this.sessionTimeCheckTask = fixSessionScheduleManager.isEnabled()
                ? this.scheduler.scheduleAtFixedRate(this::checkSessionTime,
                calculateInitialSessionTimeCheckDelay(fixSessionSettings.getSessionScheduleSettings().getWithinSessionTimeCheckInterval()),
                fixSessionSettings.getSessionScheduleSettings().getWithinSessionTimeCheckInterval().toMillis(), TimeUnit.MILLISECONDS) : null;
        this.fixMessageDecoderProvider = this::getTargetDecoder;
        this.nextExpectedIncomingSeqNumSupplier = fixSessionMessagesStore::getIncomingSeqNum;

        RingBufferFactory.AccessType accessType = ioSettings.isMultiThreadedWriteAPICalls()
                ? RingBufferFactory.AccessType.MULTI_CONSUMER_SINGLE_PRODUCER : RingBufferFactory.AccessType.SINGLE_CONSUMER_SINGLE_PRODUCER;
        this.messageSendingContexts = RingBufferFactory.build(accessType, ioSettings.getTasksRingBufferSize());
        while (!messageSendingContexts.isFull()) {
            messageSendingContexts.offer(new FixSessionFixMessageContext(
                    fixSessionMessagesStore::getNextOutgoingSeqNum, fixApplication, sendingTimeAccuracy, clock, this, fixSessionId, messageSendingContexts));
        }
        this.bufferMessageSendingContexts = RingBufferFactory.build(accessType, ioSettings.getTasksRingBufferSize());
        while (!bufferMessageSendingContexts.isFull()) {
            bufferMessageSendingContexts.offer(new FixSessionBufferedFixMessageContext(bufferMessageSendingContexts));
        }
        this.bufferedMessageSendingContexts = new ArrayList<>(messageSendingContexts.getSize());
        this.byteBufferBorrower = ByteBuffer::allocate;
        this.encodersAllocator = fixSessionSettings.isMessageEncodersDirectByteBuffers() ? ByteBuffer::allocateDirect : ByteBuffer::allocate;
        this.messageExecutors = messageExecutorsRuntime.newSessionExecutors();
        this.outgoingMessageTypes = new HashSet<>();
        this.rttEstimator = new RttEstimator(fixSessionSettings.getRttMeasurementSettings());
    }

    private static long calculateInitialSessionTimeCheckDelay(Duration withinSessionCheckInterval) {
        if (withinSessionCheckInterval.toMillis() < 50) {
            throw new IllegalStateException("WithinSessionCheckInterval cannot be smaller than 50 milliseconds");
        }
        return withinSessionCheckInterval.toMillis() - (System.currentTimeMillis() % withinSessionCheckInterval.toMillis());
    }

    // Captures each heterogeneous plugin's token type once so the token it produces flows back into its own
    // typed onMessageEncodingStarted; the engine still holds tokens opaquely as Object[].
    private static <T> T beginEncoding(FixSessionPlugin<?, T> plugin, MessageType messageType, long encodingStartTimeInNanos) {
        T token = plugin.getMessageEncodingToken(messageType, encodingStartTimeInNanos);
        plugin.onMessageEncodingStarted(messageType, encodingStartTimeInNanos, token);
        return token;
    }

    @SuppressWarnings("unchecked")
    private static <T> void encodedBody(FixSessionPlugin<?, T> plugin, MessageType messageType, ByteBuffer encodedBody,
                                        FixMessageEncoder<?> encoder, long encodingStartTimeInNanos, Object token) {
        // The token came from this same plugin's getMessageEncodingToken, so the cast back to its T is safe.
        plugin.onMessageEncodedBody(messageType, encodedBody, encoder, encodingStartTimeInNanos, (T) token);
    }

    @SuppressWarnings("unchecked")
    private static <T> void encodingFinished(FixSessionPlugin<?, T> plugin, MessageType messageType,
                                             long encodingStartTimeInNanos, Object token) {
        // The token came from this same plugin's getMessageEncodingToken, so the cast back to its T is safe.
        plugin.onMessageEncodingFinished(messageType, encodingStartTimeInNanos, (T) token);
    }

    public boolean hasConfiguredPluginsWithTimeMeasurementRequired() {
        return fixSessionPlugins != null && Arrays.stream(fixSessionPlugins).anyMatch(FixSessionPlugin::requiresTimeMeasurement);
    }

    public Set<MessageType> getIncomingMessageTypes() {
        return decoders.keySet();
    }

    public void start(FixSessionRuntimeDependencies fixSessionRuntimeDependencies) {
        for (FixApplicationSessionSettingDescriptor d : fixApplication.getRequiredFixSessionSettings()) {
            if (!fixSessionSettings.getFixApplicationSessionSettings().containsKey(d)) {
                throw new IllegalStateException("Fix session application settings " + d + " is missing for session " + fixSessionId.getId());
            }
        }

        List<FixMessageDecoder> fixMessageDecoders = this.fixApplication.setup(fixSessionSettings, this, outgoingMessageTypes);
        for (FixMessageDecoder fixMessageDecoder : fixMessageDecoders) {
            FixMessageDecoder finalDecoder = new FailSafeFixMessageDecoder(fixMessageDecoder);
            decoders.put(fixMessageDecoder.getMessageType(), new FixMessageDecoderImpl(fieldsRegistry, finalDecoder,
                    messageTypeRegistry.getTargetDictionary(), fixSessionSettings.getValidationSettings(), fixSessionSettings.getFixSessionId()));
        }
        if (decoders.isEmpty()) {
            log.info("FIX session {} has no incoming FixMessageDecoder: nothing but session level messages will be "
                    + "processed on it", fixSessionSettings.getFixSessionId());
        }
        fixMessagesLogger.start();
        fixSessionMessagesStore.start();

        List<FixSessionPlugin<?, ?>> pluginsListeners = new ArrayList<>();
        fixSessionRuntimeDependencies.getFixSessionsPlugins().forEach(p ->
                p.onSessionCreated(fixInstanceId, this, getIncomingMessageTypes(), outgoingMessageTypes)
                        .ifPresent(l -> pluginsListeners.add(new FailSafeFixSessionPlugin<>(l))));

        fixSessionPlugins = !pluginsListeners.isEmpty() ? pluginsListeners.toArray(new FixSessionPlugin[0]) : null;
        encodersClock = hasConfiguredPluginsWithTimeMeasurementRequired() ? clock : Clock.VoidClock.getInstance();

        if (fixSessionPlugins != null) {
            decoders.values().forEach(d ->
                    ((FixMessageDecoderImpl) d).onPluginsSetup(fixSessionPlugins));
            fixAdminMessagesCodec.getAdminMessageDecoders().values().forEach(d ->
                    ((FixMessageDecoderImpl) d).onPluginsSetup(fixSessionPlugins));
        }

        fixApplication.onSessionCreated(this, fieldsRegistry, messageTypeRegistry, fixMessageDecoders);
        logEvent("Session %s created", fixSessionId);
    }

    public void onLogonProcessed() {
        if (cancelOnDisconnectTask != null) {
            logEvent("Cancelling cancel on disconnect task");
            cancelOnDisconnectTask.cancel(true);
            cancelOnDisconnectTask = null;
        }
        unscheduleTasksIfNeeded();
        long initialDelayMillis = 1000L - (System.currentTimeMillis() % 1000L);
        heartBeatTask = scheduler.scheduleAtFixedRate(this::manageHeartbeats, initialDelayMillis, 1000L, TimeUnit.MILLISECONDS);
        Duration probeInterval = fixSessionSettings.getRttMeasurementSettings().getProbeInterval();
        if (probeInterval != null && !probeInterval.isZero()) {
            long periodMillis = Math.max(25L, probeInterval.toMillis());
            rttMeasurementsTask = scheduler.scheduleAtFixedRate(this::sendRttMeasurementProbe, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        }
        if (fixSessionPlugins != null) {
            for (FixSessionPlugin<?, ?> fixSessionPlugin : fixSessionPlugins) {
                fixSessionPlugin.onLogon();
            }
        }
    }

    private void sendRttMeasurementProbe() {
        if (!fixSessionImplState.isLoggedIn()) {
            return;
        }
        String testReqId = fixSessionSettings.getRttMeasurementSettings().getProbeTestReqIdPrefix() + System.currentTimeMillis();
        send(fixAdminMessagesCodec.generateTestRequest(testReqId), null, (sendingError, p1, p2) -> {
            if (sendingError == null) {
                rttEstimator.recordTestRequestSent(testReqId, clock.nanoTime(), clock.nowEpochNanos());
            }
        }, null, null);
    }

    public void onLogoutProcessed(CancelOnDisconnectType cancelOnDisconnectType, int codTimeoutWindowInMillis, boolean cleanLogout) {
        if (cancelOnDisconnectType != null) {
            switch (cancelOnDisconnectType) {
                case DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT:
                    break;
                case CANCEL_ON_DISCONNECT_OR_LOGOUT:
                    scheduleCodTask(cancelOnDisconnectType, codTimeoutWindowInMillis);
                    break;
                case CANCEL_ON_DISCONNECT_ONLY:
                    if (!cleanLogout) {
                        scheduleCodTask(cancelOnDisconnectType, codTimeoutWindowInMillis);
                    }
                    break;
                case CANCEL_ON_LOGOUT_ONLY:
                    if (cleanLogout) {
                        scheduleCodTask(cancelOnDisconnectType, codTimeoutWindowInMillis);
                    }
                    break;
            }
        }
        if (fixSessionPlugins != null) {
            for (FixSessionPlugin<?, ?> fixSessionPlugin : fixSessionPlugins) {
                fixSessionPlugin.onLogout();
            }
        }
    }

    private void scheduleCodTask(CancelOnDisconnectType cancelOnDisconnectType, int codTimeoutWindowInMillis) {
        if (fixSessionImplState.isStarted()) {
            logEvent("Schedule cancel on disconnect task for type %s with timeout %s", cancelOnDisconnectType, codTimeoutWindowInMillis);
            cancelOnDisconnectTask = scheduler.schedule(() -> {
                logEvent("Triggered cancel on disconnect task");
                fixApplication.onCancelOnDisconnectTriggered(this, cancelOnDisconnectType);
                cancelOnDisconnectTask = null;
            }, codTimeoutWindowInMillis, TimeUnit.MILLISECONDS);
        }
    }

    public void onSessionRemoved() {
        Deadline stopDeadline = Deadline.of(fixSessionSettings.getDisconnectMessagesFlushDeadline());
        stop("FIX session has been removed", stopDeadline);
        releaseResources(stopDeadline);
    }

    @Override
    public FixSessionState getDesiredState() {
        return fixSessionImplState.getDesiredState();
    }

    @Override
    public boolean isWithinSessionTime() {
        return fixSessionImplState.isInsideSessionTime();
    }

    public boolean isReadyToConnect() {
        if (fixSessionImplState.isStarted()) {
            if (fixSessionImplState.getDesiredState().equals(FixSessionState.DISCONNECTED)) {
                logConnectionHeldOnce("FIX session desired state is DISCONNECTED, holding off connecting");
                return false;
            }
            if (!fixSessionScheduleManager.isWithinSessionTime()) {
                logConnectionHeldOnce("FIX session outside of timeframe, holding off connecting until it opens again");
                return false;
            }
            connectionHeldLogged = false;
            return true;
        }
        return false;
    }

    private void logConnectionHeldOnce(String event) {
        if (!connectionHeldLogged) {
            connectionHeldLogged = true;
            logEvent(event);
        }
    }

    @Override
    public <C extends PluginContext> Optional<C> getPluginContext(Class<C> pluginContextClass) {
        if (fixSessionPlugins != null) {
            for (FixSessionPlugin<?, ?> fixSessionPlugin : fixSessionPlugins) {
                if (fixSessionPlugin.isForPluginContext(pluginContextClass)) {
                    return (Optional<C>) fixSessionPlugin.getPluginContext();
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void onEncodingStart(MessageType messageType, FixMessageEncoder<?> encoder, long encodingStartTimeInNanos) {
        if (fixSessionPlugins != null) {
            // Stash each plugin's per-message token on the encoder so the I/O-thread encoding callbacks
            // (which run on a different thread than this one) can hand it back for the same message. The token
            // is produced first, then handed straight back to the same plugin's onMessageEncodingStarted.
            Object[] tokens = encoder.pluginEncodingState(fixSessionPlugins.length);
            for (int i = 0; i < fixSessionPlugins.length; i++) {
                tokens[i] = beginEncoding(fixSessionPlugins[i], messageType, encodingStartTimeInNanos);
            }
        }
    }

    @Override
    public void onEncodedBody(MessageType messageType, ByteBuffer encodedBody, FixMessageEncoder<?> encoder, long encodingStartTimeInNanos) {
        if (fixSessionPlugins != null) {
            Object[] tokens = encoder.pluginEncodingState();
            for (int i = 0; i < fixSessionPlugins.length; i++) {
                encodedBody(fixSessionPlugins[i], messageType, encodedBody, encoder, encodingStartTimeInNanos,
                        tokens != null ? tokens[i] : null);
            }
        }
    }

    @Override
    public void onEncodingEnd(MessageType messageType, ByteBuffer encodedMessage, FixMessageEncoder<?> encoder, long encodingStartTimeInNanos) {
        if (fixSessionPlugins != null) {
            Object[] tokens = encoder.pluginEncodingState();
            for (int i = 0; i < fixSessionPlugins.length; i++) {
                encodingFinished(fixSessionPlugins[i], messageType, encodingStartTimeInNanos,
                        tokens != null ? tokens[i] : null);
            }
        }
    }

    @Override
    public void onMessageDecodingStart(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        if (fixSessionPlugins != null) {
            for (FixSessionPlugin<?, ?> fixSessionPlugin : fixSessionPlugins) {
                fixSessionPlugin.onMessageDecodingStarted(messageType, localReceiveTimeInNanos, localReceiveTime);
            }
        }
    }

    @Override
    public void onMessageDecodingEnd(MessageType messageType, int payloadSize, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        if (fixSessionPlugins != null) {
            for (FixSessionPlugin<?, ?> fixSessionPlugin : fixSessionPlugins) {
                fixSessionPlugin.onMessageReceived(messageType, payloadSize, localReceiveTimeInNanos, localReceiveTime);
            }
        }
    }

    public void onMessageSent(ByteBuffer sentMessage, Object messageSendingContext, long localSendingStartTimeInNanos) {
        if (fixSessionPlugins != null) {
            FixSessionFixMessageContext ctx = (FixSessionFixMessageContext) messageSendingContext;
            MessageType sentMessageType = ctx.getEncoder().getMessageType();
            for (FixSessionPlugin<?, ?> fixSessionPlugin : fixSessionPlugins) {
                fixSessionPlugin.onMessageSent(sentMessageType, sentMessage.limit(), localSendingStartTimeInNanos, ctx.getSendingTime());
            }
        }
    }

    @Override
    public void onMessageDecoded(MessageType messageType, FixMessageDecoder fixMessageDecoder, long incomingSeqNum, boolean possibleDuplicate, boolean possResend, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        fixSessionImplState.onMessageReceived(localReceiveTime);
        if (fixSessionImplState.isAdminOnlyMessagesAllowed() && !messageType.isAdmin()) {
            MessageReject reject = MessageReject.builder()
                    .message("Only admin messages are allowed at this session current state")
                    .refTagId(0)
                    .businessRejectReasonCode(BusinessRejectReasonCodes.OTHER)
                    .sessionRejectReasonCode(SessionRejectReasonCodes.OTHER)
                    .build();
            onMessageDecodingFailed(messageType, fixMessageDecoder, incomingSeqNum, new RejectedMessageException(reject.getMessage()), localReceiveTimeInNanos, localReceiveTime);
            onMessageRejects(messageType, fixMessageDecoder, incomingSeqNum, List.of(reject));
            return;
        }

        // check first if we have a pending resend request, onDecoded() may clear the state of the pending request
        boolean pendingResendRequest = resendRecovery.hasPendingResendRequest();
        fixMessageDecoder.onDecoded(this, possibleDuplicate, possResend);
        if (fixSessionPlugins != null) {
            for (FixSessionPlugin<?, ?> l : fixSessionPlugins) {
                l.onMessageDecodingFinished(messageType, localReceiveTimeInNanos, localReceiveTime);
            }
        }

        // some decoders settle what comes next themselves - a SequenceReset jumping to NewSeqNo or over a gap filled
        // range, a Logon that restarted the numbering or completed a retransmission - and their own MsgSeqNum belongs
        // to the numbering they replaced, so it must not be used to derive the next one. Asked after onDecoded, which
        // is where they decide
        boolean sequenceManagedByDecoder = fixMessageDecoder.managesIncomingSequenceNumber();
        long nextIncomingSeqNum = sequenceManagedByDecoder
                ? fixSessionMessagesStore.getIncomingSeqNum()
                : incomingSeqNum + 1;
        // stored before the resend is told about this message: completing a resend schedules the replay of what was
        // queued on top of the gap, and that replay reads what the session expects next from the store
        fixSessionMessagesStore.storeNextIncomingSeqNum(nextIncomingSeqNum);
        if (pendingResendRequest && !sequenceManagedByDecoder) {
            // an ordinary message of the range settles its own MsgSeqNum(34) and nothing else; the decoders that
            // settle more than that say so themselves, which is why they are excluded here
            resendRecovery.onSequenceNumbersSettledUpTo(incomingSeqNum);
        }
    }

    @Override
    public void onMessageDecodingFailed(MessageType messageType, FixMessageDecoder fixMessageDecoder, long incomingSeqNum, DecodingException decodingFailureCause, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        // a garbled message is not inbound activity: it must not reset the heartbeat interval timer
        boolean garbled = decodingFailureCause instanceof GarbledMessageException;
        if (garbled) {
            logEvent("Garbled message received, ignoring it: %s", ((GarbledMessageException) decodingFailureCause).getReason());
        } else {
            fixSessionImplState.onMessageReceived(localReceiveTime);
        }
        if (decodingFailureCause instanceof WrongBeginStringException) {
            // incorrect BeginString(8): reference the offending value in a Logout and disconnect
            WrongBeginStringException wrongBeginString = (WrongBeginStringException) decodingFailureCause;
            String message = String.format("Received message with incorrect BeginString(8): expected %s but got %s",
                    wrongBeginString.getExpectedBeginString(), wrongBeginString.getReceivedBeginString());
            logEvent(message);
            logout(message);
            fixMessageDecoder.onDecodingFailed(this, decodingFailureCause);
            return;
        }

        // settled before the decoder is told anything: the out of sequence handling below queues the message and asks
        // for the gap ahead of it, and the decoders that answer while out of sequence - Logon(35=A) and
        // ResendRequest(35=2) - build their answer on top of that having happened
        RolledBackSequenceNumber sequenceNumber = onRolledBackSequenceNumber(messageType, decodingFailureCause, garbled);

        fixMessageDecoder.onDecodingFailed(this, decodingFailureCause);
        if (fixSessionPlugins != null && !(decodingFailureCause instanceof NotEnoughDataException)) {
            // in case of NotEnoughDataException message will be shortly reprocessed and normally
            // onDecodingStarted has never been called in such case
            for (FixSessionPlugin<?, ?> l : fixSessionPlugins) {
                l.onMessageDecodingFinished(messageType, localReceiveTimeInNanos, localReceiveTime);
            }
        }

        if (sequenceNumber == RolledBackSequenceNumber.LEFT_UNCHANGED) {
            return;
        }
        // a decoder may have settled the incoming sequence number itself even on this path: a SequenceReset(35=4)
        // rolled back after it was read has left NextNumIn where the reset it refused, or applied, decided it goes,
        // and deriving one from this message's MsgSeqNum(34) would undo that. See managesIncomingSequenceNumber()
        if (!fixMessageDecoder.managesIncomingSequenceNumber()) {
            fixSessionMessagesStore.storeNextIncomingSeqNum(incomingSeqNum + 1);
        }
    }

    private RolledBackSequenceNumber onRolledBackSequenceNumber(MessageType messageType, DecodingException decodingFailureCause, boolean garbled) {
        if (garbled) {
            // section 4.8: a garbled message is disregarded as if it had never been received. The peer keeps
            // incrementing its own MsgSeqNum(34), so whatever it sends next surfaces as a gap and drives the recovery
            return RolledBackSequenceNumber.LEFT_UNCHANGED;
        }
        if (decodingFailureCause instanceof NotEnoughDataException) {
            // not processed at all, merely truncated: it is parsed again once the rest of it arrives, and counting it
            // now would have that second parse look too low
            return RolledBackSequenceNumber.LEFT_UNCHANGED;
        }
        if (decodingFailureCause instanceof WrongSeqNumException) {
            WrongSeqNumException wrongSeqNumException = (WrongSeqNumException) decodingFailureCause;
            logEvent("Received out of order message %s, expecting %s but got %s", messageType,
                    wrongSeqNumException.getExpectedMsgSeqNum(), wrongSeqNumException.getMsgSeqNum());
            if (wrongSeqNumException.getMsgSeqNum() > wrongSeqNumException.getExpectedMsgSeqNum()) {
                onMsgSeqNumTooHigh(messageType, wrongSeqNumException);
            } else {
                onMsgSeqNumTooLow(messageType, wrongSeqNumException);
            }
            // whichever way it was out of sequence, this message settles nothing: what the session expects next is
            // moved on by the recovery, by the peer's retransmission filling the gap ahead of a message too high or
            // by the session that a message too low has just taken down
            return RolledBackSequenceNumber.LEFT_UNCHANGED;
        }
        // anything else - a validation failure, a rejected message - is a message that arrived in sequence and was
        // read: it is answered with a Reject(35=3) rather than replayed, so its MsgSeqNum(34) is consumed
        return RolledBackSequenceNumber.CONSUMED;
    }

    /**
     * Section 4.5 state table rows 11 and 12: queue the message and ask for the range missing ahead of it. The peer
     * will not resend this one - it was delivered - so it has to be replayed once the gap has been filled.
     * <p>
     * A Logon(35=A) is the exception on both counts. It is processed on arrival by its decoder, out of sequence and
     * all, so only its MsgSeqNum(34) is still owed and there is nothing to replay. And it has to be acknowledged
     * before the ResendRequest(35=2) goes out - test case scenario 1S-a orders the two, and section 4.3.12 calls the
     * retransmission "synchronization after successful logon" - which only {@code LogonFixMessageDecoder} can time,
     * the application being free to validate the logon asynchronously, so it issues the request itself right after
     * the acknowledgement.
     */
    private void onMsgSeqNumTooHigh(MessageType messageType, WrongSeqNumException wrongSeqNumException) {
        long receivedSeqNum = wrongSeqNumException.getMsgSeqNum();
        boolean logon = messageType.code().equals(CoreMessageType.LOGON);
        // a ResendRequest is answered on arrival too, by its own decoder and for its own reason: holding it
        // back would deadlock two peers that each reconnect with a gap. Only its MsgSeqNum is left owed.
        boolean answeredOnArrival = logon || messageType.code().equals(CoreMessageType.RESEND_REQUEST);
        resendRecovery.queueOutOfSequenceMessage(answeredOnArrival, receivedSeqNum, wrongSeqNumException.getRawMessage());
        if (!logon && !resendRecovery.hasPendingResendRequest()) {
            requestRetransmission(wrongSeqNumException.getExpectedMsgSeqNum(), receivedSeqNum - 1, "MsgSeqNum too high");
        }
    }

    /**
     * A MsgSeqNum(34) below what the session expects, which section 4.8.1 makes fatal unless the message says why it
     * is a duplicate. Only gap fills reach here among the SequenceReset(35=4)s: a hard reset ignores its own
     * MsgSeqNum(34) and never takes the out of sequence path at all, see
     * {@link FixMessageDecoder#ignoresIncomingSequenceNumber()}.
     */
    private void onMsgSeqNumTooLow(MessageType messageType, WrongSeqNumException wrongSeqNumException) {
        long receivedSeqNum = wrongSeqNumException.getMsgSeqNum();
        long expectedSeqNum = wrongSeqNumException.getExpectedMsgSeqNum();
        if (wrongSeqNumException.isPossDup()) {
            // PossDupFlag(43)=Y: a legitimate retransmission of an already processed message, ignored - it has
            // already been received
            logEvent("Ignoring already processed PossDup message %s with MsgSeqNum %s, expecting %s",
                    messageType, receivedSeqNum, expectedSeqNum);
            return;
        }
        if (isOverrunGapFillOfOurOwnOpenEndedRequest(messageType)) {
            // the tail of an open ended ResendRequest of ours, landing after the queue on top of the gap has been
            // replayed and taken NextNumIn past it. Section 4.8.1 has this terminate the session, and it stays that
            // way for a session asking a closed range - a peer answering within the range it named has no reason to
            // reach here. This one was asked for everything from BeginSeqNo(7) onwards, so the gap fill covering what
            // it chose not to retransmit is its answer arriving a moment late, saying nothing this session has not
            // already worked out for itself.
            logEvent("Ignoring gap fill with MsgSeqNum %s past the end of our open ended ResendRequest, expecting %s",
                    receivedSeqNum, expectedSeqNum);
            return;
        }
        // too low without PossDupFlag(43): unrecoverable, log out and disconnect
        logout(String.format("MsgSeqNum too low, expecting %s but received %s", expectedSeqNum, receivedSeqNum));
    }

    /**
     * A parser of its own for the replay, so that feeding a held message back cannot disturb the state
     * {@link #fixMessageParser} keeps for the network read in progress - a message split across two TCP reads lives
     * in there. Built on first use: a session that never loses a message never allocates it.
     */
    private FixMessageParser getOutOfSequenceMessagesParser() {
        if (outOfSequenceMessagesParser == null) {
            outOfSequenceMessagesParser = new FixMessageParser(fixSessionId.invert(), messageTypeRegistry, fieldsRegistry,
                    fixMessagesLogger, fixSessionSettings.getValidationSettings(), clock, this);
        }
        return outOfSequenceMessagesParser;
    }

    /**
     * Asks the peer to retransmit {@code [fromSeqNum, toSeqNum]} and records the request as the one now outstanding.
     *
     * @param reason what opened the gap, for the session event log
     */
    public void requestRetransmission(long fromSeqNum, long toSeqNum, String reason) {
        logEvent("%s, sending ResendRequest from %s to %s", reason, fromSeqNum, toSeqNum);
        recordRetransmission(fromSeqNum, toSeqNum);
        send(fixAdminMessagesCodec.generateResendRequest(fromSeqNum, toSeqNum), null);
    }

    /**
     * Records {@code [fromSeqNum, toSeqNum]} as outstanding without asking for it, for the one case where the peer
     * retransmits of its own accord: section 4.4.1 has it driven by the NextExpectedMsgSeqNum(789) our own Logon(35=A)
     * carries, and "peers should not generate a ResendRequest(35=2) message based on MsgSeqNum(34) of the incoming
     * Logon(35=A) message but should expect any gaps to be filled automatically".
     *
     * @param reason what opened the gap, for the session event log
     */
    public void awaitPeerRetransmission(long fromSeqNum, long toSeqNum, String reason) {
        logEvent("%s, awaiting the automatic retransmission of %s to %s", reason, fromSeqNum, toSeqNum);
        recordRetransmission(fromSeqNum, toSeqNum);
    }

    /**
     * The single way a retransmission of the messages this session is missing becomes the one outstanding, whether it
     * was asked for or is expected to arrive on its own. Everything a pending request carries hangs off here: the
     * hold on outgoing application messages, the application callbacks, the stall timeout - and above all the record
     * itself, since the queue of messages held on top of a gap is only ever drained by a request completing, so a gap
     * recovered without one recorded would be recovered on the wire and never delivered.
     */
    private void recordRetransmission(long fromSeqNum, long toSeqNum) {
        resendRecovery.onResendRequestSent(fromSeqNum, toSeqNum);
        fixApplication.onResendRequestInitiated(this, fromSeqNum, toSeqNum);
    }

    /**
     * Feeds one message held by {@link ResendRecovery#queueOutOfSequenceMessage} back through the decoding path,
     * exactly as if it had just been read off the socket. Driven by the state, which owns the queue and decides the
     * order; only the parsing belongs here.
     *
     * @return whether the message could be replayed, the session having been disconnected when it could not
     */
    boolean replayOutOfSequenceMessage(long msgSeqNum, byte[] rawMessage) {
        try {
            getOutOfSequenceMessagesParser().parseMessages(ByteBuffer.wrap(rawMessage),
                    fixMessageDecoderProvider, nextExpectedIncomingSeqNumSupplier, clock.nanoTime());
            return true;
        } catch (Exception ex) {
            log.warn("Failed to replay out of order message with MsgSeqNum {} on session {}", msgSeqNum, fixSessionId, ex);
            logEvent("Failed to replay out of order message with MsgSeqNum %s: %s", msgSeqNum, ex.getMessage());
            disconnect();
            return false;
        }
    }

    FixMessagesStore.FixSessionMessagesStore getFixSessionMessagesStore() {
        return fixSessionMessagesStore;
    }

    @Override
    public void onMessageRejects(MessageType messageType, FixMessageDecoder fixMessageDecoder, long incomingSeqNum, List<MessageReject> rejects) {
        rejects.forEach(reject -> onMessageReject(messageType, reject, incomingSeqNum));
    }

    private void onMessageReject(MessageType messageType, MessageReject messageReject, long refSeqNum) {
        boolean isAdminMessage = messageType.isAdmin();
        if (messageReject.getBusinessRejectReasonCode() != null && !isAdminMessage) {
            send(fixAdminMessagesCodec.generateBusinessReject(messageReject.getMessage(),
                    messageReject.getBusinessRejectReasonCode().getCode(), refSeqNum,
                    String.valueOf(messageReject.getRefTagId()), messageType), clock.now());
        } else {
            send(fixAdminMessagesCodec.generateReject(messageReject.getMessage(),
                    messageReject.getSessionRejectReasonCode().getCode(), refSeqNum,
                    messageReject.getRefTagId(), messageType), clock.now());
        }

        if (messageReject.getSessionRejectReasonCode().equals(SessionRejectReasonCodes.SENDING_TIME_ACCURACY_PROBLEM)) {
            logout("Logout due to sending accuracy problem");
        } else if (messageReject.getSessionRejectReasonCode().equals(SessionRejectReasonCodes.COMPID_PROBLEM)
                && (messageReject.getRefTagId() == CoreFields.SENDER_COMP_ID
                || messageReject.getRefTagId() == CoreFields.TARGET_COMP_ID)) {
            // only the session's own CompIDs are worth dropping the connection over: a wrong SenderCompID(49) or
            // TargetCompID(56) means the peer is not the one this session is for. OnBehalfOfCompID(115) and
            // DeliverToCompID(128) share the CompID problem reject reason but are a routing error inside an otherwise
            // valid session, so the message is rejected and the session carries on.
            logout("Logout due to incorrect received TARGET_COMP_ID or SENDER_COMP_ID");
        }
    }

    private FixAdminMessagesCodec buildFixAdminMessagesCodec(FixDictionaryId fixDictionaryId) {
        AdminMessageCodecContext adminMessageCodecContext = AdminMessageCodecContext.builder()
                .fixSession(this)
                .messageTypeRegistry(messageTypeRegistry)
                .messageFieldsRegistry(FixTMessageFieldsRegistry.get(fixDictionaryId, fixSessionId.getFixVersion(), fieldsRegistry))
                .fieldsRegistry(fieldsRegistry)
                .fixApplication(fixApplication)
                .fixSessionImplState(fixSessionImplState)
                .fixSessionMessagesStore(fixSessionMessagesStore)
                .fixSessionSettings(fixSessionSettings)
                .clock(clock)
                .executor(scheduler)
                .build();

        if (fixSessionId.getFixVersion().equals(FixRegularVersion.VERSION_42)) {
            return new Fix42AdminMessagesCodec(adminMessageCodecContext);
        } else if (fixSessionId.getFixVersion().equals(FixRegularVersion.VERSION_43)) {
            return new Fix43AdminMessagesCodec(adminMessageCodecContext);
        } else if (fixSessionId.getFixVersion().equals(FixRegularVersion.VERSION_44)) {
            return new Fix44AdminMessagesCodec(adminMessageCodecContext);
        } else if (fixSessionId.getFixVersion().equals(FixRegularVersion.VERSION_50)
                || fixSessionId.getFixVersion().equals(FixRegularVersion.VERSION_50_SP1)
                || fixSessionId.getFixVersion().equals(FixRegularVersion.VERSION_50_SP2)) {
            throw new IllegalStateException("Fix version 5.x is only supported using fix version FIXT and a target FIX session defaultApplVerID");
        } else if (fixSessionId.getFixVersion().equals(FixtVersion.FIXT_11)) {
            return new FixTAdminMessagesCodec(adminMessageCodecContext, fixSessionId);
        } else {
            throw new IllegalStateException("Fix version " + fixSessionId.getFixVersion() + " is not supported");
        }
    }

    public void onIOThreadTask(Runnable task, BiConsumer<Runnable, Exception> taskCallback) {
        try {
            task.run();
            if (taskCallback != null) {
                taskCallback.accept(task, null);
            }
        } catch (Exception ex) {
            if (taskCallback != null) {
                taskCallback.accept(task, ex);
            } else {
                log.error("Failed to execute task", ex);
            }
        }
    }

    private void checkSessionTime() {
        log.debug("Checking session time");
        // some minor calculations and state change processed in scheduler thread for now
        if (fixSessionImplState.isDisconnected()) {
            return;
        }
        try {
            Duration outsideSessionTimePreTriggerDelay = fixSessionSettings.getSessionScheduleSettings().getOutsideSessionTimePreTriggerDelay();
            long remainingMillisUntilEndOfSessionTimeframe = fixSessionScheduleManager.getSessionTimeLeft();
            if (remainingMillisUntilEndOfSessionTimeframe <= outsideSessionTimePreTriggerDelay.toMillis()) {
                ioSession.processTask(this::onPreTriggerOutsideSessionTime, this::onFailedCheckSessionTimeTask);
            }
            if (remainingMillisUntilEndOfSessionTimeframe == 0 && fixSessionImplState.isInsideSessionTime()) {
                ioSession.processTask(this::onOutsideSessionTime, this::onFailedCheckSessionTimeTask);
            } else if (remainingMillisUntilEndOfSessionTimeframe > 0 && !fixSessionImplState.isInsideSessionTime()) {
                ioSession.processTask(this::onInsideSessionTime, this::onFailedCheckSessionTimeTask);
            }
            checkSequenceResetDue();
        } catch (Exception ex) {
            log.error("Failed to process checkSessionTime task", ex);
        }
    }

    /**
     * @see ResendRecovery#isAnsweringOwnOpenEndedRequest()
     */
    private boolean isOverrunGapFillOfOurOwnOpenEndedRequest(MessageType messageType) {
        return messageType.code().equals(CoreMessageType.SEQUENCE_REQUEST)
                && resendRecovery.isAnsweringOwnOpenEndedRequest();
    }

    private void onFailedCheckSessionTimeTask(Runnable task, Exception error) {
        if (error != null && !(error instanceof EOFException)) {
            log.error("Failed to process Check session time task {}", task.getClass().getSimpleName(), error);
        }
    }

    private void onOutsideSessionTime() {
        logEvent("FIX session outside of timeframe");
        fixApplication.onOutsideSessionTime(this);
        if (fixSessionImplState.onOutsideSessionTime()) {
            sendLogoutRequest("Outside of session timeframe", false);
        }
    }

    @Override
    public void logEvent(String event) {
        if (fixMessagesLogger.isLoggingEvents()) {
            try {
                fixMessagesLogger.logEvent(clock.now(), event);
            } catch (FixMessagesLogger.LoggingException ex) {
                log.error("Failed to log event", ex);
            }
        }
    }

    @Override
    public void logEvent(String event, Object... params) {
        if (fixMessagesLogger.isLoggingEvents()) {
            try {
                fixMessagesLogger.logEvent(clock.now(), event, params);
            } catch (FixMessagesLogger.LoggingException ex) {
                log.error("Failed to log event", ex);
            }
        }
    }

    private void onInsideSessionTime() {
        logEvent("FIX session inside of timeframe");
        fixApplication.onInsideSessionTime(this);
        if (fixSessionImplState.onInsideSessionTime()) {
            sendLogonRequestIfNeeded();
        }
    }

    private void checkSequenceResetDue() {
        FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry dueReset =
                fixSessionScheduleManager.dueSequenceReset();
        if (dueReset == null || !dueReset.getInitiatesReset()) {
            return;
        }
        if (!fixSessionImplState.isLoggedIn()) {
            // the crossing has been consumed above rather than held: a session that was down at the agreed time and
            // comes back an hour later would otherwise reset an hour late, which is a reset the counterparties never
            // agreed to. Losing the day's roll leaves the numbering running until tomorrow, which is the safer of the
            // two - the end that awaits the reset acts on the Logon it receives, never on the clock
            logEvent("Scheduled sequence reset time reached while logged out, skipping this day's reset");
            return;
        }
        logEvent("Scheduled sequence reset time reached, resetting over the live session");
        ioSession.processTask(this::sendInSessionSequenceReset, this::onFailedCheckSessionTimeTask);
    }

    private void onPreTriggerOutsideSessionTime() {
        if (fixSessionImplState.onPreOutsideSessionTimeTrigger()) {
            fixApplication.onPreOutsideSessionTime(this, Duration.ofMillis(fixSessionScheduleManager.getSessionTimeLeft()));
        }
    }

    @Override
    public <M, P1, P2, P3> MessageExecutor<M, P1, P2, P3> getMessageExecutor(Class<?> routingNamespace, IntSupplier indexedKey) {
        return messageExecutors.getMessageExecutor(routingNamespace, indexedKey.getAsInt());
    }

    @Override
    public <T extends FixMessageEncoder<?>> FixMessageEncodersPool<T> newEncodersPool(String id, boolean multiThreadedBorrows, Class<T> encoderClass) {
        return newEncodersPool(id, messageSendingContexts.getCapacity(), multiThreadedBorrows, encoderClass);
    }

    @Override
    public <T extends FixMessageEncoder<?>> FixMessageEncodersPool<T> newEncodersPool(String id, int size, boolean multiThreadedBorrows, Class<T> encoderClass) {
        return (FixMessageEncodersPool<T>) allocatedEncodersPool.computeIfAbsent(id, poolId ->
                new FixMessageEncodersPoolImpl<>(p -> allocatedEncodersPool.remove(poolId),
                        multiThreadedBorrows, size, encoderClass, fixMessageEncoderFactory, this, fixSessionSettings.isPooledMessageEncodersDirectByteBuffers(), encodersClock));
    }

    @Override
    public <T extends FixMessageEncoder<?>> T newEncoder(Class<T> encoderClass) {
        return fixMessageEncoderFactory.newInstance(encoderClass, null, encodersAllocator, this, encodersClock);
    }

    @Override
    public int getWriteTasksQueueCapacity() {
        return messageSendingContexts.getCapacity();
    }

    @Override
    public void sendBusinessMessageReject(String rejectText, int businessRejectReason, String businessRejectRefId, MessageType refMsgType) {
        send(fixAdminMessagesCodec.generateBusinessReject(
                rejectText, businessRejectReason, fixSessionMessagesStore.getIncomingSeqNum(), businessRejectRefId, refMsgType), null);
    }

    @Override
    public <T extends FixApplication> T getApplication() {
        return (T) fixApplication;
    }

    @Override
    public boolean isConnected() {
        return ioSession != null;
    }

    @Override
    public void processTask(Runnable task) {
        processTask(task, IGNORE_TASK_RESULT);
    }

    @Override
    public void processTask(Runnable task, BiConsumer<Runnable, Exception> callback) {
        IOSession currentIOSession = ioSession;
        if (currentIOSession == null) {
            callback.accept(task, NO_CONNECTED_SESSION);
            return;
        }
        currentIOSession.processTask(task, callback);
    }

    private void runOnIOOrCurrentThread(Runnable task) {
        IOSession currentIOSession = ioSession;
        if (currentIOSession != null) {
            currentIOSession.processTask(task, IGNORE_TASK_RESULT);
            return;
        }
        task.run();
    }

    @Override
    public void disconnect(String disconnectMessage) {
        fixSessionImplState.setDesiredState(org.lolaf.staffix.api.session.FixSessionState.DISCONNECTED);
        runOnIOOrCurrentThread(() -> sendLogoutRequest(disconnectMessage, false));
    }

    @Override
    public void logoutPermanently(String message) {
        fixSessionImplState.setDesiredState(org.lolaf.staffix.api.session.FixSessionState.LOGGED_OUT);
        runOnIOOrCurrentThread(() -> sendLogoutRequest(message, false));
    }

    @Override
    public void logout(String message) {
        runOnIOOrCurrentThread(() -> sendLogoutRequest(message, false));
    }

    private void disconnect(Deadline deadline) {
        if (ioSession != null) {
            ioSession.stop(deadline);
        }
    }

    public void disconnect() {
        disconnect(Deadline.of(fixSessionSettings.getDisconnectMessagesFlushDeadline()));
    }

    @Override
    public void logon() {
        fixSessionImplState.setDesiredState(org.lolaf.staffix.api.session.FixSessionState.LOGGED_IN);
        runOnIOOrCurrentThread(this::sendLogonRequestIfNeeded);
    }

    private void sendLogonRequestIfNeeded() {
        if (!fixSessionImplState.canSendLoginRequest()) {
            return;
        }
        Boolean resetSeqNumOnLogon = fixSessionSettings.getResetSeqNumOnLogon();
        // consumed after the guard above, which latches "logon sent": a Logon that is not sent must not eat the reset
        if (fixSessionImplState.consumeSequenceResetOnNextLogon()) {
            resetSeqNumOnLogon = Boolean.TRUE;
            resetSequence("Sequence reset armed for this logon");
        } else if (resetSeqNumOnLogon != null && resetSeqNumOnLogon) {
            resetSequence("Initiator ResetSeqNumOnLogon enabled");
        }
        logEvent("Sending logon request");
        sendLoginMessage((int) fixSessionSettings.getHeartBeatInterval().getInitiatorInterval().toSeconds(), resetSeqNumOnLogon);
        logonOrLogoutCheckTask = scheduler.schedule(this::checkIsLoggedOnState, fixSessionSettings.getLogInOrOutResponseTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void checkIsLoggedOnState() {
        if (!fixSessionImplState.getActualState().equals(org.lolaf.staffix.api.session.FixSessionState.LOGGED_IN)) {
            logEvent("Timeout receiving logon response, disconnecting");
            disconnect();
        }
    }

    public void sendLogoutRequest(String message, boolean forced) {
        if (forced || fixSessionImplState.canSendLogoutRequest()) {
            fixApplication.onLogoutInitiated(this, message);
            fixSessionImplState.onLogoutInitiated(message);
            send(fixAdminMessagesCodec.generateLogout(message), null);
            logonOrLogoutCheckTask = scheduler.schedule(this::checkIsLoggedOutState, fixSessionSettings.getLogInOrOutResponseTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void checkIsLoggedOutState() {
        if (!fixSessionImplState.getActualState().equals(org.lolaf.staffix.api.session.FixSessionState.LOGGED_OUT)
                && !fixSessionImplState.getActualState().equals(org.lolaf.staffix.api.session.FixSessionState.DISCONNECTED)) {
            logEvent("Timeout receiving logout response, disconnecting");
            disconnect();
        }
    }

    public void awaitCounterpartyDisconnectAfterAcknowledgedLogout() {
        logonOrLogoutCheckTask = scheduler.schedule(() -> {
            if (fixSessionImplState.isLogoutPendingConnectionEnd()) {
                logEvent("Counterparty did not close the connection within %s of its logout being acknowledged, disconnecting",
                        fixSessionSettings.getLogInOrOutResponseTimeout());
                runOnIOOrCurrentThread(this::disconnect);
            }
        }, fixSessionSettings.getLogInOrOutResponseTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    void unscheduleTasksIfNeeded() {
        if (heartBeatTask != null) {
            heartBeatTask.cancel(true);
            heartBeatTask = null;
        }
        if (rttMeasurementsTask != null) {
            rttMeasurementsTask.cancel(true);
            rttMeasurementsTask = null;
            rttEstimator.reset();
        }
        cancelLogonOrLogoutTaskIfNeeded();
    }

    void cancelLogonOrLogoutTaskIfNeeded() {
        if (logonOrLogoutCheckTask != null && !logonOrLogoutCheckTask.isDone()) {
            logonOrLogoutCheckTask.cancel(true);
            logonOrLogoutCheckTask = null;
        }
    }

    @Override
    public boolean isLoggedIn() {
        return fixSessionImplState.isLoggedIn();
    }

    private void sendLoginMessage(int hbIntervalInSeconds, Boolean resetSequenceNumber) {
        send(fixAdminMessagesCodec.generateLogin(hbIntervalInSeconds, resetSequenceNumber,
                fixSessionSettings, fixApplication.getFixApiVersion(), fixSessionMessagesStore.getIncomingSeqNum(), getIncomingMessageTypes(), getOutgoingMessageTypes()), null);
    }

    @Override
    public void bufferize(FixMessageEncoder<?> encoder, UTCTime sendingTime) {
        bufferize(encoder, sendingTime, null, null, null);
    }

    @Override
    public <P1, P2> void bufferize(FixMessageEncoder<?> encoder, UTCTime sendingTime, MessageSendOperationCallback<P1, P2> messageSendOperationCallback, P1 param1, P2 param2) {
        FixSessionFixMessageContext ctx = messageSendingContexts.poll();
        if (ctx == null) {
            flush();
            ctx = messageSendingContexts.pollBlocking(pollBlockingIdleStrategy);
        }
        synchronized (bufferedMessageSendingContexts) {
            bufferedMessageSendingContexts.add(ctx.setup(byteBufferBorrower, encoder, sendingTime, messageSendOperationCallback, param1, param2));
        }
    }

    @Override
    public void flush() {
        synchronized (bufferedMessageSendingContexts) {
            if (!bufferedMessageSendingContexts.isEmpty()) {
                FixSessionBufferedFixMessageContext ctx = bufferMessageSendingContexts.pollBlocking(pollBlockingIdleStrategy);
                ioSession.send(ctx.setup(byteBufferBorrower, bufferedMessageSendingContexts), ctx, bufferedMessageSentCallback);
                bufferedMessageSendingContexts.clear();
            }
        }
    }

    @Override
    public void send(FixMessageEncoder<?> encoder, UTCTime sendingTime) {
        send(encoder, sendingTime, null, null, null);
    }

    public byte[] encodeStandaloneLogout(FixSessionId targetSessionId, String logoutText) {
        return encodeStandaloneReply(fixAdminMessagesCodec.generateLogout(logoutText), targetSessionId);
    }

    private byte[] encodeStandaloneReply(FixMessageEncoder<?> encoder, FixSessionId targetSessionId) {
        // null fixSession/fixApplication skips per-session application encoding hooks, the header is fully driven by targetSessionId
        ByteBuffer encoded = encoder.encode(ByteBuffer::allocate, 1L, targetSessionId, null, sendingTimeAccuracy, clock.now(), null);
        encoded.flip();
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    @Override
    public <P1, P2> void send(FixMessageEncoder<?> encoder, UTCTime sendingTime, MessageSendOperationCallback<P1, P2> messageSendOperationCallback, P1 param1, P2 param2) {
        if (holdingOutgoingMessages && holdWhileRecovering(encoder, sendingTime, messageSendOperationCallback, param1, param2)) {
            return;
        }
        FixSessionFixMessageContext ctx = messageSendingContexts.pollBlocking(pollBlockingIdleStrategy);
        IOWriter.ByteBufferBuilder byteBufferBuilder = ctx.setup(byteBufferBorrower, encoder, sendingTime, messageSendOperationCallback, param1, param2);
        if (ioSession != null) {
            ioSession.send(byteBufferBuilder, ctx, messageSentCallback);
        } else {
            try {
                messageSentCallback.onMessageWriteCallback(byteBufferBuilder.build().flip(), NO_CONNECTED_SESSION, ctx); // very important do not forget to flip message
            } catch (IOException e) {
                // terminal state don't care if we do not return the eventually allocated ByteBuffer to the pool
                messageSentCallback.onMessageWriteCallback(null, NO_CONNECTED_SESSION, ctx);
            }
        }
    }

    /**
     * Holds an application message back while a retransmission this session asked for is still under way, as section
     * 4.3.11 recommends: "the initiator and acceptor should wait a short period of time following receipt of the
     * Logon(35=A) message from the counterparty before transmitting queued or new application messages to permit
     * both sides to synchronize the FIX session".
     * <p>
     * Session level messages are never held - the recovery is made of them.
     */
    private <P1, P2> boolean holdWhileRecovering(FixMessageEncoder<?> encoder, UTCTime sendingTime,
                                                 MessageSendOperationCallback<P1, P2> messageSendOperationCallback,
                                                 P1 param1, P2 param2) {
        if (encoder.getMessageType().isAdmin()) {
            return false;
        }
        List<HeldOutgoingMessage> heldMessages = fixSessionImplState.getHeldOutgoingMessages();
        if (heldMessages.size() >= fixSessionSettings.getMaxOutgoingMessagesHeldDuringRecovery()) {
            logEvent("Refusing to send %s, already holding %s application messages while recovering",
                    encoder.getMessageType(), heldMessages.size());
            // the same lifecycle a sent message's encoder gets from FixSessionFixMessageContext.release(), which
            // this one never reaches
            if (!encoder.isReusable()) {
                encoder.destroy();
            }
            encoder.release();
            callOnMessageCallbackIfNeeded(new IOException("Too many application messages held back while the session recovers missing messages"),
                    messageSendOperationCallback, param1, param2);
            return true;
        }
        heldMessages.add(HeldOutgoingMessage.of(encoder, sendingTime, messageSendOperationCallback, param1, param2));
        return true;
    }

    void startHoldingOutgoingMessagesIfNeeded() {
        holdingOutgoingMessages = fixSessionSettings.getMaxOutgoingMessagesHeldDuringRecovery() > 0;
    }

    void releaseHeldOutgoingMessages() {
        if (!holdingOutgoingMessages) {
            return;
        }
        // cleared first: sending them goes back through send(), which must no longer hold anything
        holdingOutgoingMessages = false;
        List<HeldOutgoingMessage> heldMessages = fixSessionImplState.getHeldOutgoingMessages();
        if (!heldMessages.isEmpty()) {
            logEvent("Sending the %s application messages held back while recovering", heldMessages.size());
            List<HeldOutgoingMessage> released = new ArrayList<>(heldMessages);
            heldMessages.clear();
            released.forEach(held -> held.send(this));
        }
    }

    /**
     * Runs a retransmission off the IO thread, bound to the connection current when it was asked for. Called on the IO
     * thread, where that connection can be read.
     */
    void executeResend(Consumer<IOSession> resend) {
        IOSession connection = ioSession;
        if (connection == null) {
            return;
        }
        if (resendExecutor == null) {
            resendExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fix-resend-" + fixInstanceId + "-" + fixSessionId.getId());
                thread.setDaemon(true);
                return thread;
            });
        }
        resendExecutor.execute(() -> resend.accept(connection));
    }

    private void shutdownResendExecutorIfNeeded(Deadline deadline) {
        if (resendExecutor == null) {
            return;
        }
        ExecutorService stopping = resendExecutor;
        resendExecutor = null;
        stopping.shutdownNow();
        try {
            // Deadline floors at zero and awaitTermination(0) does not wait at all, so an expired deadline is still
            // given a millisecond: the point is to leave the thread nowhere to be but finished
            if (!stopping.awaitTermination(Math.max(1L, deadline.getRemainingTime().toMillis()), TimeUnit.MILLISECONDS)) {
                log.warn("Timed out waiting for the retransmission thread of FIX session {} to stop", fixSessionId);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sends on the connection given rather than the current one: a retransmission answers the connection that asked
     * for it, and must never reach the one that replaced it.
     */
    void sendWithSeqNum(IOSession connection, FixMessageEncoder<?> encoder, long outgoingSequenceNumber) {
        connection.send(encoder.encode(connection::borrow, outgoingSequenceNumber, fixSessionId, fixApplication, sendingTimeAccuracy, clock.now(), this), encoder.getMessageType(),
                (byteBuffer, e, messageType) -> logOutgoingFixMessageMessage(byteBuffer.position(0), messageType, e), true);
    }

    @Override
    public void testRequest(String testRequest) {
        send(fixAdminMessagesCodec.generateTestRequest(testRequest), null);
    }

    @Override
    public Optional<RttMeasurement> getRttMeasurement() {
        return rttEstimator.getMeasurement();
    }

    public void onDisconnection() {
        boolean wasLoggedIn = fixSessionImplState.isLoggedIn();
        fixSessionImplState.onDisconnection();
        ioSession = null;
        logEvent("FIX session disconnected");
        unscheduleTasksIfNeeded();
        // important keep an allocator since we can also send a message when the session is offline
        this.byteBufferBorrower = ByteBuffer::allocate;
        if (wasLoggedIn) {
            if (fixSessionImplState.isLogoutSent()) {
                // the Logout this session sent was never acknowledged - the counterparty dropped the connection
                // instead of answering, or never answered at all and the wait ran out. The session still ended the
                // way this side asked for, so it is a logout rather than a line failure: reporting it as a remote
                // disconnection would cancel the orders of a client that asked to be logged out under
                // CANCEL_ON_DISCONNECT_ONLY, and leave them live under CANCEL_ON_LOGOUT_ONLY
                String sentLogoutMessage = fixSessionImplState.getSentLogoutMessage();
                fixSessionImplState.onLogoutProcessed(true);
                fixApplication.onLogout(this, sentLogoutMessage, null);
            } else {
                // nothing was ever sent, the connection simply went: a hard disconnection
                fixSessionImplState.onLogoutProcessed(false);
                fixApplication.onLogout(this, "Remote disconnection", null);
            }
        } else if (fixSessionImplState.isLogoutPendingConnectionEnd()) {
            // a logout is finished with when the connection goes, and that is the whole rule: it holds for the side
            // that acknowledged one and waited for the peer to close, and for the side that asked for one and closed
            // itself once the acknowledgement came back
            fixSessionImplState.onLogoutProcessed(true);
        }
        fixApplication.onDisconnected(this);
        authenticatedSubject = null;
    }

    public boolean onConnection(IOSession ioSession, Collection<Certificate> remoteCertificates) {
        if (fixSessionImplState.getDesiredState().equals(FixSessionState.DISCONNECTED)) {
            logEvent("FIX session desired state is DISCONNECTED, disconnecting immediately");
            return false;
        }
        this.ioSession = ioSession;
        this.ioSession.setId(getFixSessionId().getId());
        logEvent("FIX session connected");
        this.remoteCertificates = remoteCertificates;
        byteBufferBorrower = ioSession::borrow;
        fixSessionImplState.onConnection();
        firstMessageIsLogonOrLogoutCheck = false;
        // important clean potential still in flight parsed message chunk
        fixMessageParser.reset();
        catchUpOnSessionTimeCrossedWhileDisconnected();
        sendLogonRequestIfNeeded();
        return true;
    }

    private void catchUpOnSessionTimeCrossedWhileDisconnected() {
        boolean withinSessionTime = fixSessionScheduleManager.isWithinSessionTime();
        if (withinSessionTime && !fixSessionImplState.isInsideSessionTime()) {
            // fires the application callback and, on an initiator wanting to be logged in, the logon the reopened
            // window calls for - the sendLogonRequestIfNeeded() below then finds the request already sent
            onInsideSessionTime();
        } else if (!withinSessionTime && fixSessionImplState.isInsideSessionTime()) {
            onOutsideSessionTime();
        }
    }

    public void stop(String message, Deadline stopDeadline) {
        fixSessionImplState.stop();
        unscheduleTasksIfNeeded();
        if (sessionTimeCheckTask != null) {
            sessionTimeCheckTask.cancel(true);
            sessionTimeCheckTask = null;
        }
        // before anything else touches the connection: a retransmission still running would otherwise carry on
        // writing into a session being torn down, and the logout below is what the peer should see next
        shutdownResendExecutorIfNeeded(stopDeadline.fromRemainingTime(0.2));
        fixApplication.onSessionPreDestroy(this);
        if (isLoggedIn()) {
            logout(message);
            if (!stopDeadline.fromRemainingTime(0.5).waitAsLongAs(this::isLoggedIn)) {
                log.warn("Timed out to wait for complete logout on session {}", fixSessionId);
            }
        }
        messageExecutors.releaseAll(stopDeadline);
        fixApplication.onSessionDestroyed(this);
        if (fixSessionPlugins != null) {
            for (FixSessionPlugin<?, ?> l : fixSessionPlugins) {
                l.onSessionDestroyed(fixInstanceId, fixSessionId);
            }
        }
        logEvent("FIX Session destroyed");
        disconnect(stopDeadline);
        logEvent("FIX Session stopped");
        releaseResources(stopDeadline);
    }

    private void releaseResources(Deadline stopDeadline) {
        fixMessagesLogger.stop(stopDeadline);
        fixSessionMessagesStore.stop(stopDeadline);
        new ArrayList<>(allocatedEncodersPool.values()).forEach(FixMessageEncodersPool::destroy);
        allocatedEncodersPool.clear();
    }

    private FixMessageDecoder getTargetDecoder(MessageType messageType) {
        if (!firstMessageIsLogonOrLogoutCheck) {
            if (!messageType.isAdmin()
                    || (!messageType.code().equals(CoreMessageType.LOGON)
                    && !messageType.code().equals(CoreMessageType.LOGOUT)
                    && !messageType.code().equals(CoreMessageType.RESEND_REQUEST))) {
                logEvent("First message received is not logon or logout: %s, disconnecting", messageType.code());
                send(fixAdminMessagesCodec.generateReject("First received message is not logon or logout",
                        SessionRejectReasonCodes.INVALID_MSGTYPE.getCode(), 1, 0, messageType), null);
                send(fixAdminMessagesCodec.generateLogout("First received message is not logon or logout"), null);
                throw new IllegalStateException("First message received is not logon or logout: " + messageType.code() + ", disconnecting");
            }
            firstMessageIsLogonOrLogoutCheck = true;
        }

        if (fixAdminMessagesCodec.isAdminMessage(messageType)) {
            return fixAdminMessagesCodec.getDecoderForAdminMessage(messageType);
        }
        FixMessageDecoder d = decoders.get(messageType);
        if (d != null) {
            return d;
        }
        if (fixApplication.onNoDecoderSetupForMessage(this, messageType)) {
            this.sendBusinessMessageReject("Message type '" + messageType.code() + "' is not supported by application",
                    BusinessRejectReasonCodes.UNSUPPORTED_MESSAGE_TYPE.getCode(), null, messageType);
        }
        return VoidDecoder.getInstance(messageType);
    }

    public void onWatermarkEvent(boolean highWatermarkReached, long bytesLeftToWrite) {
        fixApplication.onNetworkWatermarkEvent(this, highWatermarkReached, bytesLeftToWrite);
    }

    public void onMessageRead(ByteBuffer message, long localReceiveTimeInNanos) {
        try {
            fixMessageParser.parseMessages(message, fixMessageDecoderProvider, nextExpectedIncomingSeqNumSupplier, localReceiveTimeInNanos);
        } catch (Exception ex) {
            log.warn("Failed message processing for session {}, stopping it", fixSessionId, ex);
            logEvent("Failed message processing: %s", ex.getMessage());
            message.clear();
            disconnect();
            return;
        }
        if (resendRecovery.isOutOfSequenceMessagesReplayRequested()) {
            // a resend completed while parsing this read: the messages held back on top of the gap it closed can now
            // be processed, and the parser above is done with its buffer
            resendRecovery.replayOutOfSequenceMessages();
        }
    }

    private void manageHeartbeats() {
        UTCTime now = clock.now();
        if (fixSessionImplState.isTestRequestResponseTimedOut(now)) {
            logEvent("No response to TestRequest within heartbeat interval, disconnecting");
            disconnect();
            return;
        }
        if (fixSessionImplState.isTestRequestRequired(now)) {
            String testRequestId = "Heartbeat-" + now.getEpochSeconds();
            logEvent("No message received for heartbeat interval, sending TestRequest %s", testRequestId);
            send(fixAdminMessagesCodec.generateTestRequest(testRequestId), null, (sendingError, callbackParam1, callbackParam2) -> {
                if (sendingError == null) {
                    rttEstimator.recordTestRequestSent(testRequestId, clock.nanoTime(), clock.nowEpochNanos());
                    fixSessionImplState.markTestRequestSent(now);
                }
            }, null, null);
        }
        if (fixSessionImplState.isHeartBeatSendingRequired(now)) {
            send(fixAdminMessagesCodec.generateHeartbeat(null), null);
        }
        manageStalledRetransmission();
    }

    private void manageStalledRetransmission() {
        Duration timeout = fixSessionSettings.getResendRequestResponseTimeout();
        if (timeout.isZero()) {
            return;
        }
        // the tick this runs on is a second long, so anything shorter than that is one tick rather than none
        int timeoutSeconds = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, timeout.getSeconds()));
        ResendRecovery.StalledResendAction action = resendRecovery.onResendRequestStallCheck(timeoutSeconds);
        if (action.equals(ResendRecovery.StalledResendAction.NOTHING)) {
            return;
        }
        ResendRecovery.ResendRequest pending = resendRecovery.getPendingResendRequest();
        if (pending == null) {
            // the answer landed on the IO thread while this was deciding the session had given up on it
            return;
        }
        if (action.equals(ResendRecovery.StalledResendAction.GIVE_UP)) {
            logout(String.format("No answer to the ResendRequest from %s to %s, the session cannot be recovered",
                    pending.getFromSeqNum(), pending.getToSeqNum()));
            return;
        }
        // whatever is left of it: the part already answered is behind NextNumIn and asking for it again would have
        // the peer retransmit messages this session has processed
        long fromSeqNum = fixSessionMessagesStore.getIncomingSeqNum();
        if (fromSeqNum > pending.getToSeqNum()) {
            // the same race as above, caught one step later: the range completed on the IO thread while this was
            // reading it, so there is nothing left to ask for
            return;
        }
        requestRetransmission(fromSeqNum, pending.getToSeqNum(), String.format("No answer to the ResendRequest from %s to %s for %ss",
                pending.getFromSeqNum(), pending.getToSeqNum(), timeoutSeconds));
    }

    public void onTestRequestResponseReceived(String testReqID, UTCTime sendingTime, long recvMonotonicNanos, long recvWallTimeNanos) {
        fixSessionImplState.onTestRequestResponseReceived();
        rttEstimator.recordHeartbeatReceived(testReqID, recvMonotonicNanos, recvWallTimeNanos, sendingTime.toEpochNanos())
                .ifPresent(this::onRttSample);
        fixApplication.onTestRequestResponse(this, testReqID, sendingTime);
    }

    private void onRttSample(RttMeasurement measurement) {
        if (fixSessionPlugins != null) {
            for (FixSessionPlugin<?, ?> fixSessionPlugin : fixSessionPlugins) {
                fixSessionPlugin.onRttMeasurement(measurement);
            }
        }
    }

    private void sendInSessionSequenceReset() {
        if (!fixSessionImplState.isLoggedIn()) {
            logEvent("Ignoring in-session sequence reset: the session is not logged in");
            return;
        }
        resetSequence("In-session reset");
        fixSessionImplState.onInSessionResetSent();
        sendLoginMessage(fixSessionImplState.getHeartbeatInterval(), Boolean.TRUE);
    }

    private void resetSequence(String message) {
        logEvent("Resetting sequence: %s", message);
        fixSessionMessagesStore.resetSequenceNumbers();
    }

    /**
     * Sends the message a caller wrote as a string - the administrative send, from an operator's console or a script.
     * <p>
     * Only the body of what was handed in goes out: {@link AdminFixMessageTransformer} throws the frame of the pasted
     * message away and refuses anything this session's dictionary does not describe, and the session's own encoder then
     * puts a header and a trailer of its own around the fields, with the sequence number, sending time, body length and
     * checksum this session owes its peer. Which is the point of the exercise - a message copied out of a log cannot be
     * put back on the wire as it was.
     * <p>
     * Validation is this call's; delivery is the session's. Anything wrong with the message is thrown back here, and
     * the send itself is handed to the session thread like every other.
     *
     * @param fixMessage  the message
     * @param separator   the character between its fields
     * @param possDupFlag whether the message is the same one going out again, its SendingTime(52) then becoming
     *                    OrigSendingTime(122) under a PossDupFlag(43)=Y
     * @throws IllegalArgumentException if the string is not a message this session could send
     * @throws IllegalStateException    if the session is not logged in
     */
    public void adminSendFixMessage(String fixMessage, char separator, boolean possDupFlag) {
        if (!isLoggedIn()) {
            throw new IllegalStateException("Cannot send a FIX message on FIX session " + fixSessionId
                    + ": it is not logged in");
        }
        DecodedFixMessage bodyFields = adminFixMessageTransformer().transform(fixMessage, separator, possDupFlag);
        GenericFixMessageEncoder encoder = new GenericFixMessageEncoder(bodyFields.getMessageType()).begin();
        bodyFields.foreach((field, value) -> encoder.addField(field, value, ByteArraySerde.instance()));
        logEvent("Admin API send %s", bodyFields.getMessageType().code());
        send(encoder, null);
    }

    /**
     * Built on first use and kept: it holds a {@link org.lolaf.staffix.codec.decoders.FixMessageParser} of its own, which is
     * stateful, and administrative sends are rare enough that one instance serialized on this session is all it needs.
     */
    private synchronized AdminFixMessageTransformer adminFixMessageTransformer() {
        if (adminFixMessageTransformer == null) {
            adminFixMessageTransformer = new AdminFixMessageTransformer(fixSessionId, messageTypeRegistry, fieldsRegistry,
                    fixSessionSettings.getValidationSettings(), clock, sendingTimeAccuracy);
        }
        return adminFixMessageTransformer;
    }

    public void adminSetIncomingSeqNum(long seqNum) {
        failIfStoreStopped("set incoming sequence number");
        logEvent("Admin API set incoming sequence: %s", seqNum);
        fixSessionMessagesStore.storeNextIncomingSeqNum(seqNum);
    }

    public void adminSetOutgoingSeqNum(long seqNum) {
        failIfStoreStopped("set outgoing sequence number");
        logEvent("Admin API set outgoing sequence: %s", seqNum);
        fixSessionMessagesStore.storeNextOutgoingSeqNum(seqNum);
    }

    private void failIfStoreStopped(String operation) {
        if (!fixSessionMessagesStore.isStarted()) {
            throw new IllegalStateException("Cannot " + operation + " on FIX session " + fixSessionId
                    + ": its resources have been released");
        }
    }

    /**
     * Sends a SequenceReset(35=4) with GapFillFlag(123)=N - a hard reset - announcing {@code newSeqNum} as the
     * MsgSeqNum(34) this session will send next, and renumbers this side to match once the message is on the wire.
     * <p>
     * Section 4.8.6: the peer applies it without regard to the reset's own MsgSeqNum(34), and answers one that would
     * lower its expected sequence number with a Reject(35=3). Nothing is checked here - the peer's answer is the
     * check - so a caller that announces a number the peer has already passed simply gets refused.
     * <p>
     * This is the live session counterpart of {@link #adminSetOutgoingSeqNum}, which renumbers silently and is only
     * meaningful when both ends have agreed to it while the session is down. Nothing else in staffix sends this
     * message, and it is deliberately absent from {@link FixSession} and from the admin API: renumbering a live
     * session is destructive. It exists as a method rather than as a recipe because of the ordering - the reset
     * consumes a MsgSeqNum of its own, so this side may only be renumbered once the message has actually gone out,
     * which is why the store is written from the send callback rather than straight after the call.
     */
    public void hardSequenceReset(long newSeqNum) {
        logEvent("Hard SequenceReset announcing NewSeqNum %s", newSeqNum);
        send(fixAdminMessagesCodec.generateSequenceReset(newSeqNum, false), null,
                (sendingError, unused1, unused2) -> {
                    if (sendingError == null) {
                        fixSessionMessagesStore.storeNextOutgoingSeqNum(newSeqNum);
                    }
                }, null, null);
    }

    public long adminGetIncomingSeqNum() {
        return fixSessionMessagesStore.getIncomingSeqNum();
    }

    public long adminGetOutgoingSeqNum() {
        return fixSessionMessagesStore.getOutgoingSeqNum();
    }

    public void adminResetSequence(AdminApi.ResetFixSessionMode resetFixSessionMode) {
        runOnIOOrCurrentThread(() -> {
            logEvent("Admin API reset sequence: %s", resetFixSessionMode);
            applyAdminResetSequence(resetFixSessionMode);
        });
    }

    /**
     * Every mode of {@link #adminResetSequence} runs here, on the session's own thread.
     * <p>
     * Two reasons, and the second was a bug. Moving the sequence numbers from whichever thread called the admin API
     * races the session thread that is reading and writing them as it sends and receives. And where a mode also puts
     * a message on the wire, the reset has to land on the right side of it:
     * {@link AdminApi.ResetFixSessionMode#LOGOUT_LOGON_REST_NUM_FLAG} used to queue its Logout to this thread through
     * {@link #logout} while resetting on the caller's, so the numbering could go back to 1 before the Logout was
     * sent - and a Logout arriving as MsgSeqNum(34)=1 is answered by the peer with a "MsgSeqNum too low" Logout of
     * its own, which is the opposite of a clean cycle.
     * <p>
     * {@link #logout} and {@link #logon} route through {@code processTask} themselves, which runs a task inline when
     * it is already on this thread, so calling them from here keeps the order they are written in.
     */
    private void applyAdminResetSequence(AdminApi.ResetFixSessionMode resetFixSessionMode) {
        switch (resetFixSessionMode) {
            case RESET_SEQUENCE:
                resetSequence("Admin API reset sequence");
                break;
            case LOGOUT_LOGON_REST_NUM_FLAG:
                cycleSessionWithSequenceReset();
                break;
            case RESET_SEQUENCE_IN_SESSION:
                sendInSessionSequenceReset();
                break;
        }
    }

    private void cycleSessionWithSequenceReset() {
        if (!fixSessionImplState.canSendLogoutRequest()) {
            logEvent("Ignoring admin reset sequence through logout and logon: the session is not logged in");
            return;
        }
        fixSessionImplState.runOnceLogoutProcessed(fixSessionImplState::armSequenceResetOnNextLogon);
        sendLogoutRequest("Admin reset sequence", false);
    }

    private void messageSentCallback(ByteBuffer message, Exception sendingError, FixSessionFixMessageContext context) {
        MessageType sentMessageType = context.getEncoder().getMessageType();
        long outgoingSeqNum = context.getOutgoingSeqNum();
        FixSession.MessageSendOperationCallback<?, ?> callback = context.getMessageSendOperationCallback();
        Object messageSendOperationCallbackParam1 = context.getMessageSendOperationCallbackParam1();
        Object messageSendOperationCallbackParam2 = context.getMessageSendOperationCallbackParam2();
        if (message == null) {
            callOnMessageCallbackIfNeeded(sendingError, callback, messageSendOperationCallbackParam1, messageSendOperationCallbackParam2);
            return;
        }
        if (sendingError != null) {
            fixApplication.onMessageSendingFailure(this, sentMessageType, message.position(0), sendingError);
        }
        if (sentMessageType.isStorable() && !fixSessionMessagesStore.filter(sentMessageType, message)) {
            fixSessionMessagesStore.storeMessageSent(outgoingSeqNum, message.position(0));
        } else {
            fixSessionMessagesStore.storeNextOutgoingSeqNum(outgoingSeqNum + 1);
        }

        logOutgoingFixMessageMessage(message.position(0), sentMessageType, sendingError);

        callOnMessageCallbackIfNeeded(sendingError, callback, messageSendOperationCallbackParam1, messageSendOperationCallbackParam2);
        fixSessionImplState.onMessageSent(context.getSendingTime());
    }

    private void callOnMessageCallbackIfNeeded(Exception sendingError, MessageSendOperationCallback callback, Object messageSendOperationCallbackParam1, Object messageSendOperationCallbackParam2) {
        if (callback != null) {
            try {
                callback.onMessageCallback(sendingError, messageSendOperationCallbackParam1, messageSendOperationCallbackParam2);
            } catch (Exception ex) {
                log.error("Failed to call MessageSendOperationCallback on FIX session {}", fixSessionId, ex);
            }
        }
    }

    private void bufferedMessagesSentCallback(ByteBuffer bufferedWritesMessage, Exception sendingError, FixSessionBufferedFixMessageContext bufferedMessagesSendingContext) {
        int sendingContextsCount = bufferedMessagesSendingContext.getSendingContextsCount();
        FixSessionFixMessageContext[] sendingContexts = bufferedMessagesSendingContext.getSendingContexts();
        for (int i = 0; i < sendingContextsCount; i++) {
            FixSessionFixMessageContext msc = sendingContexts[i];
            ByteBuffer messageToReturnToPool = msc.getMessage();
            messageSentCallback.onMessageWriteCallback(messageToReturnToPool, sendingError, msc);
            // ByteBuffers in bufferedWritesContexts needs to be manually returned to the IOBuffers pool
            ioSession.unborrow(messageToReturnToPool);
        }
        bufferedMessagesSendingContext.release();
    }

    private void logOutgoingFixMessageMessage(ByteBuffer message, MessageType messageType, Exception sendingError) {
        if (fixMessagesLogger.isLoggingOutgoing()) {
            if (sendingError == null) {
                try {
                    fixMessagesLogger.logOutgoing(clock.now(), messageType, message);
                } catch (Exception ex) {
                    log.warn("Failed to log message", ex);
                }
            } else {
                byte[] dst = new byte[message.limit()];
                message.get(dst);
                logEvent("Failed to send message: (%s), will be eventually resent on remote session reconnection: %s", sendingError.getMessage(), new String(dst, SerDe.CHARSET));
            }
        }
    }

    /**
     * What a rolled back message leaves behind for NextNumIn, which is the one thing every branch of
     * {@link #onMessageDecodingFailed} has to agree on.
     */
    private enum RolledBackSequenceNumber {
        /**
         * The message was received and its MsgSeqNum(34) is accounted for, so the numbering moves on past it.
         */
        CONSUMED,
        /**
         * NextNumIn stays where it is, the session still expecting the sequence number this message carried - or,
         * where the message was ahead of it, the one it is still missing.
         */
        LEFT_UNCHANGED
    }
}