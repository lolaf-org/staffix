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
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.impl.session.codec.FixAdminMessagesCodec;
import org.lolaf.staffix.impl.threading.SchedulerThread;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sections 4.3 and 4.4: the Logon(35=A) and Logout(35=5) exchanges, and the one timeout that watches whichever of
 * them is in flight.
 *
 * <p>Both are exchanges rather than messages, so each is sent with a deadline for its answer, and the session is
 * disconnected when none comes. A single task serves them: a session is only ever waiting on one of the two.
 */
@RequiredArgsConstructor
public class LogonLogoutComponent implements FixSessionLayerComponent {

    private final FixSessionImpl fixSession;
    private final FixSessionStateComponent fixSessionStateComponent;
    private final FixSessionLayerComponents fixSessionLayerComponents;
    private final FixSessionSettings fixSessionSettings;
    private final FixAdminMessagesCodec fixAdminMessagesCodec;
    private final FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore;
    private final FixApplication fixApplication;
    private final ScheduledExecutorService scheduler;
    private final CodecsComponent codecs;
    private ScheduledFuture<?> logonOrLogoutCheckTask;

    void sendLogonRequestIfNeeded() {
        if (!fixSessionStateComponent.canSendLoginRequest()) {
            return;
        }
        Boolean resetSeqNumOnLogon = fixSessionSettings.getResetSeqNumOnLogon();
        // consumed after the guard above, which latches "logon sent": a Logon that is not sent must not eat the reset
        if (fixSessionStateComponent.consumeSequenceResetOnNextLogon()) {
            resetSeqNumOnLogon = Boolean.TRUE;
            fixSession.resetSequence("Sequence reset armed for this logon");
        } else if (resetSeqNumOnLogon != null && resetSeqNumOnLogon) {
            fixSession.resetSequence("Initiator ResetSeqNumOnLogon enabled");
        }
        fixSession.logEvent("Sending logon request");
        sendLoginMessage((int) fixSessionSettings.getHeartBeatInterval().getInitiatorInterval().toSeconds(), resetSeqNumOnLogon);
        logonOrLogoutCheckTask = scheduler.schedule(this::checkIsLoggedOnState,
                fixSessionSettings.getLogInOrOutResponseTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    void sendLoginMessage(int hbIntervalInSeconds, Boolean resetSequenceNumber) {
        fixSession.send(fixAdminMessagesCodec.generateLogin(hbIntervalInSeconds, resetSequenceNumber, fixSessionSettings,
                fixApplication.getFixApiVersion(), fixSessionMessagesStore.getIncomingSeqNum(),
                codecs.getIncomingMessageTypes(), codecs.getOutgoingMessageTypes()), null);
    }

    /**
     * @param forced sends the Logout whatever the session state, for the refusals that answer a Logon this session
     *               will not accept
     */
    public void sendLogoutRequest(String message, boolean forced) {
        if (forced || fixSessionStateComponent.canSendLogoutRequest()) {
            fixSessionLayerComponents.onLogoutInitiated(message);
            fixSession.send(fixAdminMessagesCodec.generateLogout(message), null);
            logonOrLogoutCheckTask = scheduler.schedule(this::checkIsLoggedOutState,
                    fixSessionSettings.getLogInOrOutResponseTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * The side that acknowledged a Logout waits for the peer to close, and closes itself only if it never does
     * (FIX Session Testcases scenario 13 case B step 2).
     */
    public void awaitCounterpartyDisconnectAfterAcknowledgedLogout() {
        logonOrLogoutCheckTask = scheduler.schedule(this::checkCounterpartyClosedTheConnection,
                fixSessionSettings.getLogInOrOutResponseTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    @SchedulerThread
    private void checkCounterpartyClosedTheConnection() {
        if (fixSessionStateComponent.isLogoutPendingConnectionEnd()) {
            fixSession.logEvent("Counterparty did not close the connection within %s of its logout being acknowledged, disconnecting",
                    fixSessionSettings.getLogInOrOutResponseTimeout());
            fixSession.runOnIOOrCurrentThread(fixSession::disconnect);
        }
    }

    public void cancelLogonOrLogoutTaskIfNeeded() {
        if (logonOrLogoutCheckTask != null && !logonOrLogoutCheckTask.isDone()) {
            // never interrupting, here and for every task of this session: they run on the connector's shared
            // scheduler, and the logout timeout is the one disconnecting while this runs, waiting for it to finish
            logonOrLogoutCheckTask.cancel(false);
            logonOrLogoutCheckTask = null;
        }
    }

    @Override
    public void onLogonCompleted(LogonCompleted logon) {
        cancelLogonOrLogoutTaskIfNeeded();
    }

    @Override
    public void onLogoutReceived(String message, DecodedFixMessage logoutMessage) {
        cancelLogonOrLogoutTaskIfNeeded();
    }

    @Override
    public void onConnectionClosed() {
        cancelLogonOrLogoutTaskIfNeeded();
    }

    @Override
    public void onSessionStopping(Deadline deadline) {
        cancelLogonOrLogoutTaskIfNeeded();
    }

    @SchedulerThread
    private void checkIsLoggedOnState() {
        if (!fixSessionStateComponent.getActualState().equals(FixSessionState.LOGGED_IN)) {
            fixSession.logEvent("Timeout receiving logon response, disconnecting");
            fixSession.disconnect();
        }
    }

    @SchedulerThread
    private void checkIsLoggedOutState() {
        if (!fixSessionStateComponent.getActualState().equals(FixSessionState.LOGGED_OUT)
                && !fixSessionStateComponent.getActualState().equals(FixSessionState.DISCONNECTED)) {
            fixSession.logEvent("Timeout receiving logout response, disconnecting");
            fixSession.disconnect();
        }
    }
}
