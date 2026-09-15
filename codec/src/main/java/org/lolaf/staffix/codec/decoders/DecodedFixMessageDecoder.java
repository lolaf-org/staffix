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

import lombok.AccessLevel;
import lombok.Getter;
import org.lolaf.staffix.api.codec.DecodingException;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.FixFieldMap;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.serde.ByteArraySerde;

import java.util.Arrays;

/**
 * The decoder used when no typed one is registered: keeps the fields as they arrived, for an application that
 * wants the message generically.
 */
public class DecodedFixMessageDecoder implements FixMessageDecoder {

    private final DecodedFixMessageImpl decodedFixMessageImpl;
    private final FixFieldMap.GroupFixFieldMap[] currentGroupStack;
    private final MessageType messageType;
    private int currentGroupStackIndex = -1;
    @Getter(AccessLevel.PROTECTED)
    private FixFieldMap currentFixFieldMap;
    private FixFieldMap.GroupFixFieldMap currentGroup;

    public DecodedFixMessageDecoder(MessageType messageType) {
        this.messageType = messageType;
        this.decodedFixMessageImpl = new DecodedFixMessageImpl(getMessageType());
        this.currentGroupStack = new FixFieldMap.GroupFixFieldMap[FixMessageParser.MAX_INNER_GROUP];
    }

    @Override
    public MessageType getMessageType() {
        return messageType;
    }

    public DecodedFixMessage getDecodedFixMessage() {
        return decodedFixMessageImpl;
    }

    @Override
    public void onBegin(long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        decodedFixMessageImpl.clear();
        currentFixFieldMap = decodedFixMessageImpl;
    }

    @Override
    public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
        clean();
    }

    @Override
    public void onDecodingFailed(FixSession fixSession, DecodingException decodingException) {
        clean();
    }

    private void clean() {
        currentGroupStackIndex = -1;
        Arrays.fill(currentGroupStack, null);
        currentFixFieldMap = null;
    }


    @Override
    public void onField(FixField fixField, SerDe.DeserializationContext deserializationContext) {
        if (!fixField.getType().equals(FieldType.NUMINGROUP)) {
            // not optimal as ByteArraySerde will create a new byte[] instance, but call to this method is rare as DecodedFixMessageDecoder is not used a lot
            currentFixFieldMap.add(fixField, ByteArraySerde.instance().deserialize(deserializationContext));
        }
    }

    @Override
    public void onGroupStart(FixField parentGroup, FixField groupField, int numInGroup) {
        currentGroup = currentFixFieldMap.addGroup(groupField, numInGroup);
        currentGroupStack[++currentGroupStackIndex] = currentGroup;
    }

    @Override
    public void onGroupEnd(FixField parentGroup, FixField groupField) {
        if (currentGroupStackIndex > 0) {
            currentGroup = currentGroupStack[--currentGroupStackIndex];
            currentFixFieldMap = currentGroup.getParent();
        } else {
            currentGroupStackIndex = -1;
            currentFixFieldMap = decodedFixMessageImpl;
        }
    }

    @Override
    public void onGroupEntryStart(FixField parentGroup, FixField groupField, FixField fixField) {
        currentFixFieldMap = currentGroup.addEntry();
    }
}