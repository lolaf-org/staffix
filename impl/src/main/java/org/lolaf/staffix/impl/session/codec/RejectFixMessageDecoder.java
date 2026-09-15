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

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;

/**
 * Decodes a Reject(35=3): the peer refusing one of our messages at the session level.
 *
 * <p>Worth logging rather than swallowing - a Reject means the counterparty could not accept something we
 * consider valid, which is usually a configuration disagreement.
 */
@Setter
@Slf4j
public class RejectFixMessageDecoder extends AbstractAdminFixMessageDecoder {
    private String rejectText;
    private String refMsgType;
    private int rejectReason;
    private int refTagId;
    private long refSeqNum;

    public RejectFixMessageDecoder(MessageType messageType, AdminMessageCodecContext adminMessageCodecContext, FixAdminMessagesCodec fixAdminMessagesCodec) {
        super(messageType, adminMessageCodecContext, fixAdminMessagesCodec);
    }

    @Override
    public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
        fixFieldsDecoderMapper.mapStringField(getFieldsRegistry().find(372), this::setRefMsgType, null)
                .mapStringField(getFieldsRegistry().find(CoreFields.TEXT), this::setRejectText, null)
                .mapIntField(getFieldsRegistry().find(371), this::setRefTagId, 0)
                .mapIntField(getFieldsRegistry().find(373), this::setRejectReason, 0)
                .mapLongField(getFieldsRegistry().find(45), this::setRefSeqNum, 0);
        super.mapFieldsForDecoding(fixFieldsDecoderMapper, fieldsRegistry);
    }

    @Override
    public void onDecodedLocal(FixSession fixSession, boolean possDupFlag, boolean possResend) {
        getFixApplication().onMessageReject(getFixSession(), rejectText, rejectReason, refSeqNum, refTagId, refMsgType);
    }
}