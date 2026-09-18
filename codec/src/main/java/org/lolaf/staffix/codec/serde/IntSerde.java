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
import org.lolaf.ringos.threading.FastThreadLocal;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;

import static org.lolaf.staffix.codec.serde.NumberSerdeUtils.*;

/**
 * Reads and writes an int as ASCII digits, in place.
 *
 * <p>Specialised by length rather than looping: the number of digits is known before parsing starts, so each
 * case is straight-line multiply-and-add with no bounds test per digit. Tags, sequence numbers and quantities
 * are the most common fields on the wire, so this is the parser's hottest arithmetic.
 *
 * <p>The unsigned variants exist for the fields that cannot be negative - a tag, a length, a sequence number -
 * and skip the sign check entirely.
 */
@UtilityClass
public class IntSerde {

    private static final int[] SIZE_TABLE = {
            9,
            99,
            999,
            9999,
            99999,
            999999,
            9999999,
            99999999,
            999999999,
            Integer.MAX_VALUE};
    private static final FastThreadLocal<ByteArraysCache> BA_CACHE = FastThreadLocal.withInitial(() -> new ByteArraysCache(Integer.toString(Integer.MAX_VALUE).length()));
    private static final byte[] MIN_VALUE_BYTES = Integer.toString(Integer.MIN_VALUE).getBytes(SerDe.CHARSET);

    public static int deserialize(SerDe.DeserializationContext context) {
        return deserialize(context.getDeserializationBuffer(), context.getStartOffset(), context.getLength());
    }

    public static int deserialize(byte[] numberBuffer) {
        return deserialize(numberBuffer, 0, numberBuffer.length);
    }

    private static int deserialize(byte[] numberBuffer, int startIndex, int len) {
        if (len == 0) {
            throw new IllegalFieldValueException("cannot parse empty number");
        } else if (len == 1) {
            return getNum(numberBuffer[startIndex]);
        }
        // The magnitude is an unsigned run of digits; only an optional leading sign differs. Delegating lets the
        // length-specialized deserializeUnsigned handle the digits, and preserves the two's-complement overflow
        // that makes Integer.MIN_VALUE round-trip (the magnitude 2147483648 wraps, then negation restores it).
        byte first = numberBuffer[startIndex];
        if (first == NEGATIVE_CHAR) {
            return -deserializeUnsigned(numberBuffer, startIndex + 1, len - 1);
        } else if (first == POSITIVE_CHAR) {
            return deserializeUnsigned(numberBuffer, startIndex + 1, len - 1);
        }
        return deserializeUnsigned(numberBuffer, startIndex, len);
    }

    public static int deserializeUnsigned(SerDe.DeserializationContext context) {
        return deserializeUnsigned(context.getDeserializationBuffer(), context.getStartOffset(), context.getLength());
    }

    public static int deserializeUnsigned(byte[] numberBuffer, int startIndex, int len) {
        // Unrolled, branch-free digit combination for the common short lengths (field tags, checksum, the
        // fixed-width UTC date/time components). Each digit load/validation is independent, so this exposes ILP
        // the accumulator loop cannot — while preserving the exact per-digit validation of getNum. When the
        // caller passes a constant len (e.g. UtcTimeOnlySerde), the switch folds to a single arm after inlining.
        switch (len) {
            case 1:
                return getNum(numberBuffer[startIndex]);
            case 2:
                return getNum(numberBuffer[startIndex]) * 10
                        + getNum(numberBuffer[startIndex + 1]);
            case 3:
                return getNum(numberBuffer[startIndex]) * 100
                        + getNum(numberBuffer[startIndex + 1]) * 10
                        + getNum(numberBuffer[startIndex + 2]);
            case 4:
                return getNum(numberBuffer[startIndex]) * 1000
                        + getNum(numberBuffer[startIndex + 1]) * 100
                        + getNum(numberBuffer[startIndex + 2]) * 10
                        + getNum(numberBuffer[startIndex + 3]);
            default:
                int num = 0;
                int end = startIndex + len;
                while (startIndex < end) {
                    num = num * 10 + getNum(numberBuffer[startIndex++]);
                }
                return num;
        }
    }

    public static void serialize(int value, byte[] dst) {
        serialize(value, dst, dst.length, 0);
    }

    public static void serialize(int value, byte[] dst, int valueSize, int startOffset) {
        byte sign = 0;
        if (value < 0) {
            if (value == Integer.MIN_VALUE) {
                System.arraycopy(MIN_VALUE_BYTES, 0, dst, startOffset, valueSize);
                return;
            }
            sign = '-';
            value = -value;
        }
        int q, r;
        int charPos = valueSize;
        // Generate two digits per iteration
        while (value >= 65536) {
            q = value / 100;
            // really: r = i - (q * 100)
            r = value - ((q << 6) + (q << 5) + (q << 2));
            value = q;
            dst[startOffset + --charPos] = DIGIT_ONES[r];
            dst[startOffset + --charPos] = DIGIT_TENS[r];
        }

        // Fall thru to fast mode for smaller numbers
        while (true) {
            q = value * 52429 >>> 16 + 3;
            r = value - ((q << 3) + (q << 1)); // r = i-(q*10) ...
            dst[startOffset + --charPos] = DIGITS[r];
            value = q;
            if (value == 0) {
                break;
            }
        }
        if (sign != 0) {
            dst[startOffset + --charPos] = sign;
        }
    }

    public static byte[] serialize(int value) {
        byte[] output = new byte[getIntSize(value)];
        serialize(value, output, output.length, 0);
        return output;
    }

    public static byte[] cachedSerialize(int value) {
        byte[] output = BA_CACHE.get().forSize(getIntSize(value));
        serialize(value, output, output.length, 0);
        return output;
    }

    private static int getIntSize(int data) {
        if (data < 0) {
            if (data == Integer.MIN_VALUE) {
                return MIN_VALUE_BYTES.length;
            }
            return getPositiveIntSize(-data) + 1;
        }
        return getPositiveIntSize(data);
    }

    public static int getPositiveIntSize(int x) {
        for (int i = 0; ; i++) {
            if (x <= SIZE_TABLE[i]) {
                return i + 1;
            }
        }
    }
}