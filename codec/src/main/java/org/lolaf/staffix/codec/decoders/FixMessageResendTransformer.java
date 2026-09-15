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
package org.lolaf.staffix.codec.decoders;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.codec.DecodingException;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.logging.VoidMessageLogger;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.serde.BooleanSerde;
import org.lolaf.staffix.serde.UtcDateTimeSerde;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * Rewrites a stored message for retransmission: sets PossDupFlag(43), fills OrigSendingTime(122) and restamps
 * SendingTime(52).
 *
 * <p>Re-parses with validation disabled, deliberately. The message was validated when it was first sent and is
 * being replayed verbatim; re-checking it would only be able to reject what the session already accepted.
 */
@Slf4j
public class FixMessageResendTransformer {

    private static final byte[] TRUE = BooleanSerde.TRUE.getBytes(SerDe.CHARSET);
    /**
     * The messages this transformer reads back are ones this engine encoded and stored itself, on their way to being
     * retransmitted. They were built valid and were never on the wire in between, so re-running the session's
     * validation over them would only spend cycles rejecting nothing.
     */
    private static final FixSessionSettings.ValidationSettings DISABLED_VALIDATION = FixSessionSettings.ValidationSettings
            .builder()
            .validateChecksum(false)
            .validateFieldsHaveValues(false)
            .validateCompId(false)
            .validateBeginString(false)
            .detectGarbledMessages(false)
            .maxSendingTime(null)
            .maxMessageSize(null)
            .build();

    private final FixField origSendingTime;
    private final FixField messageType;
    private final FixField possDupFlag;
    private final FixField sendingTime;
    private final FixField messageSeqNum;
    private final FixMessageParser parser;
    private final TimeUnit sendingTimeAccuracy;
    private DecodedFixMessageDecoder decodedFixMessageDecoder;

    public FixMessageResendTransformer(TimeUnit sendingTimeAccuracy, Clock clock, FixSessionId fixSessionId,
                                       MessageTypeRegistry messageTypeRegistry, FieldsRegistry fieldsRegistry) {
        origSendingTime = fieldsRegistry.find(CoreFields.ORIG_SENDING_TIME);
        messageType = fieldsRegistry.find(CoreFields.MESSAGE_TYPE);
        possDupFlag = fieldsRegistry.find(CoreFields.POSS_DUP_FLAG);
        sendingTime = fieldsRegistry.find(CoreFields.SENDING_TIME);
        messageSeqNum = fieldsRegistry.find(CoreFields.MESSAGE_SEQ_NUM);
        this.sendingTimeAccuracy = sendingTimeAccuracy;
        parser = new FixMessageParser(fixSessionId, messageTypeRegistry, fieldsRegistry,
                VoidMessageLogger.getInstance(), DISABLED_VALIDATION, clock,
                FixMessageParserEventsListener.VoidFixMessageParserEventsListener.getInstance());
    }

    private DecodedFixMessageDecoder getDecoder(MessageType mt) {
        decodedFixMessageDecoder = new DecodedFixMessageDecoder(mt) {
            @Override
            public void onField(FixField fixField, SerDe.DeserializationContext deserializationContext) {
                if (fixField.getLocation().equals(FieldLocation.BODY)
                        || fixField.getCode() == sendingTime.getCode()
                        || fixField.getCode() == messageSeqNum.getCode()
                        || fixField.getCode() == messageType.getCode()) {
                    if (fixField.equals(sendingTime)) {
                        UTCTime sendingTimeValue = UtcDateTimeSerde.deserialize(deserializationContext);
                        getCurrentFixFieldMap().add(origSendingTime, UtcDateTimeSerde.serializeTime(sendingTimeValue, sendingTimeAccuracy));
                        getCurrentFixFieldMap().add(possDupFlag, TRUE);
                    } else {
                        super.onField(fixField, deserializationContext);
                    }
                }
            }
        };
        return decodedFixMessageDecoder;
    }

    public DecodedFixMessage transformForResend(ByteBuffer message) {
        decodedFixMessageDecoder = null;
        try {
            parser.parseMessages(message, this::getDecoder);
        } catch (DecodingException e) {
            throw new IllegalStateException("Unable to parse message for resend, should have never happened", e);
        }
        return decodedFixMessageDecoder.getDecodedFixMessage();
    }
}