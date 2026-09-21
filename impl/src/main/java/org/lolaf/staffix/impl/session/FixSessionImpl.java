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

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.codec.FixMessageEncodersPool;
import org.lolaf.staffix.api.executor.MessageExecutor;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.*;
import org.lolaf.staffix.api.session.plugins.PluginContext;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.codec.decoders.FixMessageParser;
import org.lolaf.staffix.impl.FixSessionRuntimeDependencies;
import org.lolaf.staffix.impl.executor.MessageExecutorsRuntime;
import org.lolaf.staffix.impl.executor.SessionMessageExecutors;
import org.lolaf.staffix.impl.threading.ExternalThread;

import javax.security.auth.Subject;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * A FIX session: the connection it runs over, the {@link FixSession} the application holds, and the points where
 * what happens to the session is announced.
 *
 * <p>The session layer protocol itself is not here. Each concern of it is a {@link FixSessionLayerComponent},
 * built and registered by {@link SessionWiring} and reached through {@link FixSessionLayerComponents}; the
 * conformance scenarios in {@code fix-conformance} are the tests for them. What is left in this class is the
 * connection, the facade, and the emitting of the events the components react to.
 */
@Slf4j
public class FixSessionImpl implements FixSession {

    static final IOException NO_CONNECTED_SESSION = new IOException("No connected session");
    private static final BiConsumer<Runnable, Exception> IGNORE_TASK_RESULT = (task, error) -> {
        if (error == NO_CONNECTED_SESSION || error instanceof EOFException) {
            log.debug("Skipped FIX session task {}, the connection is gone: {}", task.getClass().getName(), error.getMessage());
        } else if (error != null) {
            log.error("Failed to execute FIX session task {}", task.getClass().getName(), error);
        }
    };

    @Getter
    private final FixSessionId fixSessionId;
    private final FixMessageParser fixMessageParser;
    private final FixApplication fixApplication;
    private final SessionWiring wiring;
    @Getter
    private final FixSessionSettings fixSessionSettings;
    private final FixMessagesLogger.Logger fixMessagesLogger;
    private final FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore;
    private final FixSessionStateComponent fixSessionState;
    private final FixSessionLayerComponents fixSessionLayerComponents;
    private final HeldOutgoingMessagesComponent heldOutgoingMessages;
    private final ResendRecovery resendRecovery;
    private final MessageRejectsComponent messageRejects;
    private final CodecsComponent codecs;
    private final OutgoingMessagesComponent outgoingMessages;
    private final AdminOperationsComponent adminOperations;
    private final Function<MessageType, FixMessageDecoder> fixMessageDecoderProvider;
    private final LongSupplier nextExpectedIncomingSeqNumSupplier;
    @Getter
    private final Clock clock;
    @Getter
    private final FieldsRegistry fieldsRegistry;
    @Getter
    private final FixSessionRegistry fixSessionRegistry;
    private final SessionMessageExecutors messageExecutors;
    private final ExecutorService disconnectedSessionsExecutor;
    private final PluginsComponent plugins;
    /**
     * The thread of {@link #disconnectedSessionsExecutor} currently running this session's work, so that a task
     * already on the owner is recognised as such. Written and cleared by that thread, read by any.
     */
    private volatile Thread offlineOwnerThread;
    @Getter
    private Collection<Certificate> remoteCertificates;
    private IOSession ioSession;
    private boolean connectionHeldLogged;
    @Getter
    @Setter
    private Subject authenticatedSubject;

    public FixSessionImpl(String fixInstanceId,
                          FixSessionSettings fixSessionSettings,
                          FixSessionRuntimeDependencies fixSessionRuntimeDependencies,
                          ScheduledExecutorService scheduledExecutorService,
                          IOSettings ioSettings,
                          MessageExecutorsRuntime messageExecutorsRuntime,
                          Clock providedClock) {
        this.fixSessionSettings = fixSessionSettings;
        this.fixSessionRegistry = fixSessionRuntimeDependencies.getFixSessionRegistry();
        this.wiring = new SessionWiring(this, fixInstanceId, fixSessionSettings, fixSessionRuntimeDependencies,
                scheduledExecutorService, ioSettings, messageExecutorsRuntime, providedClock);
        this.fixSessionId = wiring.fixSessionId;
        this.fixApplication = wiring.fixApplication;
        this.fieldsRegistry = wiring.fieldsRegistry;
        this.clock = wiring.clock;
        this.fixSessionLayerComponents = wiring.components;
        this.fixSessionMessagesStore = wiring.messagesStore;
        this.fixMessagesLogger = wiring.messagesLogger;
        this.fixMessageParser = wiring.messageParser;
        this.messageExecutors = wiring.messageExecutors;
        this.disconnectedSessionsExecutor = wiring.disconnectedSessionsExecutor;
        // the registry is where a component lives; the session keeps the ones it uses per message in a field
        this.fixSessionState = fixSessionLayerComponents.get(FixSessionStateComponent.class);
        this.outgoingMessages = fixSessionLayerComponents.get(OutgoingMessagesComponent.class);
        this.resendRecovery = fixSessionLayerComponents.get(RetransmissionComponent.class).getResendRecovery();
        this.heldOutgoingMessages = fixSessionLayerComponents.get(HeldOutgoingMessagesComponent.class);
        this.messageRejects = fixSessionLayerComponents.get(MessageRejectsComponent.class);
        this.codecs = fixSessionLayerComponents.get(CodecsComponent.class);
        this.adminOperations = fixSessionLayerComponents.get(AdminOperationsComponent.class);
        this.plugins = fixSessionLayerComponents.get(PluginsComponent.class);
        this.fixMessageDecoderProvider = codecs::getTargetDecoder;
        this.nextExpectedIncomingSeqNumSupplier = fixSessionMessagesStore::getIncomingSeqNum;
    }

    public boolean hasConfiguredPluginsWithTimeMeasurementRequired() {
        return plugins.requiresTimeMeasurement();
    }

    @ExternalThread
    public void start(FixSessionRuntimeDependencies fixSessionRuntimeDependencies) {
        for (FixApplicationSessionSettingDescriptor d : fixApplication.getRequiredFixSessionSettings()) {
            if (!fixSessionSettings.getFixApplicationSessionSettings().containsKey(d)) {
                throw new IllegalStateException("Fix session application settings " + d + " is missing for session " + fixSessionId.getId());
            }
        }
        fixMessagesLogger.start();
        fixSessionMessagesStore.start();

        List<FixMessageDecoder> fixMessageDecoders = this.fixApplication.setup(fixSessionSettings, this, codecs.getOutgoingMessageTypes());
        codecs.setupApplicationDecoders(fixMessageDecoders);
        wiring.setupApplicationComponents(this, fixSessionRuntimeDependencies, fixMessageDecoders);
        fixSessionLayerComponents.onSessionStarted();
        logEvent("Session %s created", fixSessionId);
    }

    @ExternalThread
    public void onSessionRemoved() {
        Deadline stopDeadline = Deadline.of(fixSessionSettings.getDisconnectMessagesFlushDeadline());
        stop("FIX session has been removed", stopDeadline);
        releaseResources(stopDeadline);
    }

    @Override
    public FixSessionState getDesiredState() {
        return fixSessionState.getDesiredState();
    }

    @Override
    public boolean isWithinSessionTime() {
        return fixSessionLayerComponents.get(SessionTimeWindowComponent.class).isInsideSessionTime();
    }

    public boolean isReadyToConnect() {
        if (fixSessionState.isStarted()) {
            if (fixSessionState.getDesiredState().equals(FixSessionState.DISCONNECTED)) {
                logConnectionHeldOnce("FIX session desired state is DISCONNECTED, holding off connecting");
                return false;
            }
            // the clock rather than the window flag: the flag only moves on a transition, which needs a connection,
            // so a session down when its window opens would never dial
            if (!fixSessionLayerComponents.get(SessionTimeWindowComponent.class).isWithinSessionTimeNow()) {
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
        return plugins.getPluginContext(pluginContextClass);
    }

    public void onMessageSent(ByteBuffer sentMessage, Object messageSendingContext, long localSendingStartTimeInNanos) {
        if (plugins.isEmpty()) {
            return;
        }
        if (messageSendingContext instanceof FixSessionFixMessageSendingContext) {
            triggerPluginMessageSent(sentMessage, localSendingStartTimeInNanos, (FixSessionFixMessageSendingContext) messageSendingContext);
        } else if (messageSendingContext instanceof FixSessionBufferedFixMessageContext) {
            FixSessionBufferedFixMessageContext ctxBuffered = (FixSessionBufferedFixMessageContext) messageSendingContext;
            for (int i = 0; i < ctxBuffered.getSendingContextsCount(); i++) {
                triggerPluginMessageSent(sentMessage, localSendingStartTimeInNanos, ctxBuffered.getSendingContexts()[i]);
            }
        }
    }

    private void triggerPluginMessageSent(ByteBuffer sentMessage, long localSendingStartTimeInNanos, FixSessionFixMessageSendingContext ctx) {
        plugins.onMessageSent(ctx.getMessageType(), sentMessage.limit(), localSendingStartTimeInNanos, ctx.getSendingTime());
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

    @Override
    public void logEvent(String event) {
        if (!fixMessagesLogger.isLoggingEvents()) {
            return;
        }
        IOSession connection = ioSession;
        if (connection != null && connection.isWithinIOThread()) {
            writeEvent(clock.now(), event);
            return;
        }
        // taken here, when the event happened, and made immutable: the clock hands out one reused instance
        writeEventOnTheSessionOwner(clock.now().asImmutable(), event);
    }

    @Override
    public void logEvent(String event, Object... params) {
        if (!fixMessagesLogger.isLoggingEvents()) {
            return;
        }
        IOSession connection = ioSession;
        if (connection != null && connection.isWithinIOThread()) {
            writeEvent(clock.now(), event, params);
            return;
        }
        // formatted here rather than by the logger: the parameters are the caller's and may have moved on by the
        // time the owner gets to them
        writeEventOnTheSessionOwner(clock.now().asImmutable(), String.format(event, params));
    }

    /**
     * The logger belongs to the session, so it is written by whichever thread owns it: the IO thread of its
     * connection, or the engine's thread for the sessions that have none. Only a session whose engine has stopped
     * has neither, and the lines that explain a teardown are worth keeping even written from here.
     */
    private void writeEventOnTheSessionOwner(UTCTime eventTime, String event) {
        try {
            runOnSessionOwnerThread(() -> writeEvent(eventTime, event));
        } catch (RejectedExecutionException noOwnerLeft) {
            writeEvent(eventTime, event);
        }
    }

    private void writeEvent(UTCTime eventTime, String event) {
        try {
            fixMessagesLogger.logEvent(eventTime, event);
        } catch (FixMessagesLogger.LoggingException ex) {
            log.error("Failed to log event", ex);
        }
    }

    private void writeEvent(UTCTime eventTime, String event, Object... params) {
        try {
            fixMessagesLogger.logEvent(eventTime, event, params);
        } catch (FixMessagesLogger.LoggingException ex) {
            log.error("Failed to log event", ex);
        }
    }

    @Override
    public <M, P1, P2, P3> MessageExecutor<M, P1, P2, P3> getMessageExecutor(Class<?> routingNamespace, IntSupplier indexedKey) {
        return messageExecutors.getMessageExecutor(routingNamespace, indexedKey.getAsInt());
    }

    @Override
    public <T extends FixMessageEncoder<?>> FixMessageEncodersPool<T> newEncodersPool(String id, boolean multiThreadedBorrows, Class<T> encoderClass) {
        return codecs.newEncodersPool(id, multiThreadedBorrows, encoderClass);
    }

    @Override
    public <T extends FixMessageEncoder<?>> FixMessageEncodersPool<T> newEncodersPool(String id, int size, boolean multiThreadedBorrows, Class<T> encoderClass) {
        return codecs.newEncodersPool(id, size, multiThreadedBorrows, encoderClass);
    }

    @Override
    public <T extends FixMessageEncoder<?>> T newEncoder(Class<T> encoderClass) {
        return codecs.newEncoder(encoderClass);
    }

    @Override
    public int getWriteTasksQueueCapacity() {
        return outgoingMessages.getWriteTasksQueueCapacity();
    }

    @Override
    @ExternalThread
    public void sendBusinessMessageReject(String rejectText, int businessRejectReason, String businessRejectRefId, MessageType refMsgType) {
        messageRejects.sendBusinessMessageReject(rejectText, businessRejectReason, businessRejectRefId, refMsgType);
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
    @ExternalThread
    public void processTask(Runnable task) {
        processTask(task, IGNORE_TASK_RESULT);
    }

    @Override
    @ExternalThread
    public void processTask(Runnable task, BiConsumer<Runnable, Exception> callback) {
        IOSession currentIOSession = ioSession;
        if (currentIOSession == null) {
            callback.accept(task, NO_CONNECTED_SESSION);
            return;
        }
        currentIOSession.processTask(task, callback);
    }

    IOSession currentIOSession() {
        return ioSession;
    }

    private LogonLogoutComponent logonLogoutComponent() {
        return fixSessionLayerComponents.get(LogonLogoutComponent.class);
    }

    void runOnSessionOwnerThread(Runnable task) {
        runOnSessionOwnerThread(task, ioSession);
    }

    void runOnSessionOwnerThread(Runnable task, IOSession currentIOSession) {
        if (currentIOSession != null) {
            currentIOSession.processTask(task, this::forwardToTheOwnerIfRefused);
            return;
        }
        if (offlineOwnerThread == Thread.currentThread()) {
            task.run();
            return;
        }
        disconnectedSessionsExecutor.execute(() -> runIfStillDisconnected(task));
    }

    /**
     * Whether the calling thread is the one allowed to touch this session's state: its connection's IO thread, or
     * the thread running its work while it has none.
     */
    boolean isWithinSessionOwnerThread(IOSession currentIOSession) {
        return currentIOSession != null ? currentIOSession.isWithinIOThread() : offlineOwnerThread == Thread.currentThread();
    }

    /**
     * The connection went between choosing it and handing the task over, so the session is owned by the executor
     * again. Anything else is a fault, logged rather than retried.
     */
    private void forwardToTheOwnerIfRefused(Runnable task, Exception error) {
        if (error == NO_CONNECTED_SESSION || error instanceof EOFException) {
            disconnectedSessionsExecutor.execute(() -> runIfStillDisconnected(task));
        } else if (error != null) {
            log.error("Failed to execute FIX session task {}", task.getClass().getName(), error);
        }
    }

    /**
     * A connection may have arrived while the task waited its turn, in which case the session belongs to an IO
     * thread again and this one hands it over rather than touching the session beside it.
     */
    private void runIfStillDisconnected(Runnable task) {
        IOSession currentIOSession = ioSession;
        if (currentIOSession == null) {
            offlineOwnerThread = Thread.currentThread();
            try {
                task.run();
            } finally {
                offlineOwnerThread = null;
            }
            return;
        }
        currentIOSession.processTask(task, this::forwardToTheOwnerIfRefused);
    }

    @Override
    @ExternalThread
    public void logoutPermanently(String message) {
        fixSessionState.setDesiredState(FixSessionState.LOGGED_OUT);
        runOnSessionOwnerThread(() -> logonLogoutComponent().sendLogoutRequest(message, false), ioSession);
    }

    @Override
    @ExternalThread
    public void logout(String message) {
        runOnSessionOwnerThread(() -> logonLogoutComponent().sendLogoutRequest(message, false), ioSession);
    }

    public void disconnect() {
        disconnect(Deadline.of(fixSessionSettings.getDisconnectMessagesFlushDeadline()));
    }

    @Override
    @ExternalThread
    public void disconnect(String disconnectMessage) {
        fixSessionState.setDesiredState(FixSessionState.DISCONNECTED);
        runOnSessionOwnerThread(() -> logonLogoutComponent().sendLogoutRequest(disconnectMessage, false), ioSession);
    }

    private void disconnect(Deadline deadline) {
        if (ioSession != null) {
            ioSession.stop(deadline);
        }
    }

    @Override
    @ExternalThread
    public void logon() {
        fixSessionState.setDesiredState(FixSessionState.LOGGED_IN);
        runOnSessionOwnerThread(() -> logonLogoutComponent().sendLogonRequestIfNeeded(), ioSession);
    }

    @Override
    public boolean isLoggedIn() {
        return fixSessionState.isLoggedIn();
    }

    @Override
    @ExternalThread
    public void bufferize(FixMessageEncoder<?> encoder, UTCTime sendingTime) {
        bufferize(encoder, sendingTime, null, null, null);
    }

    @Override
    @ExternalThread
    public <P1, P2> void bufferize(FixMessageEncoder<?> encoder, UTCTime sendingTime, MessageSendOperationCallback<P1, P2> messageSendOperationCallback, P1 param1, P2 param2) {
        outgoingMessages.bufferize(encoder, sendingTime, messageSendOperationCallback, param1, param2);
    }

    @Override
    @ExternalThread
    public void flush() {
        outgoingMessages.flush();
    }

    @Override
    @ExternalThread
    public void send(FixMessageEncoder<?> encoder, UTCTime sendingTime) {
        send(encoder, sendingTime, null, null, null);
    }

    public byte[] encodeStandaloneLogout(FixSessionId targetSessionId, String logoutText) {
        return codecs.encodeStandaloneLogout(targetSessionId, logoutText);
    }

    @Override
    @ExternalThread
    public <P1, P2> void send(FixMessageEncoder<?> encoder, UTCTime sendingTime, MessageSendOperationCallback<P1, P2> messageSendOperationCallback, P1 param1, P2 param2) {
        if (heldOutgoingMessages.holdIfRecovering(encoder, sendingTime, messageSendOperationCallback, param1, param2)) {
            return;
        }
        outgoingMessages.send(encoder, sendingTime, messageSendOperationCallback, param1, param2);
    }

    @Override
    @ExternalThread
    public void testRequest(String testRequest) {
        fixSessionLayerComponents.get(HeartbeatsComponent.class).sendTestRequest(testRequest);
    }

    @Override
    public Optional<RttMeasurement> getRttMeasurement() {
        return fixSessionLayerComponents.get(HeartbeatsComponent.class).getMeasurement();
    }

    public void onDisconnection() {
        boolean wasLoggedIn = fixSessionState.isLoggedIn();
        fixSessionLayerComponents.onConnectionClosed();
        ioSession = null;
        logEvent("FIX session disconnected");
        if (wasLoggedIn) {
            // the Logout this session sent was never acknowledged - the counterparty dropped the connection
            // instead of answering, or never answered at all and the wait ran out. The session still ended the
            // way this side asked for, so it is a logout rather than a line failure: reporting it as a remote
            // disconnection would cancel the orders of a client that asked to be logged out under
            // CANCEL_ON_DISCONNECT_ONLY, and leave them live under CANCEL_ON_LOGOUT_ONLY
            // nothing was ever sent, the connection simply went: a hard disconnection
            fixSessionLayerComponents.onLogoutProcessed(fixSessionState.isLogoutSent());
        } else if (fixSessionState.isLogoutPendingConnectionEnd()) {
            // a logout is finished with when the connection goes, and that is the whole rule: it holds for the side
            // that acknowledged one and waited for the peer to close, and for the side that asked for one and closed
            // itself once the acknowledgement came back
            fixSessionLayerComponents.onLogoutProcessed(true);
        }
        fixSessionLayerComponents.onDisconnected();
        authenticatedSubject = null;
    }

    public boolean onConnection(IOSession ioSession, Collection<Certificate> remoteCertificates) {
        if (fixSessionState.getDesiredState().equals(FixSessionState.DISCONNECTED)) {
            logEvent("FIX session desired state is DISCONNECTED, disconnecting immediately");
            return false;
        }
        this.ioSession = ioSession;
        this.ioSession.setId(getFixSessionId().getId());
        logEvent("FIX session connected");
        this.remoteCertificates = remoteCertificates;
        fixSessionLayerComponents.onConnected();
        // important clean potential still in flight parsed message chunk
        fixMessageParser.reset();
        fixSessionLayerComponents.get(SessionTimeWindowComponent.class).catchUpOnSessionTimeCrossedWhileDisconnected();
        logonLogoutComponent().sendLogonRequestIfNeeded();
        return true;
    }

    @ExternalThread
    public void stop(String message, Deadline stopDeadline) {
        fixSessionLayerComponents.onSessionStopping(stopDeadline);
        // before anything else touches the connection: a retransmission still running would otherwise carry on
        // writing into a session being torn down, and the logout below is what the peer should see next
        fixApplication.onSessionPreDestroy(this);
        if (isLoggedIn()) {
            logout(message);
            Deadline logoutAnswerDeadline = stopDeadline.fromRemainingTime(0.5);
            Duration logoutResponseTimeout = fixSessionSettings.getLogInOrOutResponseTimeout();
            if (logoutAnswerDeadline.getRemainingTime().compareTo(logoutResponseTimeout) > 0) {
                // no longer than any other Logout gets: the timeout of one already in flight was cancelled above, and
                // with an unlimited deadline a peer that never answers would otherwise be waited for forever
                logoutAnswerDeadline = Deadline.of(logoutResponseTimeout);
            }
            if (!logoutAnswerDeadline.waitAsLongAs(this::isLoggedIn)) {
                log.warn("Timed out to wait for complete logout on session {}", fixSessionId);
            }
        }
        messageExecutors.releaseAll(stopDeadline);
        fixSessionLayerComponents.onSessionStopped();
        logEvent("FIX Session destroyed");
        disconnect(stopDeadline);
        logEvent("FIX Session stopped");
        releaseResources(stopDeadline);
    }

    private void releaseResources(Deadline stopDeadline) {
        fixMessagesLogger.stop(stopDeadline);
        fixSessionMessagesStore.stop(stopDeadline);
        codecs.destroyEncodersPools();
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

    public void onTestRequestResponseReceived(String testReqID, UTCTime sendingTime, long recvMonotonicNanos, long recvWallTimeNanos) {
        fixSessionLayerComponents.onTestRequestResponseReceived(testReqID, sendingTime, recvMonotonicNanos, recvWallTimeNanos);
    }

    void sendInSessionSequenceReset() {
        if (!fixSessionState.isLoggedIn()) {
            logEvent("Ignoring in-session sequence reset: the session is not logged in");
            return;
        }
        resetSequence("In-session reset");
        fixSessionState.onInSessionResetSent();
        logonLogoutComponent().sendLoginMessage(fixSessionLayerComponents.get(HeartbeatsComponent.class).getHeartbeatInterval(), Boolean.TRUE);
    }

    void resetSequence(String message) {
        logEvent("Resetting sequence: %s", message);
        fixSessionMessagesStore.resetSequenceNumbers();
    }

    public void adminSendFixMessage(String fixMessage, char separator, boolean possDupFlag) {
        adminOperations.sendFixMessage(fixMessage, separator, possDupFlag);
    }

    public void adminSetIncomingSeqNum(long seqNum) {
        adminOperations.setIncomingSeqNum(seqNum);
    }

    public void adminSetOutgoingSeqNum(long seqNum) {
        adminOperations.setOutgoingSeqNum(seqNum);
    }

    public void hardSequenceReset(long newSeqNum) {
        adminOperations.hardSequenceReset(newSeqNum);
    }

    public long adminGetIncomingSeqNum() {
        return adminOperations.getIncomingSeqNum();
    }

    public long adminGetOutgoingSeqNum() {
        return adminOperations.getOutgoingSeqNum();
    }

    public void adminResetSequence(AdminApi.ResetFixSessionMode resetFixSessionMode) {
        adminOperations.resetSequence(resetFixSessionMode);
    }

}