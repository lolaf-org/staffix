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
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;

/**
 * The Logon(35=A) decoder of FIX.4.3 and later, which is where TestMessageIndicator(464) appears.
 * <p>
 * A version older than that has no such field in its dictionary, and asking the mapper to decode a field the
 * registry cannot find fails the session as its codec is being built - so the mapping lives here rather than in
 * {@link LogonFixMessageDecoder}, which serves every version. The encoding side draws the same line, in
 * {@link Fix43AdminMessagesCodec}.
 */
@Slf4j
public class Fix43LogonFixMessageDecoder extends LogonFixMessageDecoder {

    public Fix43LogonFixMessageDecoder(MessageType messageType, AdminMessageCodecContext adminMessageCodecContext, FixAdminMessagesCodec fixAdminMessagesCodec) {
        super(messageType, adminMessageCodecContext, fixAdminMessagesCodec);
    }

    @Override
    public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
        fixFieldsDecoderMapper.mapBooleanObjectField(getFieldsRegistry().find(CoreFields.TEST_MESSAGE_INDICATOR),
                this::setTestMessageIndicator, null);
        super.mapFieldsForDecoding(fixFieldsDecoderMapper, fieldsRegistry);
    }
}
