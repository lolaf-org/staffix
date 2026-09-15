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
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.RequiredFieldNotFoundException;
import org.lolaf.staffix.api.codec.ValidationException;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.time.UTCTime;

/**
 * Decodes a TestRequest(35=1): the peer asking for proof the session is alive, answered with a
 * Heartbeat echoing its TestReqID(112).
 */
public class TestRequestFixMessageDecoder extends AbstractAdminFixMessageDecoder {

    @Setter
    private String testRequestId;
    @Setter
    private UTCTime sendingTime;

    public TestRequestFixMessageDecoder(MessageType messageType, AdminMessageCodecContext adminMessageCodecContext, FixAdminMessagesCodec fixAdminMessagesCodec) {
        super(messageType, adminMessageCodecContext, fixAdminMessagesCodec);
    }

    @Override
    public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
        fixFieldsDecoderMapper.mapStringField(getFieldsRegistry().find(CoreFields.TEST_REQUEST_ID), this::setTestRequestId, null)
                .mapUtcDateTimeField(getFieldsRegistry().find(CoreFields.SENDING_TIME), this::setSendingTime, null);
        super.mapFieldsForDecoding(fixFieldsDecoderMapper, fieldsRegistry);
    }

    @Override
    public void validate() throws ValidationException {
        if (testRequestId == null) {
            throw new RequiredFieldNotFoundException(getFieldsRegistry().find(CoreFields.TEST_REQUEST_ID));
        }
        super.validate();
    }


    @Override
    public void onDecodedLocal(FixSession fixSession, boolean possDupFlag, boolean possResend) {
        getFixApplication().onTestRequest(getFixSession(), testRequestId, sendingTime);
        getFixSession().send(getFixAdminMessagesCodec().generateHeartbeat(testRequestId), null);
    }
}