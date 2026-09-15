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

import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.serde.SerDe;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The decoder swapped in when a message is being skipped - one that has already failed, or one no application
 * asked for.
 *
 * <p>Doing nothing per field costs less than testing whether to do something per field, which is why this
 * exists rather than a null check on the parsing loop. One instance per message type, shared.
 */
public class VoidDecoder implements FixMessageDecoder {

    private static final Map<MessageType, VoidDecoder> INSTANCES = new ConcurrentHashMap<>();
    private final MessageType messageType;

    private VoidDecoder(MessageType messageType) {
        this.messageType = messageType;
    }

    public static VoidDecoder getInstance(MessageType messageType) {
        return INSTANCES.computeIfAbsent(messageType, VoidDecoder::new);
    }

    @Override
    public void onField(FixField fixField, SerDe.DeserializationContext deserializationContext) {
        // nothing to do
    }

    @Override
    public MessageType getMessageType() {
        return messageType;
    }

    @Override
    public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
        // nothing to do
    }
}