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

import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.time.UTCTime;

import java.util.Arrays;

/**
 * The components a FIX session is made of, and the announcement of what the session does to them.
 *
 * <p>Components are called in the order they were registered, and that order is behaviour rather than an accident:
 * the session state first, since every other component reads it as it reacts; then the session's own concerns; then
 * the application and the plugins, so their callbacks see a session in its final state; and last the admin
 * operations, whose one-shot tasks run once everything else is done. Registering in that order is the caller's.
 *
 * <p>One order serves every event. Where a session layer moment looks as if it needed an order of its own it is two
 * moments, and it is emitted as two events: stopping and stopped, the connection closing and the session
 * disconnected. And a protocol answer, such as the Logout(35=5) acknowledging one received, is not a reaction: it
 * stays where it is decided, in the decoder.
 *
 * <p>The two events every message raises go to the single component that implements
 * {@link FixSessionMessageListener}, which registering a second one refuses. They are the only events on the hot
 * path, and one field spends a call per message where the fan out would walk every component to find the one that
 * reacts.
 *
 * <p>A component throwing is not caught. These are the engine's own, and a transition that failed is not something
 * to carry on from.
 */
public class FixSessionLayerComponents {

    private static final FixSessionLayerComponent[] NONE = new FixSessionLayerComponent[0];
    private static final FixSessionMessageListener NOTHING_LISTENING = new FixSessionMessageListener() {
    };

    private FixSessionLayerComponent[] components = NONE;
    private FixSessionMessageListener messageListener = NOTHING_LISTENING;

    /**
     * @throws IllegalStateException if a second {@link FixSessionMessageListener} is registered, there being room
     *                               for one: the per message events are a field rather than a list so that a message
     *                               costs a call rather than a loop
     */
    public void register(FixSessionLayerComponent component) {
        FixSessionLayerComponent[] registered = Arrays.copyOf(components, components.length + 1);
        registered[components.length] = component;
        components = registered;
        if (component instanceof FixSessionMessageListener) {
            if (messageListener != NOTHING_LISTENING) {
                throw new IllegalStateException("A FIX session listens to its messages through a single component, "
                        + messageListener.getClass().getSimpleName() + " here, and "
                        + component.getClass().getSimpleName() + " wants to be a second");
            }
            messageListener = (FixSessionMessageListener) component;
        }
    }

    /**
     * The component of that class, for the few things a session asks one of them rather than tells it: what the
     * heartbeat interval was settled at, what the last round trip measured.
     *
     * @throws IllegalStateException if no component of that class is registered, which is a wiring mistake rather
     *                               than a state a session can be in
     */
    public <C extends FixSessionLayerComponent> C get(Class<C> componentClass) {
        for (FixSessionLayerComponent component : components) {
            if (componentClass.isInstance(component)) {
                return componentClass.cast(component);
            }
        }
        throw new IllegalStateException("No " + componentClass.getSimpleName() + " registered on this FIX session");
    }

    public void onSessionStarted() {
        if (messageListener == NOTHING_LISTENING) {
            throw new IllegalStateException("A message listener must be registered");
        }
        for (FixSessionLayerComponent component : components) {
            component.onSessionStarted(this);
        }
    }

    public void onConnected() {
        for (FixSessionLayerComponent component : components) {
            component.onConnected();
        }
    }

    public void onLogonCompleted(FixSessionLayerComponent.LogonCompleted logon) {
        for (FixSessionLayerComponent component : components) {
            component.onLogonCompleted(logon);
        }
    }

    public void onLocalLogoutInitiated(String message) {
        for (FixSessionLayerComponent component : components) {
            component.onLocalLogoutInitiated(message);
        }
    }

    public void onRemoteLogoutInitiated(String message) {
        for (FixSessionLayerComponent component : components) {
            component.onRemoteLogoutInitiated(message);
        }
    }

    public void onLoggedOutConnectionOpen(String message, DecodedFixMessage logoutMessage) {
        for (FixSessionLayerComponent component : components) {
            component.onLoggedOutConnectionOpen(message, logoutMessage);
        }
    }

    public void onLoggedOutConnectionClosed(boolean cleanLogout) {
        for (FixSessionLayerComponent component : components) {
            component.onLoggedOutConnectionClosed(cleanLogout);
        }
    }

    public void onMessageReceived(UTCTime receiveTime) {
        messageListener.onMessageReceived(receiveTime);
    }

    public void onMessageSent(UTCTime sendingTime) {
        messageListener.onMessageSent(sendingTime);
    }

    public void onTestRequestResponseReceived(String testReqId, UTCTime sendingTime, long receiveMonotonicNanos,
                                              long receiveWallTimeNanos) {
        for (FixSessionLayerComponent component : components) {
            component.onTestRequestResponseReceived(testReqId, sendingTime, receiveMonotonicNanos, receiveWallTimeNanos);
        }
    }

    public void onConnectionClosed() {
        for (FixSessionLayerComponent component : components) {
            component.onConnectionClosed();
        }
    }

    public void onDisconnected() {
        for (FixSessionLayerComponent component : components) {
            component.onDisconnected();
        }
    }

    public void onSessionStopping(Deadline deadline) {
        for (FixSessionLayerComponent component : components) {
            component.onSessionStopping(deadline);
        }
    }

    public void onSessionStopped() {
        for (FixSessionLayerComponent component : components) {
            component.onSessionStopped();
        }
    }
}