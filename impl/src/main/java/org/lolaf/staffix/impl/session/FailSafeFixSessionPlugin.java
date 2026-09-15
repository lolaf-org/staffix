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
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixFieldsEncoder;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.RttMeasurement;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.PluginContext;
import org.lolaf.staffix.api.time.UTCTime;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Wraps a session plugin so a fault in metrics, tracing or any other observer cannot affect the session it
 * observes.
 */
@Slf4j
@Value
public class FailSafeFixSessionPlugin<C, T> implements FixSessionPlugin<C, T> {

    FixSessionPlugin<C, T> fixSessionPlugin;

    @Override
    public boolean isForPluginContext(Class<? extends PluginContext> pluginClass) {
        return fixSessionPlugin.isForPluginContext(pluginClass);
    }

    @Override
    public boolean requiresTimeMeasurement() {
        return fixSessionPlugin.requiresTimeMeasurement();
    }

    @Override
    public Optional<C> getPluginContext() {
        return fixSessionPlugin.getPluginContext();
    }

    @Override
    public void onDecoderSetup(FixMessageDecoder decoder, FixFieldsDecoderMapper fieldsDecoderMapper) {
        try {
            fixSessionPlugin.onDecoderSetup(decoder, fieldsDecoderMapper);
        } catch (Exception ex) {
            log.error("Failed to call onDecoderSetup() on FixMessageEventsListener {}", fixSessionPlugin.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void onSessionDestroyed(String fixInstanceId, FixSessionId fixSessionId) {
        try {
            fixSessionPlugin.onSessionDestroyed(fixInstanceId, fixSessionId);
        } catch (Exception ex) {
            log.error("Failed to call onSessionDestroyed() on FixMessageEventsListener {}", fixSessionPlugin.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void onLogon() {
        try {
            fixSessionPlugin.onLogon();
        } catch (Exception ex) {
            log.error("Failed to call onLogon() on FixMessageEventsListener {}", fixSessionPlugin.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void onMessageDecodingStarted(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        try {
            fixSessionPlugin.onMessageDecodingStarted(messageType, localReceiveTimeInNanos, localReceiveTime);
        } catch (Exception ex) {
            log.error("Failed to call onDecodingStarted() on FixMessageEventsListener {}", fixSessionPlugin.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void onMessageDecodingFinished(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        try {
            fixSessionPlugin.onMessageDecodingFinished(messageType, localReceiveTimeInNanos, localReceiveTime);
        } catch (Exception ex) {
            log.error("Failed to call onDecodingFinished() on FixMessageEventsListener {}", fixSessionPlugin.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void onMessageReceived(MessageType messageType, int payloadSize, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        try {
            fixSessionPlugin.onMessageReceived(messageType, payloadSize, localReceiveTimeInNanos, localReceiveTime);
        } catch (Exception ex) {
            log.error("Failed to call onMessageReceived() on FixMessageEventsListener {}", fixSessionPlugin.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public T getMessageEncodingToken(MessageType messageType, long localSendingTimeInNanos) {
        try {
            return fixSessionPlugin.getMessageEncodingToken(messageType, localSendingTimeInNanos);
        } catch (Exception ex) {
            log.error("Failed to call getMessageEncodingToken() on FixMessageEventsListener {}", fixSessionPlugin.getClass().getSimpleName(), ex);
            return null;
        }
    }

    @Override
    public void onMessageEncodingStarted(MessageType messageType, long localSendingTimeInNanos, T encodingToken) {
        try {
            fixSessionPlugin.onMessageEncodingStarted(messageType, localSendingTimeInNanos, encodingToken);
        } catch (Exception ex) {
            log.error("Failed to call onMessageEncodingStarted() on FixMessageEventsListener {}", fixSessionPlugin.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void onMessageEncodedBody(MessageType messageType, ByteBuffer encodedBody, FixFieldsEncoder<?> fixFieldsEncoder, long encodingStartTimeInNanos, T encodingToken) {
        try {
            fixSessionPlugin.onMessageEncodedBody(messageType, encodedBody, fixFieldsEncoder, encodingStartTimeInNanos, encodingToken);
        } catch (Exception ex) {
            log.error("Failed to call onMessageEncodedBody() on FixMessageEventsListener {}", fixSessionPlugin.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void onMessageEncodingFinished(MessageType messageType, long localSendingTimeInNanos, T encodingToken) {
        try {
            fixSessionPlugin.onMessageEncodingFinished(messageType, localSendingTimeInNanos, encodingToken);
        } catch (Exception ex) {
            log.error("Failed to call onMessageEncodingFinished() on FixMessageEventsListener {}", fixSessionPlugin.getClass().getSimpleName(), ex);
        }
    }


    @Override
    public void onMessageSent(MessageType messageType, int payloadSize, long localSendingTimeInNanos, UTCTime localSendingTime) {
        try {
            fixSessionPlugin.onMessageSent(messageType, payloadSize, localSendingTimeInNanos, localSendingTime);
        } catch (Exception ex) {
            log.error("Failed to call onMessageSent() on FixMessageEventsListener {}", fixSessionPlugin.getClass().getSimpleName(), ex);
        }
    }


    @Override
    public void onLogout() {
        try {
            fixSessionPlugin.onLogout();
        } catch (Exception ex) {
            log.error("Failed to call onLogout() on FixMessageEventsListener {}", fixSessionPlugin.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void onRttMeasurement(RttMeasurement measurement) {
        try {
            fixSessionPlugin.onRttMeasurement(measurement);
        } catch (Exception ex) {
            log.error("Failed to call onRttMeasurement() on FixMessageEventsListener {}", fixSessionPlugin.getClass().getSimpleName(), ex);
        }
    }
}
