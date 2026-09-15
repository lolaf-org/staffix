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
package org.lolaf.staffix.api.serde;

import lombok.experimental.UtilityClass;

import java.nio.ByteBuffer;

/**
 * The hash the parser routes and compares on.
 *
 * <p>Defined over bytes so a wire value can be identified without building a String for it - the overload taking
 * one exists only so a configured value can be compared against a wire value, and the two must agree.
 */
@UtilityClass
public class Hashing {

    /**
     * {@link SerDe#CHARSET}, because this has to agree with {@link #hash(byte[], int, int)} over the same value's
     * wire bytes: the two overloads exist to be compared without materialising a String on the message path.
     */
    public static int hash(String toHash) {
        byte[] toHashBytes = toHash.getBytes(SerDe.CHARSET);
        return hash(toHashBytes, 0, toHashBytes.length);
    }

    public static int hash(SerDe.DeserializationContext deserializationContext) {
        return hash(deserializationContext.getDeserializationBuffer(), deserializationContext.getStartOffset(), deserializationContext.getLength());
    }

    public static int hash(byte[] toHash, int offset, int len) {
        int hash = 0;
        int multiplier = 1;
        for (int i = offset + len - 1; i >= offset; i--) {
            hash += toHash[i] * multiplier;
            int shifted = multiplier << 5;
            multiplier = shifted - multiplier;
        }
        return avalanche(hash);
    }

    public static int hash(ByteBuffer toHash, int offset, int len) {
        int hash = 0;
        int multiplier = 1;
        for (int i = offset + len - 1; i >= offset; i--) {
            hash += toHash.get(i) * multiplier;
            int shifted = multiplier << 5;
            multiplier = shifted - multiplier;
        }
        return avalanche(hash);
    }

    private static int avalanche(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}