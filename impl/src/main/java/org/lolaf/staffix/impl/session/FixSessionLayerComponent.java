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

import lombok.Value;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.CancelOnDisconnectType;
import org.lolaf.staffix.api.time.UTCTime;

/**
 * What happens to a FIX session at the session layer, for the parts of the engine that react to it: the state
 * machine, the heartbeats, the cancel on disconnect timer, the application and the plugins.
 *
 * <p>Every method does nothing by default, so a component implements only what it reacts to. The order they are
 * called in is part of the behaviour and belongs to {@link FixSessionLayerComponents}. The two events every message
 * raises are not here but on {@link FixSessionMessageListener}, which a component implements as well when it reacts
 * to the traffic itself.
 *
 * <p>This is the engine's own wiring, not an extension point: {@link org.lolaf.staffix.api.session.plugins.FixSessionPlugin}
 * is what an application implements, and the component that fans out to them is one of these.
 *
 * <p>A component runs on whichever thread announced what it is reacting to: the IO thread for everything a message
 * drives, and the connector's scheduler for what a timeout drives. Nothing here hops threads on a component's
 * behalf.
 */
public interface FixSessionLayerComponent {

    /**
     * The session is wired and every component is registered, which is where one that needs another takes it and
     * keeps it: resolving at this point rather than on every call, and after construction rather than during it,
     * where the order components are built in would decide what exists yet.
     */
    default void onSessionStarted(FixSessionLayerComponents components) {
    }

    /**
     * The transport is up. Nothing has been exchanged yet, so the session is connected rather than logged on.
     */
    default void onConnected() {
    }

    /**
     * The Logon(35=A) exchange is complete, with what it negotiated.
     */
    default void onLogonCompleted(LogonCompleted logon) {
    }

    /**
     * This side is sending a Logout(35=5), for the reason given. Announced before it goes out, so a component can
     * still act on a session that is logged in.
     */
    default void onLocalLogoutInitiated(String message) {
    }

    /**
     * The counterparty asks for a logout, announced before its Logout(35=5) is processed, so a component can still
     * act on a session that is logged in. Not fired for a Logout answering one this side sent.
     */
    default void onRemoteLogoutInitiated(String message) {
    }

    /**
     * A Logout(35=5) has arrived, asking for a logout or answering this side's: the session is logged out but the
     * connection is still open, for the acknowledgement to go out or the side that asked to close it.
     */
    default void onLoggedOutConnectionOpen(String message, DecodedFixMessage logoutMessage) {
    }

    /**
     * The connection is closed and the logout is over. Also fired when a logged on session loses its connection.
     *
     * @param cleanLogout whether the session ended through a logout rather than losing its connection
     */
    default void onLoggedOutConnectionClosed(boolean cleanLogout) {
    }

    /**
     * A Heartbeat(35=0) carrying the TestReqID(112) of a TestRequest(35=1) this session sent, which both answers it
     * and times the round trip. The two receive times are taken at the same moment: the monotonic one measures the
     * round trip, the wall clock one compares against the peer's SendingTime(52).
     */
    default void onTestRequestResponseReceived(String testReqId, UTCTime sendingTime, long receiveMonotonicNanos,
                                               long receiveWallTimeNanos) {
    }

    /**
     * The connection is gone and what belongs to it is torn down. {@link #onDisconnected()} follows, once a logout
     * under way has been completed.
     */
    default void onConnectionClosed() {
    }

    /**
     * The session is down and a logout it was in the middle of has been completed, which is the point the outside
     * world is told.
     */
    default void onDisconnected() {
    }

    /**
     * The session is stopping, before the logout it waits for.
     */
    default void onSessionStopping(Deadline deadline) {
    }

    /**
     * The session has stopped, the logout wait being over.
     */
    default void onSessionStopped() {
    }

    /**
     * What the Logon(35=A) exchange settled, which outlives the message it was read from.
     */
    @Value
    class LogonCompleted {

        int heartbeatInterval;
        CancelOnDisconnectType cancelOnDisconnectType;
        int codTimeoutWindowInMillis;
        DecodedFixMessage logonMessage;
    }
}
