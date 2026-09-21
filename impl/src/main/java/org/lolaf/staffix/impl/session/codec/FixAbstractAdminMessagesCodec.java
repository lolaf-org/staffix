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
package org.lolaf.staffix.impl.session.codec;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.codec.FixMessageEncodingListener;
import org.lolaf.staffix.api.fields.*;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.msg.MessageFieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.*;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.codec.decoders.FixMessageDecoderImpl;
import org.lolaf.staffix.codec.encoders.FixMessageEncoderImpl;
import org.lolaf.staffix.impl.FixEngineVersion;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * What every version's admin codec shares, leaving each subclass only the fields its version actually has.
 */
@Slf4j
public abstract class FixAbstractAdminMessagesCodec implements FixAdminMessagesCodec {

    @Getter
    private final FieldsRegistry fieldsRegistry;
    @Getter
    private final MessageTypeRegistry messageTypeRegistry;
    @Getter
    private final Map<MessageType, FixMessageDecoder> adminMessageDecoders;
    @Getter
    private final FixAdminMessagesCodec fixAdminMessagesCodec;
    private final boolean acceptorSession;
    private final FixMessageEncodingListener encodingListener;
    private final Clock clock;
    private final FixSessionSettings.ValidationSettings adminDecodersValidationSettings;
    private final ResendRequestRange resendRequestRange;
    /**
     * The precision SendingTime(52) is written with, which OrigSendingTime(122) on a gap fill follows so that the two
     * are comparable at the peer.
     */
    private final TimeUnit sendingTimeAccuracy;

    protected FixAbstractAdminMessagesCodec(AdminMessageCodecContext adminMessageCodecContext) {
        this.adminDecodersValidationSettings = adminMessageCodecContext.getFixSessionSettings().getValidationSettings()
                .toBuilder().validateRequiredFields(true).build();
        this.resendRequestRange = adminMessageCodecContext.getFixSessionSettings().getResendRequestRange();
        this.sendingTimeAccuracy = adminMessageCodecContext.getFixSessionSettings().getSendingTimeAccuracy();
        this.fieldsRegistry = adminMessageCodecContext.getFieldsRegistry();
        this.acceptorSession = adminMessageCodecContext.getFixSessionSettings().getFixSessionType().equals(FixSession.FixSessionType.ACCEPTOR);
        this.messageTypeRegistry = adminMessageCodecContext.getMessageTypeRegistry();
        this.adminMessageDecoders = new IdentityHashMap<>();
        this.encodingListener = adminMessageCodecContext.getEncodingListener();
        this.clock = adminMessageCodecContext.getClock();
        // for FIXT support
        this.fixAdminMessagesCodec = adminMessageCodecContext.getFixAdminMessagesCodec() != null ? adminMessageCodecContext.getFixAdminMessagesCodec() : this;
        MessageFieldsRegistry messageFieldsRegistry = adminMessageCodecContext.getMessageFieldsRegistry();
        registerAdminDecoder(new LogonFixMessageDecoder(messageTypeRegistry.find(CoreMessageType.LOGON), adminMessageCodecContext, fixAdminMessagesCodec),
                messageFieldsRegistry, adminMessageCodecContext.getFixSessionSettings().getFixSessionId());
        registerAdminDecoder(new LogoutFixMessageDecoder(messageTypeRegistry.find(CoreMessageType.LOGOUT), adminMessageCodecContext, fixAdminMessagesCodec),
                messageFieldsRegistry, adminMessageCodecContext.getFixSessionSettings().getFixSessionId());
        registerAdminDecoder(new HeartbeatFixMessageDecoder(messageTypeRegistry.find(CoreMessageType.HEARTBEAT), adminMessageCodecContext, fixAdminMessagesCodec),
                messageFieldsRegistry, adminMessageCodecContext.getFixSessionSettings().getFixSessionId());
        registerAdminDecoder(new TestRequestFixMessageDecoder(messageTypeRegistry.find(CoreMessageType.TEST_REQUEST), adminMessageCodecContext, fixAdminMessagesCodec),
                messageFieldsRegistry, adminMessageCodecContext.getFixSessionSettings().getFixSessionId());
        registerAdminDecoder(new RejectFixMessageDecoder(messageTypeRegistry.find(CoreMessageType.REJECT), adminMessageCodecContext, fixAdminMessagesCodec),
                messageFieldsRegistry, adminMessageCodecContext.getFixSessionSettings().getFixSessionId());
        registerAdminDecoder(new ResendRequestFixMessageDecoder(messageTypeRegistry.find(CoreMessageType.RESEND_REQUEST), adminMessageCodecContext, fixAdminMessagesCodec),
                messageFieldsRegistry, adminMessageCodecContext.getFixSessionSettings().getFixSessionId());
        registerAdminDecoder(new SequenceResetFixMessageDecoder(messageTypeRegistry.find(CoreMessageType.SEQUENCE_REQUEST), adminMessageCodecContext, fixAdminMessagesCodec),
                messageFieldsRegistry, adminMessageCodecContext.getFixSessionSettings().getFixSessionId());
    }

    public void registerAdminDecoder(FixMessageDecoder decoder, MessageFieldsRegistry messageFieldsRegistry, FixSessionId fixSessionId) {
        this.adminMessageDecoders.put(decoder.getMessageType(), new FixMessageDecoderImpl(fieldsRegistry, decoder, messageFieldsRegistry,
                adminDecodersValidationSettings, fixSessionId));
    }

    @Override
    public FixMessageEncoder<?> generateResendRequest(long fromSeqNum, long toSeqNum) {
        return new ResendRequestEncoder(messageTypeRegistry.find(CoreMessageType.RESEND_REQUEST), encodingListener, clock)
                .begin()
                .addLong(fieldsRegistry.find(CoreFields.BEGIN_SEQ_NO), fromSeqNum)
                .addLong(fieldsRegistry.find(CoreFields.END_SEQ_NO), resendRequestRange.endSeqNo(toSeqNum));
    }

    @Override
    public FixMessageEncoder<?> generateReject(String rejectText, int sessionRejectReasonCode, long refSeqNum, int refTagId, MessageType refMsgType) {
        RejectEncoder rejectEncoder = new RejectEncoder(messageTypeRegistry.find(CoreMessageType.REJECT), encodingListener, clock);
        rejectEncoder.begin().addLong(fieldsRegistry.find(45), refSeqNum);
        if (refTagId > 0) {
            rejectEncoder.addLong(fieldsRegistry.find(371), refTagId);
        }
        if (refMsgType != null) {
            rejectEncoder.addString(fieldsRegistry.find(372), refMsgType.code());
        }

        getSessionRejectCode(sessionRejectReasonCode).ifPresent(c -> rejectEncoder.addInt(fieldsRegistry.find(373), c));
        if (rejectText != null) {
            rejectEncoder.addString(fieldsRegistry.find(CoreFields.TEXT), rejectText);
        }
        return rejectEncoder;
    }


    public abstract Optional<Integer> getSessionRejectCode(int sessionRejectReasonCode);

    @Override
    public FixMessageEncoder<?> generateLogin(int heartbeatInterval, Boolean resetNumFlag, FixSessionSettings fixSessionSettings, FixApiVersion applicationFixApiVersion, Long nextExpectedSeqNum,
                                              Set<MessageType> incomingMessageTypes, Set<MessageType> outgoingMessageTypes) {
        LogonEncoder logonEncoder = new LogonEncoder(messageTypeRegistry.find(CoreMessageType.LOGON), encodingListener, clock);
        logonEncoder.begin()
                .addInt(fieldsRegistry.find(98), 0) // unencrypted
                .addInt(fieldsRegistry.find(CoreFields.HEARTBEAT_INTERVAL), heartbeatInterval);
        if (resetNumFlag != null) {
            logonEncoder.addBoolean(fieldsRegistry.find(CoreFields.RESET_NUM_FLAG), resetNumFlag);
        }
        Integer maxMessageSize = fixSessionSettings.getValidationSettings().getMaxMessageSize();
        if (maxMessageSize != null) {
            logonEncoder.addInt(fieldsRegistry.find(CoreFields.MAX_MESSAGE_SIZE), maxMessageSize);
        }
        if (fixSessionSettings.isAdvertiseEngineOnLogon()) {
            FixApiVersion fixEngineVersion = FixEngineVersion.getInstance();
            logonEncoder.addString(getOrCreateStringField(1600), fixEngineVersion.getName());
            logonEncoder.addString(getOrCreateStringField(1601), fixEngineVersion.getVersion().toString());
            logonEncoder.addString(getOrCreateStringField(1602), fixEngineVersion.getVendor());
        }
        if (fixSessionSettings.isAdvertiseApplicationOnLogon()) {
            logonEncoder.addString(getOrCreateStringField(1603), applicationFixApiVersion.getName());
            logonEncoder.addString(getOrCreateStringField(1604), applicationFixApiVersion.getVersion().toString());
            if (applicationFixApiVersion.getVendor() != null) {
                logonEncoder.addString(getOrCreateStringField(1605), applicationFixApiVersion.getVendor());
            }
        }

        if (fixSessionSettings.isAdvertiseMsgTypeGrpOnLogon()) {
            FixField refMsgType = fieldsRegistry.find(372);
            FixField msgDirection = fieldsRegistry.find(385);
            logonEncoder.addInt(fieldsRegistry.find(384), incomingMessageTypes.size() + outgoingMessageTypes.size());
            incomingMessageTypes.forEach(mt ->
                    logonEncoder.addString(refMsgType, mt.code()).addString(msgDirection, "R"));
            outgoingMessageTypes.forEach(mt ->
                    logonEncoder.addString(refMsgType, mt.code()).addString(msgDirection, "S"));
        }

        if (!acceptorSession && fixSessionSettings.getCancelOnDisconnectSettings().isEnabled()) {
            CancelOnDisconnectType codType = fixSessionSettings.getCancelOnDisconnectSettings().getCancelOnDisconnectType();
            logonEncoder.addChar(fieldsRegistry.find(fixSessionSettings.getCancelOnDisconnectSettings().getCancelOnDisconnectTypeFieldCode()),
                    fixSessionSettings.getCancelOnDisconnectSettings().getCancelOnDisconnectTypeFieldCodes().get(codType));
            Duration codTimeoutWindow = fixSessionSettings.getCancelOnDisconnectSettings().getCodTimeoutWindow();
            TimeUnit codTimeoutWindowScale = fixSessionSettings.getCancelOnDisconnectSettings().getCodTimeoutWindowScale();
            logonEncoder.addInt(fieldsRegistry.find(fixSessionSettings.getCancelOnDisconnectSettings().getCodTimeoutWindowFieldCode()),
                    (int) codTimeoutWindowScale.convert(codTimeoutWindow));
        }
        return logonEncoder;
    }

    private FixField getOrCreateStringField(int code) {
        FixField field = fieldsRegistry.find(code);
        if (field == null) {
            field = FixField.of(code, FieldType.STRING, FieldLocation.BODY);
        }
        return field;
    }

    @Override
    public FixMessageEncoder<?> generateLogout(String logoutMessage) {
        LogoutEncoder logoutEncoder = new LogoutEncoder(messageTypeRegistry.find(CoreMessageType.LOGOUT), encodingListener, clock);
        logoutEncoder.begin();
        if (logoutMessage != null && !logoutMessage.isEmpty()) {
            logoutEncoder.addString(fieldsRegistry.find(CoreFields.TEXT), logoutMessage);
        }
        return logoutEncoder;
    }

    @Override
    public FixMessageEncoder<?> generateSequenceReset(long newSequenceNumber, boolean gapFill) {
        SequenceResetEncoder sequenceResetEncoder =
                new SequenceResetEncoder(messageTypeRegistry.find(CoreMessageType.SEQUENCE_REQUEST), encodingListener, clock).begin();
        if (gapFill) {
            sequenceResetEncoder
                    .addBoolean(fieldsRegistry.find(CoreFields.POSS_DUP_FLAG), true)
                    .addUtcDateTime(fieldsRegistry.find(CoreFields.ORIG_SENDING_TIME), clock.now(), sendingTimeAccuracy);
        }
        return sequenceResetEncoder
                .addBoolean(fieldsRegistry.find(CoreFields.GAP_FILL), gapFill)
                .addLong(fieldsRegistry.find(CoreFields.NEW_SEQ_NO), newSequenceNumber);
    }

    @Override
    public FixMessageEncoder<?> generateHeartbeat(String testRequestId) {
        HeartbeatEncoder hb = new HeartbeatEncoder(messageTypeRegistry.find(CoreMessageType.HEARTBEAT), encodingListener, clock).begin();
        if (testRequestId != null) {
            hb.addString(fieldsRegistry.find(CoreFields.TEST_REQUEST_ID), testRequestId);
        }
        return hb;
    }

    @Override
    public FixMessageEncoder<?> generateTestRequest(String testRequestId) {
        return new TestRequestEncoder(messageTypeRegistry.find(CoreMessageType.TEST_REQUEST), encodingListener, clock)
                .begin().addString(fieldsRegistry.find(CoreFields.TEST_REQUEST_ID), testRequestId);
    }

    @Override
    public FixMessageDecoder getDecoderForAdminMessage(MessageType messageType) {
        FixMessageDecoder decoder = adminMessageDecoders.get(messageType);
        if (decoder == null) {
            log.error("Received unmanaged admin message {}", messageType);
            throw new IllegalStateException("Unsupported admin message " + messageType);
        }
        return decoder;
    }

    @Override
    public boolean isAdminMessage(MessageType messageType) {
        return messageType.isAdmin();
    }

    private static class LogonEncoder extends FixMessageEncoderImpl<LogonEncoder> {

        public LogonEncoder(MessageType messageType, FixMessageEncodingListener encodingListener, Clock clock) {
            super(null, messageType, ByteBuffer::allocate, encodingListener, clock);
        }
    }

    private static class LogoutEncoder extends FixMessageEncoderImpl<LogoutEncoder> {

        public LogoutEncoder(MessageType messageType, FixMessageEncodingListener encodingListener, Clock clock) {
            super(null, messageType, ByteBuffer::allocate, encodingListener, clock);
        }
    }

    private static class SequenceResetEncoder extends FixMessageEncoderImpl<SequenceResetEncoder> {

        public SequenceResetEncoder(MessageType messageType, FixMessageEncodingListener encodingListener, Clock clock) {
            super(null, messageType, ByteBuffer::allocate, encodingListener, clock);
        }
    }

    private static class ResendRequestEncoder extends FixMessageEncoderImpl<ResendRequestEncoder> {

        public ResendRequestEncoder(MessageType messageType, FixMessageEncodingListener encodingListener, Clock clock) {
            super(null, messageType, ByteBuffer::allocate, encodingListener, clock);
        }
    }

    private static class TestRequestEncoder extends FixMessageEncoderImpl<TestRequestEncoder> {

        public TestRequestEncoder(MessageType messageType, FixMessageEncodingListener encodingListener, Clock clock) {
            super(null, messageType, ByteBuffer::allocate, encodingListener, clock);
        }
    }

    private static class HeartbeatEncoder extends FixMessageEncoderImpl<HeartbeatEncoder> {

        public HeartbeatEncoder(MessageType messageType, FixMessageEncodingListener encodingListener, Clock clock) {
            super(null, messageType, ByteBuffer::allocate, encodingListener, clock);
        }
    }

    private static class RejectEncoder extends FixMessageEncoderImpl<RejectEncoder> {

        public RejectEncoder(MessageType messageType, FixMessageEncodingListener encodingListener, Clock clock) {
            super(null, messageType, ByteBuffer::allocate, encodingListener, clock);
        }
    }
}