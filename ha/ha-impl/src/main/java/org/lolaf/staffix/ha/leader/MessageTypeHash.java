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
package org.lolaf.staffix.ha.leader;

import lombok.experimental.UtilityClass;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.serde.Hashing;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;

/**
 * The hash of a stored message's MsgType(35), read from its bytes since a store keeps no type. The third field of
 * every FIX message, so the scan stops within its first few bytes.
 */
@UtilityClass
class MessageTypeHash {

    private static final byte EQUALS = '=';
    private static final byte THREE = '3';
    private static final byte FIVE = '5';

    /**
     * @return {@link WireFormat#NO_MESSAGE_TYPE} for bytes holding no MsgType(35)
     */
    static int of(ByteBuffer message, int start, int length) {
        int end = start + length;
        for (int i = start; i + 3 < end; i++) {
            if (message.get(i) == CoreFields.FIELD_SEPARATOR_BYTE
                    && message.get(i + 1) == THREE && message.get(i + 2) == FIVE && message.get(i + 3) == EQUALS) {
                int valueStart = i + 4;
                int valueEnd = valueStart;
                while (valueEnd < end && message.get(valueEnd) != CoreFields.FIELD_SEPARATOR_BYTE) {
                    valueEnd++;
                }
                return Hashing.hash(message, valueStart, valueEnd - valueStart);
            }
        }
        return WireFormat.NO_MESSAGE_TYPE;
    }
}
