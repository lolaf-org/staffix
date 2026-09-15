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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.ApplVerID;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixApplVerID;

import java.util.Map;
import java.util.Set;

/**
 * The session-layer messages as FIXT.1.1 defines them, used by every FIX.5.0 and later session.
 *
 * <p>From FIX.5.0 the session layer is versioned apart from the application layer, so one codec serves any
 * application dictionary a FIXT session carries.
 */
@Slf4j
public class FixTAdminMessagesCodec implements FixAdminMessagesCodec {

    private final ApplVerID defaultAppVerID;
    private final FixAdminMessagesCodec adminMessagesHandler;
    private final FieldsRegistry fieldsRegistry;

    public FixTAdminMessagesCodec(AdminMessageCodecContext adminMessageCodecContext, FixSessionId fixSessionId) {
        this.defaultAppVerID = fixSessionId.getDefaultApplVerID();
        this.fieldsRegistry = adminMessageCodecContext.getFieldsRegistry();
        adminMessageCodecContext = adminMessageCodecContext.toBuilder().fixAdminMessagesCodec(this).build();
        FixApplVerID verId = FixApplVerID.forCode(fixSessionId.getDefaultApplVerID().getCode()).orElseThrow();
        switch (verId) {
            case FIX42:
                adminMessagesHandler = new Fix42AdminMessagesCodec(adminMessageCodecContext);
                break;
            case FIX43:
                adminMessagesHandler = new Fix43AdminMessagesCodec(adminMessageCodecContext);
                break;
            case FIX44:
                adminMessagesHandler = new Fix44AdminMessagesCodec(adminMessageCodecContext);
                break;
            case FIX50:
            case FIX50SP1:
            case FIX50SP2:
                adminMessagesHandler = new Fix50AdminMessagesCodec(adminMessageCodecContext);
                break;
            case FIX_LATEST:
                adminMessagesHandler = new FixLatestAdminMessagesCodec(adminMessageCodecContext);
                break;
            default:
                throw new IllegalStateException("Should have never happened");
        }
    }

    @Override
    public Map<MessageType, FixMessageDecoder> getAdminMessageDecoders() {
        return adminMessagesHandler.getAdminMessageDecoders();
    }

    @Override
    public FixMessageEncoder<?> generateLogin(int heartbeatInterval, Boolean resetNumFlag, FixSessionSettings fixSessionSettings,
                                              FixApiVersion applicationFixApiVersion, Long nextExpectedSeqNum,
                                              Set<MessageType> incomingMessageTypes, Set<MessageType> outgoingMessageTypes) {
        FixMessageEncoder<?> logonEncoder = adminMessagesHandler.generateLogin(heartbeatInterval, resetNumFlag, fixSessionSettings, applicationFixApiVersion,
                nextExpectedSeqNum, incomingMessageTypes, outgoingMessageTypes);
        logonEncoder.addString(fieldsRegistry.find(CoreFields.DEFAULT_APPL_VER_ID), defaultAppVerID.getCode());
        return logonEncoder;
    }

    @Override
    public FixMessageEncoder<?> generateLogout(String logoutMessage) {
        return adminMessagesHandler.generateLogout(logoutMessage);
    }

    @Override
    public FixMessageEncoder<?> generateHeartbeat(String testRequestId) {
        return adminMessagesHandler.generateHeartbeat(testRequestId);
    }

    @Override
    public FixMessageEncoder<?> generateBusinessReject(String rejectText, int businessRejectReason, long refSeqNum, String businessRejectRefId, MessageType refMsgType) {
        return adminMessagesHandler.generateBusinessReject(rejectText, businessRejectReason, refSeqNum, businessRejectRefId, refMsgType);
    }

    @Override
    public FixMessageEncoder<?> generateReject(String rejectText, int sessionRejectReasonCode, long refSeqNum, int refTagId, MessageType refMsgType) {
        return adminMessagesHandler.generateReject(rejectText, sessionRejectReasonCode, refSeqNum, refTagId, refMsgType);
    }

    @Override
    public FixMessageEncoder<?> generateSequenceReset(long newSequenceNumber, boolean gapFill) {
        return adminMessagesHandler.generateSequenceReset(newSequenceNumber, gapFill);
    }

    @Override
    public FixMessageEncoder<?> generateTestRequest(String testRequestId) {
        return adminMessagesHandler.generateTestRequest(testRequestId);
    }

    @Override
    public FixMessageEncoder<?> generateResendRequest(long fromSeqNum, long toSeqNum) {
        return adminMessagesHandler.generateResendRequest(fromSeqNum, toSeqNum);
    }

    @Override
    public FixMessageDecoder getDecoderForAdminMessage(MessageType messageType) {
        return adminMessagesHandler.getDecoderForAdminMessage(messageType);
    }

    @Override
    public boolean isAdminMessage(MessageType messageType) {
        return adminMessagesHandler.isAdminMessage(messageType);
    }
}