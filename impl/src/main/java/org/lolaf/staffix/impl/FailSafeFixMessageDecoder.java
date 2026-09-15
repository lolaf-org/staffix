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
package org.lolaf.staffix.impl;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.codec.DecodingException;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.codec.ValidationException;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.time.UTCTime;

/**
 * The same guard as {@link FailSafeFixApplication}, for a decoder: an exception becomes a reject for that
 * message rather than a failure of the session.
 */
@Slf4j
@Value
public class FailSafeFixMessageDecoder implements FixMessageDecoder {

    FixMessageDecoder fixMessageDecoder;

    @Override
    public MessageType getMessageType() {
        return fixMessageDecoder.getMessageType();
    }

    @Override
    public boolean ignoresIncomingSequenceNumber() {
        return fixMessageDecoder.ignoresIncomingSequenceNumber();
    }

    @Override
    public boolean managesIncomingSequenceNumber() {
        return fixMessageDecoder.managesIncomingSequenceNumber();
    }

    @Override
    public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
        fixMessageDecoder.mapFieldsForDecoding(fixFieldsDecoderMapper, fieldsRegistry);
    }

    @Override
    public void validate() throws ValidationException {
        fixMessageDecoder.validate();
    }

    @Override
    public void onField(FixField fixField, SerDe.DeserializationContext deserializationContext) {
        try {
            fixMessageDecoder.onField(fixField, deserializationContext);
        } catch (Exception ex) {
            log.error("Failed to call onField() on decoder {}", fixMessageDecoder.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void onUnknownField(FixField fixField, SerDe.DeserializationContext deserializationContext) {
        try {
            fixMessageDecoder.onUnknownField(fixField, deserializationContext);
        } catch (Exception ex) {
            log.error("Failed to call onUnknownField() on decoder {}", fixMessageDecoder.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void onBegin(long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        try {
            fixMessageDecoder.onBegin(localReceiveTimeInNanos, localReceiveTime);
        } catch (Exception ex) {
            log.error("Failed to call onBegin() on decoder {}", fixMessageDecoder.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
        try {
            fixMessageDecoder.onDecoded(fixSession, possDupFlag, possResend);
        } catch (Exception ex) {
            log.error("Failed to call onDecoded() on decoder {}", fixMessageDecoder.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void onDecodingFailed(FixSession fixSession, DecodingException decodingException) {
        try {
            fixMessageDecoder.onDecodingFailed(fixSession, decodingException);
        } catch (Exception ex) {
            log.error("Failed to call onDecodingFailed() on decoder {}", fixMessageDecoder.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void onGroupStart(FixField parentGroup, FixField groupField, int numInGroup) {
        try {
            fixMessageDecoder.onGroupStart(parentGroup, groupField, numInGroup);
        } catch (Exception ex) {
            log.error("Failed to call onGroupStart() on decoder {}", fixMessageDecoder.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void onGroupEnd(FixField parentGroup, FixField groupField) {
        try {
            fixMessageDecoder.onGroupEnd(parentGroup, groupField);
        } catch (Exception ex) {
            log.error("Failed to call onGroupEnd() on decoder {}", fixMessageDecoder.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void onGroupEntryStart(FixField parentGroup, FixField groupField, FixField fixField) {
        try {
            fixMessageDecoder.onGroupEntryStart(parentGroup, groupField, fixField);
        } catch (Exception ex) {
            log.error("Failed to call onGroupEntryStart() on decoder {}", fixMessageDecoder.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void onGroupEntryEnd(FixField parentGroup, FixField groupField, FixField fixField) {
        try {
            fixMessageDecoder.onGroupEntryEnd(parentGroup, groupField, fixField);
        } catch (Exception ex) {
            log.error("Failed to call onGroupEntryEnd() on decoder {}", fixMessageDecoder.getClass().getSimpleName(), ex);
        }
    }
}
