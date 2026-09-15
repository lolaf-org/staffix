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
import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.codec.SessionRejectReasonCodes;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.codec.encoders.FixMessageEncoderImpl;

import java.util.Optional;

/**
 * The session-layer messages as FIX.4.2 defines them.
 */
@Slf4j
public class Fix42AdminMessagesCodec extends FixAbstractAdminMessagesCodec {

    private final MessageType businessMessageReject;
    private final Clock clock;

    public Fix42AdminMessagesCodec(AdminMessageCodecContext adminMessageCodecContext) {
        super(adminMessageCodecContext);
        this.businessMessageReject = getMessageTypeRegistry().find(CoreMessageType.BUSINESS_MESSAGE_REJECT);
        this.clock = adminMessageCodecContext.getClock();
        registerAdminDecoder(new BusinessMessageRejectFixMessageDecoder(businessMessageReject, adminMessageCodecContext, getFixAdminMessagesCodec()),
                adminMessageCodecContext.getMessageFieldsRegistry(), adminMessageCodecContext.getFixSessionSettings().getFixSessionId());
    }

    @Override
    public boolean isAdminMessage(MessageType messageType) {
        return super.isAdminMessage(messageType) || messageType.equals(businessMessageReject);
    }

    @Override
    public FixMessageEncoder<?> generateBusinessReject(String rejectText, int businessRejectReason, long refSeqNum, String businessRejectRefId, MessageType refMsgType) {
        BusinessMessageRejectEncoder businessMessageRejectEncoder = new BusinessMessageRejectEncoder(businessMessageReject, clock);
        businessMessageRejectEncoder.begin().addLong(getFieldsRegistry().find(45), refSeqNum);
        businessMessageRejectEncoder.addString(getFieldsRegistry().find(372), refMsgType.code());
        if (businessRejectRefId != null) {
            businessMessageRejectEncoder.addString(getFieldsRegistry().find(379), businessRejectRefId);
        }
        businessMessageRejectEncoder.addInt(getFieldsRegistry().find(380), businessRejectReason)
                .addString(getFieldsRegistry().find(58), rejectText);
        return businessMessageRejectEncoder;
    }

    @Override
    public Optional<Integer> getSessionRejectCode(int sessionRejectReasonCode) {
        if (sessionRejectReasonCode <= SessionRejectReasonCodes.INVALID_MSGTYPE.getCode()) {
            return Optional.of(sessionRejectReasonCode);
        }
        return Optional.empty();
    }

    private static class BusinessMessageRejectEncoder extends FixMessageEncoderImpl<BusinessMessageRejectEncoder> {

        public BusinessMessageRejectEncoder(MessageType messageType, Clock clock) {
            super(null, messageType, null, null, clock);
        }
    }
}