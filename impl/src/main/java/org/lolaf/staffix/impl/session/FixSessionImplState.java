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
import org.lolaf.staffix.api.session.CancelOnDisconnectType;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.api.time.UTCTime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A session's state, and the transitions the specification allows between them.
 *
 * <p>Logon and logout are tracked as sent-flags separately from the state itself because both are exchanges
 * rather than moments: a session that has sent a Logout but not had one back is in neither condition.
 */
public class FixSessionImplState {

    private final boolean acceptorSession;
    private final AtomicBoolean logonSent;
    private final AtomicBoolean logoutSent;
    private final AtomicReference<FixSessionState> actualState;
    private final FixSessionImpl fixSession;
    private final FixSessionScheduleManager fixSessionScheduleManager;
    /**
     * The gap recovery, which is a session state of its own and large enough to be kept as one: what the peer still
     * owes this session, and what arrived on top of it meanwhile.
     */
    @Getter
    private final ResendRecovery resendRecovery;
    @Getter
    private final List<HeldOutgoingMessage> heldOutgoingMessages;
    private final AtomicBoolean inSessionResetPending;
    private final AtomicBoolean sequenceResetOnNextLogon;
    private final AtomicBoolean logoutProcessed;
    private Runnable onLogoutProcessedTask;
    @Getter
    private boolean insideSessionTime;
    private boolean preOutsideSessionTimeTriggered;
    @Setter
    @Getter
    private FixSessionState desiredState;
    @Getter
    private String sentLogoutMessage;
    @Getter
    private boolean adminOnlyMessagesAllowed;
    private CancelOnDisconnectType cancelOnDisconnectType;
    private int codTimeoutWindowInMillis;
    private long lastMessageSentInEpochSeconds;
    private long lastMessageReceivedInEpochSeconds;
    private long pendingTestRequestSentInEpochSeconds;
    @Getter
    private int heartbeatInterval;
    @Getter
    private boolean started;

    public FixSessionImplState(FixSessionImpl fixSession, boolean acceptorSession, FixSessionState desiredState,
                               FixSessionScheduleManager fixSessionScheduleManager) {
        this.fixSession = fixSession;
        this.acceptorSession = acceptorSession;
        this.desiredState = desiredState;
        this.actualState = new AtomicReference<>(FixSessionState.DISCONNECTED);
        this.logonSent = new AtomicBoolean();
        this.logoutSent = new AtomicBoolean();
        this.fixSessionScheduleManager = fixSessionScheduleManager;
        this.insideSessionTime = fixSessionScheduleManager.isWithinSessionTime();
        this.adminOnlyMessagesAllowed = true;
        this.resendRecovery = new ResendRecovery(fixSession);
        this.heldOutgoingMessages = new ArrayList<>();
        this.inSessionResetPending = new AtomicBoolean();
        this.sequenceResetOnNextLogon = new AtomicBoolean();
        this.logoutProcessed = new AtomicBoolean(true);
        this.started = true;
    }

    public void stop() {
        started = false;
    }

    public FixSessionState getActualState() {
        return actualState.get();
    }

    private boolean isState(FixSessionState state) {
        return actualState.get().equals(state);
    }

    /**
     * Between a connection and the Logon(35=A) exchange completing, and again once a logout has been processed: the
     * transport is up but the session is not, which is where an initiator may log on from.
     */
    private boolean isConnectedOrLoggedOut() {
        return isState(FixSessionState.CONNECTED) || isState(FixSessionState.LOGGED_OUT);
    }

    private boolean wantsToBeLoggedIn() {
        return desiredState.equals(FixSessionState.LOGGED_IN);
    }

    public boolean isLoggedIn() {
        return isState(FixSessionState.LOGGED_IN);
    }

    public boolean isDisconnected() {
        return isState(FixSessionState.DISCONNECTED);
    }

    public boolean canSendLogoutRequest() {
        return isLoggedIn() && !logoutSent.get();
    }

    public boolean isLogoutPendingConnectionEnd() {
        return !logoutProcessed.get();
    }

    /**
     * Whether this side asked for the logout that is under way. Set by {@link #onLogoutInitiated(String)} and cleared
     * by {@link #onLogoutProcessed(boolean)}, so it still answers while a connection is ending and tells a session
     * that logged itself out from one that lost its line.
     */
    public boolean isLogoutSent() {
        return logoutSent.get();
    }

    public boolean onPreOutsideSessionTimeTrigger() {
        if (!preOutsideSessionTimeTriggered) {
            preOutsideSessionTimeTriggered = true;
            return true;
        }
        return false;
    }

    public boolean onOutsideSessionTime() {
        insideSessionTime = false;
        return canSendLogoutRequest();
    }

    public boolean onInsideSessionTime() {
        insideSessionTime = true;
        preOutsideSessionTimeTriggered = false;
        return !acceptorSession
                && !isLoggedIn()
                && wantsToBeLoggedIn()
                && !logonSent.get();
    }

    public boolean canSendLoginRequest() {
        return !acceptorSession
                && fixSessionScheduleManager.isWithinSessionTime()
                && isConnectedOrLoggedOut()
                && wantsToBeLoggedIn()
                && !logonSent.getAndSet(true);
    }


    public boolean isLogoutInitiatedRemotely() {
        return (isState(FixSessionState.CONNECTED) || isLoggedIn()) && !logoutSent.get();
    }

    public void onInSessionResetSent() {
        inSessionResetPending.set(true);
    }

    public boolean consumeInSessionResetPending() {
        return inSessionResetPending.getAndSet(false);
    }

    public void armSequenceResetOnNextLogon() {
        sequenceResetOnNextLogon.set(true);
    }

    public boolean consumeSequenceResetOnNextLogon() {
        return sequenceResetOnNextLogon.getAndSet(false);
    }

    public void runOnceLogoutProcessed(Runnable task) {
        onLogoutProcessedTask = task;
    }

    public boolean canSendLoginResponse() {
        return acceptorSession
                && isConnectedOrLoggedOut()
                && wantsToBeLoggedIn()
                && !logonSent.get();
    }

    public void onLogon(int heartbeatInterval, CancelOnDisconnectType cancelOnDisconnectType, int codTimeoutWindowInMillis) {
        this.heartbeatInterval = heartbeatInterval;
        this.cancelOnDisconnectType = cancelOnDisconnectType;
        this.codTimeoutWindowInMillis = codTimeoutWindowInMillis;
        adminOnlyMessagesAllowed = false;
        actualState.set(FixSessionState.LOGGED_IN);
        logonSent.set(false);
        logoutSent.set(false);
        fixSession.onLogonProcessed();
    }

    public void onLogoutInitiated(String message) {
        sentLogoutMessage = message;
        logoutSent.set(true);
    }

    public void onLogoutReceived() {
        fixSession.unscheduleTasksIfNeeded();
        logoutProcessed.set(false);
        actualState.set(FixSessionState.LOGGED_OUT);
    }

    public void onLogoutProcessed(boolean cleanLogout) {
        logoutProcessed.set(true);
        adminOnlyMessagesAllowed = true;
        logoutSent.set(false);
        logonSent.set(false);
        preOutsideSessionTimeTriggered = false;
        resendRecovery.onSessionEnded();
        // the outgoing ones are not dropped though: sending them now stores them, so they reach the peer on the
        // next connection the way any other message sent while disconnected does
        fixSession.releaseHeldOutgoingMessages();
        sentLogoutMessage = null;
        lastMessageSentInEpochSeconds = 0;
        lastMessageReceivedInEpochSeconds = 0;
        pendingTestRequestSentInEpochSeconds = 0;
        fixSession.onLogoutProcessed(cancelOnDisconnectType, codTimeoutWindowInMillis, cleanLogout);
        cancelOnDisconnectType = null;
        codTimeoutWindowInMillis = 0;
        heartbeatInterval = 0;
        // last, so that the task sees a session whose logout is done with rather than one half way through it
        Runnable task = onLogoutProcessedTask;
        if (task != null) {
            onLogoutProcessedTask = null;
            task.run();
        }
    }

    public void onDisconnection() {
        actualState.set(FixSessionState.DISCONNECTED);
    }

    public void onConnection() {
        actualState.set(FixSessionState.CONNECTED);
        logoutSent.set(false);
        logonSent.set(false);
        resendRecovery.onNewConnection();
        // same for a task waiting on a logout that never came: it belongs to the session that just ended
        onLogoutProcessedTask = null;
    }

    public boolean isTestRequestRequired(UTCTime now) {
        return lastMessageReceivedInEpochSeconds + heartbeatInterval < now.getEpochSeconds();
    }

    public boolean isHeartBeatSendingRequired(UTCTime now) {
        return lastMessageSentInEpochSeconds + heartbeatInterval <= now.getEpochSeconds();
    }

    public boolean isTestRequestResponseTimedOut(UTCTime now) {
        return pendingTestRequestSentInEpochSeconds > 0
                && lastMessageReceivedInEpochSeconds < pendingTestRequestSentInEpochSeconds
                && pendingTestRequestSentInEpochSeconds + heartbeatInterval < now.getEpochSeconds();
    }

    public void markTestRequestSent(UTCTime sendingTime) {
        pendingTestRequestSentInEpochSeconds = sendingTime.getEpochSeconds();
    }

    public void onTestRequestResponseReceived() {
        pendingTestRequestSentInEpochSeconds = 0;
    }

    public void onMessageSent(UTCTime sendingTime) {
        lastMessageSentInEpochSeconds = sendingTime.getEpochSeconds();
    }

    public void onMessageReceived(UTCTime receiveTime) {
        lastMessageReceivedInEpochSeconds = receiveTime.getEpochSeconds();
    }

}