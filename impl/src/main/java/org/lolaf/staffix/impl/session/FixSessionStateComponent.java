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
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.FixSessionState;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A session's state, and the transitions the specification allows between them.
 *
 * <p>Logon and logout are tracked as sent-flags separately from the state itself because both are exchanges
 * rather than moments: a session that has sent a Logout but not had one back is in neither condition.
 *
 * <p>It is the first component of {@link FixSessionLayerComponents}, so every other one reads a state that has
 * already moved. It reacts and answers; what a transition sets off belongs to the components that follow it. The
 * exception is the one-shot of {@link #runOnceLogoutProcessed(Runnable)}, which an administrative operation leaves
 * for the next logout and which runs here, last of this state's own doing.
 */
public class FixSessionStateComponent implements FixSessionLayerComponent {

    private final boolean acceptorSession;
    private final AtomicBoolean logonSent;
    private final AtomicBoolean logoutSent;
    private final AtomicReference<FixSessionState> actualState;
    private final FixSessionScheduleManager fixSessionScheduleManager;
    private final AtomicBoolean inSessionResetPending;
    private final AtomicBoolean sequenceResetOnNextLogon;
    private final AtomicBoolean logoutProcessed;
    private Runnable onLogoutProcessedTask;
    @Setter
    @Getter
    private FixSessionState desiredState;
    @Getter
    private String sentLogoutMessage;
    @Getter
    private boolean adminOnlyMessagesAllowed;
    @Getter
    private boolean started;

    public FixSessionStateComponent(boolean acceptorSession, FixSessionState desiredState,
                                    FixSessionScheduleManager fixSessionScheduleManager) {
        this.acceptorSession = acceptorSession;
        this.desiredState = desiredState;
        this.actualState = new AtomicReference<>(FixSessionState.DISCONNECTED);
        this.logonSent = new AtomicBoolean();
        this.logoutSent = new AtomicBoolean();
        this.fixSessionScheduleManager = fixSessionScheduleManager;
        this.adminOnlyMessagesAllowed = true;
        this.inSessionResetPending = new AtomicBoolean();
        this.sequenceResetOnNextLogon = new AtomicBoolean();
        this.logoutProcessed = new AtomicBoolean(true);
        this.started = true;
    }

    @Override
    public void onSessionStopping(Deadline deadline) {
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

    /**
     * Whether a window reopening is this session's to act on: an initiator that wants to be logged in and has not
     * asked yet.
     */
    public boolean canLogonForReopenedWindow() {
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

    @Override
    public void onLogonCompleted(LogonCompleted logon) {
        adminOnlyMessagesAllowed = false;
        actualState.set(FixSessionState.LOGGED_IN);
        logonSent.set(false);
        logoutSent.set(false);
    }

    @Override
    public void onLogoutInitiated(String message) {
        sentLogoutMessage = message;
        logoutSent.set(true);
    }

    @Override
    public void onLogoutReceived(String message, DecodedFixMessage logoutMessage) {
        logoutProcessed.set(false);
        actualState.set(FixSessionState.LOGGED_OUT);
    }

    @Override
    public void onLogoutProcessed(boolean cleanLogout) {
        logoutProcessed.set(true);
        adminOnlyMessagesAllowed = true;
        logoutSent.set(false);
        logonSent.set(false);
        sentLogoutMessage = null;
        runPendingLogoutProcessedTask();
    }

    private void runPendingLogoutProcessedTask() {
        Runnable task = onLogoutProcessedTask;
        if (task != null) {
            onLogoutProcessedTask = null;
            task.run();
        }
    }

    @Override
    public void onConnectionClosed() {
        actualState.set(FixSessionState.DISCONNECTED);
    }

    @Override
    public void onConnected() {
        actualState.set(FixSessionState.CONNECTED);
        logoutSent.set(false);
        logonSent.set(false);
        // same for a task waiting on a logout that never came: it belongs to the session that just ended
        onLogoutProcessedTask = null;
    }

}