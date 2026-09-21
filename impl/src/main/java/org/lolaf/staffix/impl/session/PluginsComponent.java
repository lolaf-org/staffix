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

import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.codec.FixMessageEncodingListener;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.RttMeasurement;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.PluginContext;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.impl.FixSessionRuntimeDependencies;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * The plugins of one session, and the fan out to them.
 *
 * <p>Two kinds of callback meet here. The session layer ones arrive as events, like every other component's; the
 * encoding and decoding ones are handed over directly by the session, being on the message path rather than
 * transitions.
 */
class PluginsComponent implements FixSessionLayerComponent, FixMessageEncodingListener {

    private static final FixSessionPlugin<?, ?>[] NONE = new FixSessionPlugin<?, ?>[0];

    private final String fixInstanceId;
    private final FixSessionId fixSessionId;
    private final FixSessionImpl fixSession;
    private FixSessionPlugin<?, ?>[] plugins = NONE;

    /**
     * Built with the session rather than with its plugins, which cannot exist until the application has declared its
     * decoders: an encoder built during the wiring reports here from the start, and an empty fan out answers the way
     * a session with no plugin does.
     *
     * <p>The session id is passed rather than read off the session, which is half built at this point and has not
     * taken its own id yet.
     */
    PluginsComponent(String fixInstanceId, FixSessionId fixSessionId, FixSessionImpl fixSession) {
        this.fixInstanceId = fixInstanceId;
        this.fixSessionId = fixSessionId;
        this.fixSession = fixSession;
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

    /**
     * Asks each configured plugin whether it wants this session, once the message types it decides on are known.
     * Called from {@code start()} before any component is told the session started, since what the codecs do with
     * the plugins depends on which of them are here.
     */
    void setup(FixSessionRuntimeDependencies runtimeDependencies, Set<MessageType> incomingMessageTypes,
               Set<MessageType> outgoingMessageTypes) {
        List<FixSessionPlugin<?, ?>> created = new ArrayList<>();
        runtimeDependencies.getFixSessionsPlugins().forEach(p ->
                p.onSessionCreated(fixInstanceId, fixSession, incomingMessageTypes, outgoingMessageTypes)
                        .ifPresent(l -> created.add(new FailSafeFixSessionPlugin<>(l))));
        this.plugins = created.isEmpty() ? NONE : created.toArray(new FixSessionPlugin[0]);
    }

    boolean isEmpty() {
        return plugins.length == 0;
    }

    FixSessionPlugin<?, ?>[] get() {
        return plugins;
    }

    boolean requiresTimeMeasurement() {
        return Arrays.stream(plugins).anyMatch(FixSessionPlugin::requiresTimeMeasurement);
    }

    <C extends PluginContext> Optional<C> getPluginContext(Class<C> pluginContextClass) {
        for (FixSessionPlugin<?, ?> plugin : plugins) {
            if (plugin.isForPluginContext(pluginContextClass)) {
                return (Optional<C>) plugin.getPluginContext();
            }
        }
        return Optional.empty();
    }

    @Override
    public void onLogonCompleted(LogonCompleted logon) {
        for (FixSessionPlugin<?, ?> plugin : plugins) {
            plugin.onLogon();
        }
    }

    @Override
    public void onLogoutProcessed(boolean cleanLogout) {
        for (FixSessionPlugin<?, ?> plugin : plugins) {
            plugin.onLogout();
        }
    }

    @Override
    public void onSessionStopped() {
        for (FixSessionPlugin<?, ?> plugin : plugins) {
            plugin.onSessionDestroyed(fixInstanceId, fixSessionId);
        }
    }

    @Override
    public void onEncodingStart(MessageType messageType, FixMessageEncoder<?> encoder, long encodingStartTimeInNanos) {
        if (isEmpty()) {
            return;
        }
        // Stash each plugin's per-message token on the encoder so the I/O-thread encoding callbacks
        // (which run on a different thread than this one) can hand it back for the same message. The token
        // is produced first, then handed straight back to the same plugin's onMessageEncodingStarted.
        Object[] tokens = encoder.pluginEncodingState(plugins.length);
        for (int i = 0; i < plugins.length; i++) {
            tokens[i] = beginEncoding(plugins[i], messageType, encodingStartTimeInNanos);
        }
    }

    @Override
    public void onEncodedBody(MessageType messageType, ByteBuffer encodedBody, FixMessageEncoder<?> encoder, long encodingStartTimeInNanos) {
        if (isEmpty()) {
            return;
        }
        Object[] tokens = encoder.pluginEncodingState();
        for (int i = 0; i < plugins.length; i++) {
            encodedBody(plugins[i], messageType, encodedBody, encoder, encodingStartTimeInNanos,
                    tokens != null ? tokens[i] : null);
        }
    }

    @Override
    public void onEncodingEnd(MessageType messageType, ByteBuffer encodedMessage, FixMessageEncoder<?> encoder, long encodingStartTimeInNanos) {
        if (isEmpty()) {
            return;
        }
        Object[] tokens = encoder.pluginEncodingState();
        for (int i = 0; i < plugins.length; i++) {
            encodingFinished(plugins[i], messageType, encodingStartTimeInNanos, tokens != null ? tokens[i] : null);
        }
    }

    void onMessageDecodingStarted(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        if (isEmpty()) {
            return;
        }
        for (FixSessionPlugin<?, ?> plugin : plugins) {
            plugin.onMessageDecodingStarted(messageType, localReceiveTimeInNanos, localReceiveTime);
        }
    }

    void onMessageDecodingFinished(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        if (isEmpty()) {
            return;
        }
        for (FixSessionPlugin<?, ?> plugin : plugins) {
            plugin.onMessageDecodingFinished(messageType, localReceiveTimeInNanos, localReceiveTime);
        }
    }

    void onMessageReceived(MessageType messageType, int payloadSize, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        if (isEmpty()) {
            return;
        }
        for (FixSessionPlugin<?, ?> plugin : plugins) {
            plugin.onMessageReceived(messageType, payloadSize, localReceiveTimeInNanos, localReceiveTime);
        }
    }

    void onMessageSent(MessageType messageType, int size, long localSendingStartTimeInNanos, UTCTime sendingTime) {
        for (FixSessionPlugin<?, ?> plugin : plugins) {
            plugin.onMessageSent(messageType, size, localSendingStartTimeInNanos, sendingTime);
        }
    }

    void onRttMeasurement(RttMeasurement measurement) {
        for (FixSessionPlugin<?, ?> plugin : plugins) {
            plugin.onRttMeasurement(measurement);
        }
    }
}
