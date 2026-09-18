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
package org.lolaf.staffix.codec.serde;

import lombok.experimental.UtilityClass;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;

/**
 * Reads and writes a FIX boolean, which is the single character {@code Y} or {@code N}.
 *
 * <p>Not {@code true}/{@code false}: the constants are here so nothing has to remember that.
 */
@UtilityClass
public class BooleanSerde {

    public static final String TRUE = "Y";
    public static final String FALSE = "N";
    private static final byte TRUE_BYTE = 'Y';
    private static final byte FALSE_BYTE = 'N';

    public static boolean deserialize(SerDe.DeserializationContext context) {
        byte value = context.getDeserializationBuffer()[context.getStartOffset()];
        if (value == BooleanSerde.TRUE_BYTE) {
            return true;
        }
        if (value == BooleanSerde.FALSE_BYTE) {
            return false;
        }
        throw new IllegalFieldValueException("Unknown Boolean value: " + ((char) value));
    }

    /**
     * Deserializes a boolean without rejecting an unknown value: anything but {@code Y} reads as {@code false}. For
     * header flags that carry no session layer semantics, where a malformed value must be left to the normal field
     * decoding path to reject rather than aborting the parsing of the whole message.
     */
    public static boolean deserializeLenient(SerDe.DeserializationContext context) {
        return context.getDeserializationBuffer()[context.getStartOffset()] == TRUE_BYTE;
    }

    public static byte serialize(boolean value) {
        return value ? TRUE_BYTE : FALSE_BYTE;
    }
}