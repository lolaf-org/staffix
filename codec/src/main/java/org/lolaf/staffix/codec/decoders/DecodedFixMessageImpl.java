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

import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;

import java.util.Map;

/**
 * A parsed message held as its fields.
 *
 * <p>Reused for the next message, so it is valid only within the callback it is handed to - see
 * {@link org.lolaf.staffix.api.msg.DecodedFixMessage#copy()}.
 */
public class DecodedFixMessageImpl extends FieldMapImpl implements DecodedFixMessage {

    private final MessageType messageType;

    public DecodedFixMessageImpl(MessageType messageType) {
        this.messageType = messageType;
    }

    public DecodedFixMessageImpl(MessageType messageType, Map<FixField, Object> fieldsValues) {
        super(fieldsValues);
        this.messageType = messageType;
    }

    @Override
    public MessageType getMessageType() {
        return messageType;
    }

    @Override
    public String toString() {
        StringBuilder toString = new StringBuilder(256);
        foreach((field, value) ->
                toString.append(field.getCode()).append("=")
                        .append(new String(value, SerDe.CHARSET)).append(CoreFields.FIELD_SEPARATOR));
        return toString.toString();
    }

    @Override
    public DecodedFixMessage copy() {
        return new DecodedFixMessageImpl(this.getMessageType(), this.getFieldsValues());
    }
}