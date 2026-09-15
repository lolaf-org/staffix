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

/**
 * The Logon(35=A) decoder of FIX.4.4 and later, adding NextExpectedMsgSeqNum(789) to what
 * {@link Fix43LogonFixMessageDecoder} already maps.
 */
@Slf4j
@Setter
public class Fix44LogonFixMessageDecoder extends Fix43LogonFixMessageDecoder {

    private long nextExpectedMsgSeqNum = Long.MIN_VALUE;

    public Fix44LogonFixMessageDecoder(MessageType messageType, AdminMessageCodecContext adminMessageCodecContext, FixAdminMessagesCodec fixAdminMessagesCodec) {
        super(messageType, adminMessageCodecContext, fixAdminMessagesCodec);
    }

    @Override
    public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
        fixFieldsDecoderMapper.mapLongField(getFieldsRegistry().find(CoreFields.NEXT_EXPECTED_MSG_SEQ_NUM), this::setNextExpectedMsgSeqNum, Long.MIN_VALUE);
        super.mapFieldsForDecoding(fixFieldsDecoderMapper, fieldsRegistry);
    }

    @Override
    public Long getNextExpectedMsgSeqNum() {
        return nextExpectedMsgSeqNum == Long.MIN_VALUE ? null : nextExpectedMsgSeqNum;
    }
}