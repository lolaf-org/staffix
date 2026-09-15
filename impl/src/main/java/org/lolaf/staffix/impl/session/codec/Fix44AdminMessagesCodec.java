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
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixApiVersion;

import java.util.Optional;
import java.util.Set;

/**
 * The session-layer messages as FIX.4.4 defines them.
 */
@Slf4j
public class Fix44AdminMessagesCodec extends Fix43AdminMessagesCodec {

    private final FixField nextExpectedMsgSeqNumField;

    public Fix44AdminMessagesCodec(AdminMessageCodecContext adminMessageCodecContext) {
        super(adminMessageCodecContext);
        this.nextExpectedMsgSeqNumField = getFieldsRegistry().find(CoreFields.NEXT_EXPECTED_MSG_SEQ_NUM);
        registerAdminDecoder(new Fix44LogonFixMessageDecoder(getMessageTypeRegistry().find(CoreMessageType.LOGON), adminMessageCodecContext, getFixAdminMessagesCodec()),
                adminMessageCodecContext.getMessageFieldsRegistry(), adminMessageCodecContext.getFixSessionSettings().getFixSessionId());
    }

    /**
     * Adds NextExpectedMsgSeqNum(789) to the Logon when the session is configured for it, which is the version this
     * field appeared in - and the version whose {@link Fix44LogonFixMessageDecoder} reads it back.
     */
    @Override
    public FixMessageEncoder<?> generateLogin(int heartbeatInterval, Boolean resetNumFlag, FixSessionSettings fixSessionSettings,
                                              FixApiVersion applicationFixApiVersion, Long nextExpectedSeqNum,
                                              Set<MessageType> incomingMessageTypes, Set<MessageType> outgoingMessageTypes) {
        FixMessageEncoder<?> logonEncoder = super.generateLogin(heartbeatInterval, resetNumFlag, fixSessionSettings,
                applicationFixApiVersion, nextExpectedSeqNum, incomingMessageTypes, outgoingMessageTypes);
        if (fixSessionSettings.isEnabledLogonNextExpectedMsgSeqNum() && nextExpectedSeqNum != null) {
            logonEncoder.addLong(nextExpectedMsgSeqNumField, nextExpectedSeqNum);
        }
        return logonEncoder;
    }

    @Override
    public Optional<Integer> getSessionRejectCode(int sessionRejectReasonCode) {
        if (sessionRejectReasonCode == SessionRejectReasonCodes.OTHER.getCode()
                || sessionRejectReasonCode <= SessionRejectReasonCodes.NON_DATA_VALUE_INCLUDES_FIELD_DELIMITER.getCode()) {
            return Optional.of(sessionRejectReasonCode);
        }
        return Optional.empty();
    }
}