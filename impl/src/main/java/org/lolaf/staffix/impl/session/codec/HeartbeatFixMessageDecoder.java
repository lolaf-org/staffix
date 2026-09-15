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
 * Decodes a Heartbeat(35=0), which proves the connection is alive and, when it carries a
 * TestReqID(112), answers a TestRequest.
 */
@Setter
public class HeartbeatFixMessageDecoder extends AbstractAdminFixMessageDecoder {

    private UTCTime sendingTime;
    private String testRequestId;
    private long recvMonotonicNanos;
    private long recvWallTimeNanos;

    public HeartbeatFixMessageDecoder(MessageType messageType, AdminMessageCodecContext adminMessageCodecContext, FixAdminMessagesCodec fixAdminMessagesCodec) {
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
        if (sendingTime == null) {
            throw new RequiredFieldNotFoundException(getFieldsRegistry().find(CoreFields.SENDING_TIME));
        }
        super.validate();
    }

    @Override
    public void onBegin(long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        recvMonotonicNanos = localReceiveTimeInNanos == 0 ? System.nanoTime() : localReceiveTimeInNanos;
        recvWallTimeNanos = localReceiveTime.toEpochNanos();
        super.onBegin(localReceiveTimeInNanos, localReceiveTime);
    }

    @Override
    public void onDecodedLocal(FixSession fixSession, boolean possDupFlag, boolean possResend) {
        if (testRequestId == null) {
            getFixApplication().onHeartbeat(getFixSession(), sendingTime);
        } else {
            getFixSession().onTestRequestResponseReceived(testRequestId, sendingTime, recvMonotonicNanos, recvWallTimeNanos);
        }
    }
}