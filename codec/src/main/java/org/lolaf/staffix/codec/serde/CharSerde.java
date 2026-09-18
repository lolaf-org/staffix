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
import org.lolaf.staffix.api.serde.SerDe;

/**
 * Reads and writes a single-character FIX field - a side, an order type, a time-in-force.
 *
 * <p>One byte, so there is nothing to parse; it exists so a generated encoder has a serde for every field type
 * rather than a special case.
 */
@UtilityClass
public class CharSerde {

    public static char deserialize(SerDe.DeserializationContext context) {
        return (char) context.getDeserializationBuffer()[context.getStartOffset()];
    }

    public static byte serialize(char value) {
        return (byte) value;
    }
}