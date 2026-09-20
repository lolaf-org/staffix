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
    private boolean logoutReported;

    @Override
    public void onSessionStarted(FixSessionLayerComponents components) {
        fixApplication.onSessionCreated(fixSession, fieldsRegistry, messageTypeRegistry, fixMessageDecoders);
    }

    @Override
    public void onLogonCompleted(LogonCompleted logon) {
        fixApplication.onLogon(fixSession, logon.getLogonMessage());
    }

    @Override
    public void onLogoutInitiated(String message) {
        sentLogoutMessage = message;
        fixApplication.onLogoutInitiated(fixSession, message);
    }

    @Override
    public void onLogoutReceived(String message, DecodedFixMessage logoutMessage) {
        fixApplication.onLogout(fixSession, message, logoutMessage);
        logoutReported = true;
    }

    /**
     * The application hears about a logout once per session, and a Logout(35=5) that never arrived is why this is not
     * simply the answer to one: a connection lost while the session was live ends it just as surely, and is reported
     * as the logout this side asked for or as the line failure it was.
     */
    @Override
    public void onLogoutProcessed(boolean cleanLogout) {
        if (!logoutReported) {
            fixApplication.onLogout(fixSession, cleanLogout ? sentLogoutMessage : "Remote disconnection", null);
        }
        logoutReported = false;
        sentLogoutMessage = null;
    }

    @Override
    public void onTestRequestResponseReceived(String testReqId, UTCTime sendingTime, long receiveMonotonicNanos,
                                              long receiveWallTimeNanos) {
        fixApplication.onTestRequestResponse(fixSession, testReqId, sendingTime);
    }

    @Override
    public void onDisconnected() {
        fixApplication.onDisconnected(fixSession);
    }

    @Override
    public void onSessionStopped() {
        fixApplication.onSessionDestroyed(fixSession);
    }
}
