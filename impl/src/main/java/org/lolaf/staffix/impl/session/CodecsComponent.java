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
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.*;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.codec.decoders.FixMessageDecoderImpl;
import org.lolaf.staffix.codec.decoders.VoidDecoder;
import org.lolaf.staffix.impl.FailSafeFixMessageDecoder;
import org.lolaf.staffix.impl.FixMessageEncodersPoolImpl;
import org.lolaf.staffix.impl.session.codec.FixAdminMessagesCodec;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

/**
 * Which decoder reads an arriving message and which encoder writes a leaving one: the application's decoders, the
 * session level ones the admin codec owns, and the pools an application borrows encoders from.
 *
 * <p>The replay of a message held on top of a gap belongs here too, being a parse of bytes the session already
 * holds rather than anything the retransmission decides.
 */
@Slf4j
public class CodecsComponent implements FixSessionLayerComponent {

    private final FixSessionImpl fixSession;
    private final FixSessionSettings fixSessionSettings;
    private final FixAdminMessagesCodec fixAdminMessagesCodec;
    private final FixApplication fixApplication;
    private final FieldsRegistry fieldsRegistry;
    private final MessageTypeRegistry messageTypeRegistry;
    private final FixMessageEncoderFactory fixMessageEncoderFactory;
    private final IntFunction<ByteBuffer> encodersAllocator;
    private final IntSupplier defaultEncodersPoolSize;
    private final FixSessionId fixSessionId;
    private final Clock clock;
    private final java.util.concurrent.TimeUnit sendingTimeAccuracy;
    private final PluginsComponent plugins;
    private final Map<MessageType, FixMessageDecoder> decoders;
    @Getter
    private final Set<MessageType> outgoingMessageTypes;
    private final Map<String, FixMessageEncodersPool<?>> allocatedEncodersPool;
    private Clock encodersClock = Clock.VoidClock.getInstance();
    private boolean firstMessageIsLogonOrLogoutCheck;

    CodecsComponent(FixSessionImpl fixSession, FixSessionSettings fixSessionSettings, FixAdminMessagesCodec fixAdminMessagesCodec,
                    FixApplication fixApplication, FieldsRegistry fieldsRegistry, MessageTypeRegistry messageTypeRegistry,
                    FixMessageEncoderFactory fixMessageEncoderFactory, IntFunction<ByteBuffer> encodersAllocator,
                    IntSupplier defaultEncodersPoolSize, FixSessionId fixSessionId, Clock clock,
                    java.util.concurrent.TimeUnit sendingTimeAccuracy, PluginsComponent plugins) {
        this.fixSession = fixSession;
        this.fixSessionSettings = fixSessionSettings;
        this.fixAdminMessagesCodec = fixAdminMessagesCodec;
        this.fixApplication = fixApplication;
        this.fieldsRegistry = fieldsRegistry;
        this.messageTypeRegistry = messageTypeRegistry;
        this.fixMessageEncoderFactory = fixMessageEncoderFactory;
        this.encodersAllocator = encodersAllocator;
        this.defaultEncodersPoolSize = defaultEncodersPoolSize;
        this.fixSessionId = fixSessionId;
        this.clock = clock;
        this.sendingTimeAccuracy = sendingTimeAccuracy;
        this.plugins = plugins;
        this.decoders = new IdentityHashMap<>();
        this.outgoingMessageTypes = new HashSet<>();
        this.allocatedEncodersPool = new ConcurrentHashMap<>();
    }

    /**
     * Wraps each decoder the application declared, so that a fault in one of them cannot take the session down and
     * every message is validated against this session's dictionary before it is handed over.
     */
    void setupApplicationDecoders(List<FixMessageDecoder> fixMessageDecoders) {
        for (FixMessageDecoder fixMessageDecoder : fixMessageDecoders) {
            FixMessageDecoder finalDecoder = new FailSafeFixMessageDecoder(fixMessageDecoder);
            decoders.put(fixMessageDecoder.getMessageType(), new FixMessageDecoderImpl(fieldsRegistry, finalDecoder,
                    messageTypeRegistry.getTargetDictionary(), fixSessionSettings.getValidationSettings(), fixSessionId));
        }
        if (decoders.isEmpty()) {
            log.info("FIX session {} has no incoming FixMessageDecoder: nothing but session level messages will be "
                    + "processed on it", fixSessionId);
        }
    }

    @Override
    public void onSessionStarted(FixSessionLayerComponents components) {
        encodersClock = plugins.requiresTimeMeasurement() ? clock : Clock.VoidClock.getInstance();
        if (!plugins.isEmpty()) {
            decoders.values().forEach(d -> ((FixMessageDecoderImpl) d).onPluginsSetup(plugins.get()));
            fixAdminMessagesCodec.getAdminMessageDecoders().values().forEach(d ->
                    ((FixMessageDecoderImpl) d).onPluginsSetup(plugins.get()));
        }
    }

    public Set<MessageType> getIncomingMessageTypes() {
        return decoders.keySet();
    }

    /**
     * Section 4.3: nothing but a Logon(35=A) may open a session, so the first message is checked before it is
     * decoded. A Logout(35=5) and a ResendRequest(35=2) are allowed through with it: a peer that refuses the
     * connection says so with one, and one that reconnects into a gap may ask before it is asked.
     */
    FixMessageDecoder getTargetDecoder(MessageType messageType) {
        if (!firstMessageIsLogonOrLogoutCheck) {
            if (!messageType.isAdmin()
                    || (!messageType.code().equals(CoreMessageType.LOGON)
                    && !messageType.code().equals(CoreMessageType.LOGOUT)
                    && !messageType.code().equals(CoreMessageType.RESEND_REQUEST))) {
                fixSession.logEvent("First message received is not logon or logout: %s, disconnecting", messageType.code());
                fixSession.send(fixAdminMessagesCodec.generateReject("First received message is not logon or logout",
                        SessionRejectReasonCodes.INVALID_MSGTYPE.getCode(), 1, 0, messageType), null);
                fixSession.send(fixAdminMessagesCodec.generateLogout("First received message is not logon or logout"), null);
                throw new IllegalStateException("First message received is not logon or logout: " + messageType.code() + ", disconnecting");
            }
            firstMessageIsLogonOrLogoutCheck = true;
        }

        if (fixAdminMessagesCodec.isAdminMessage(messageType)) {
            return fixAdminMessagesCodec.getDecoderForAdminMessage(messageType);
        }
        FixMessageDecoder d = decoders.get(messageType);
        if (d != null) {
            return d;
        }
        if (fixApplication.onNoDecoderSetupForMessage(fixSession, messageType)) {
            fixSession.sendBusinessMessageReject("Message type '" + messageType.code() + "' is not supported by application",
                    BusinessRejectReasonCodes.UNSUPPORTED_MESSAGE_TYPE.getCode(), null, messageType);
        }
        return VoidDecoder.getInstance(messageType);
    }

    <T extends FixMessageEncoder<?>> FixMessageEncodersPool<T> newEncodersPool(String id, boolean multiThreadedBorrows, Class<T> encoderClass) {
        return newEncodersPool(id, defaultEncodersPoolSize.getAsInt(), multiThreadedBorrows, encoderClass);
    }

    @SuppressWarnings("unchecked")
    <T extends FixMessageEncoder<?>> FixMessageEncodersPool<T> newEncodersPool(String id, int size, boolean multiThreadedBorrows, Class<T> encoderClass) {
        return (FixMessageEncodersPool<T>) allocatedEncodersPool.computeIfAbsent(id, poolId ->
                new FixMessageEncodersPoolImpl<>(p -> allocatedEncodersPool.remove(poolId),
                        multiThreadedBorrows, size, encoderClass, fixMessageEncoderFactory, plugins,
                        fixSessionSettings.isPooledMessageEncodersDirectByteBuffers(), encodersClock));
    }

    <T extends FixMessageEncoder<?>> T newEncoder(Class<T> encoderClass) {
        return fixMessageEncoderFactory.newInstance(encoderClass, null, encodersAllocator, plugins, encodersClock);
    }

    void destroyEncodersPools() {
        new ArrayList<>(allocatedEncodersPool.values()).forEach(FixMessageEncodersPool::destroy);
        allocatedEncodersPool.clear();
    }

    /**
     * Encodes a message for a session this engine does not have: a Logout(35=5) refusing a connection that named a
     * session nobody is configured for, which has no sequence numbers, no store and no state to speak of.
     */
    byte[] encodeStandaloneLogout(FixSessionId targetSessionId, String logoutText) {
        // null fixSession/fixApplication skips per-session application encoding hooks, the header is fully driven by targetSessionId
        ByteBuffer encoded = fixAdminMessagesCodec.generateLogout(logoutText)
                .encode(ByteBuffer::allocate, 1L, targetSessionId, null, sendingTimeAccuracy, clock.now(), null);
        encoded.flip();
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    /**
     * A new connection starts the message sequence again, so the first message it carries is checked afresh.
     */
    @Override
    public void onConnected() {
        firstMessageIsLogonOrLogoutCheck = false;
    }

}
