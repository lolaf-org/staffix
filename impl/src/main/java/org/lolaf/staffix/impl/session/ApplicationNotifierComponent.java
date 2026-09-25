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
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.time.UTCTime;

import java.util.List;

/**
 * Tells the application what the session did, and does it last of every component, so an application callback
 * always sees a session the engine has finished moving.
 */
@RequiredArgsConstructor
class ApplicationNotifierComponent implements FixSessionLayerComponent {

    private final FixApplication fixApplication;
    private final FixSessionImpl fixSession;
    private final FieldsRegistry fieldsRegistry;
    private final MessageTypeRegistry messageTypeRegistry;
    private final List<FixMessageDecoder> fixMessageDecoders;
    private String sentLogoutMessage;
    private String receivedLogoutText;
    private DecodedFixMessage receivedLogout;
    private boolean logoutPending;

    @Override
    public void onSessionStarted(FixSessionLayerComponents components) {
        fixApplication.onSessionCreated(fixSession, fieldsRegistry, messageTypeRegistry, fixMessageDecoders);
    }

    @Override
    public void onLogonCompleted(LogonCompleted logon) {
        fixApplication.onLogon(fixSession, logon.getLogonMessage());
    }

    @Override
    public void onLocalLogoutInitiated(String message) {
        sentLogoutMessage = message;
        logoutPending = true;
        fixApplication.onPreLogout(fixSession, message, true);
    }

    @Override
    public void onRemoteLogoutInitiated(String message) {
        logoutPending = true;
        fixApplication.onPreLogout(fixSession, message, false);
    }

    /**
     * Kept for {@link #onLoggedOutConnectionClosed}, as the application hears of the logout only once the connection
     * is gone, and the received message does not outlive this callback.
     */
    @Override
    public void onLoggedOutConnectionOpen(String message, DecodedFixMessage logoutMessage) {
        receivedLogoutText = message;
        receivedLogout = logoutMessage.copy();
    }

    /**
     * A logout that never got its Logout(35=5) back is reported as the one this side asked for, or as the line failure
     * it was.
     */
    @Override
    public void onLoggedOutConnectionClosed(boolean cleanLogout) {
        reportLogout(cleanLogout);
    }

    private void reportLogout(boolean cleanLogout) {
        String logoutText = cleanLogout ? sentLogoutMessage : "Remote disconnection";
        String message = receivedLogoutText != null ? receivedLogoutText : logoutText;
        DecodedFixMessage logoutMessage = receivedLogout;
        sentLogoutMessage = null;
        receivedLogoutText = null;
        receivedLogout = null;
        logoutPending = false;
        fixApplication.onLogout(fixSession, message, logoutMessage);
    }

    @Override
    public void onTestRequestResponseReceived(String testReqId, UTCTime sendingTime, long receiveMonotonicNanos,
                                              long receiveWallTimeNanos) {
        fixApplication.onTestRequestResponse(fixSession, testReqId, sendingTime);
    }

    /**
     * A Logout sent by a session that never logged on, refusing a Logon, ends with the connection without a logout
     * being processed, and still owes the application the {@code onLogout} its {@code onPreLogout} promised.
     */
    @Override
    public void onDisconnected() {
        if (logoutPending) {
            reportLogout(true);
        }
        fixApplication.onDisconnected(fixSession);
    }

    @Override
    public void onSessionStopped() {
        fixApplication.onSessionDestroyed(fixSession);
    }
}
