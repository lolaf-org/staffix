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
package org.lolaf.staffix.spring.boot.actuator;

import lombok.Getter;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.api.time.UTCTime;

/**
 * The per-session counters the Actuator endpoint and health indicator read.
 */
@Getter
public final class ActuatorSessionStats {

    private final String fixInstanceId;
    private final FixSession fixSession;

    private long messagesReceived;
    private long messagesSent;
    private long bytesReceived;
    private long bytesSent;

    private FixSessionState state;
    private volatile long lastEventEpochMillis = System.currentTimeMillis();
    private volatile long lastLogonEpochMillis;
    private volatile long lastLogoutEpochMillis;

    ActuatorSessionStats(String fixInstanceId, FixSession fixSession) {
        this.fixInstanceId = fixInstanceId;
        this.fixSession = fixSession;
    }

    void onLogon() {
        long now = System.currentTimeMillis();
        lastLogonEpochMillis = now;
        lastEventEpochMillis = now;
        state = FixSessionState.LOGGED_IN;
    }

    void onLogout() {
        long now = System.currentTimeMillis();
        lastLogoutEpochMillis = now;
        lastEventEpochMillis = now;
        state = FixSessionState.LOGGED_OUT;
    }

    void onMessageReceived(int payloadSize, UTCTime localReceiveTime) {
        messagesReceived++;
        bytesReceived += payloadSize;
        lastEventEpochMillis = localReceiveTime.toEpochMillis();
    }

    void onMessageSent(int payloadSize, UTCTime localSendingTime) {
        messagesSent++;
        bytesSent += payloadSize;
        lastEventEpochMillis = localSendingTime.toEpochMillis();
    }
}