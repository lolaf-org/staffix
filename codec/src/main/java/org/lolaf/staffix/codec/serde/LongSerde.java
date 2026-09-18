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
 * Reads and writes a long as ASCII digits, length-specialised the same way {@link IntSerde} is.
 *
 * <p>{@code Long.MIN_VALUE} is held as a constant because it is the one value whose absolute value does not
 * fit in a long, so the usual negate-and-format path cannot produce it.
 */
@UtilityClass
public class LongSerde {

    private static final long[] SIZE_TABLE = {
            9L,
            99L,
            999L,
            9999L,
            99999L,
            999999L,
            9999999L,
            99999999L,
            999999999L,
            9999999999L,
            99999999999L,
            999999999999L,
            9999999999999L,
            99999999999999L,
            999999999999999L,
            9999999999999999L,
            99999999999999999L,
            999999999999999999L,
            Long.MAX_VALUE};

    private static final FastThreadLocal<ByteArraysCache> BA_CACHE = FastThreadLocal.withInitial(() -> new ByteArraysCache(Long.toString(Long.MAX_VALUE).length()));
    private static final byte[] MIN_VALUE_BYTES = Long.toString(Long.MIN_VALUE).getBytes(SerDe.CHARSET);

    public static long deserialize(SerDe.DeserializationContext context) {
        return deserialize(context.getDeserializationBuffer(), context.getStartOffset(), context.getLength());
    }

    public static long deserialize(byte[] numberBuffer) {
        return deserialize(numberBuffer, 0, numberBuffer.length);
    }

    private static long deserialize(byte[] numberBuffer, int startIndex, int len) {
        if (len == 0) {
            throw new IllegalFieldValueException("cannot parse empty number");
        } else if (len == 1) {
            return getNum(numberBuffer[startIndex]);
        }
        // The magnitude is an unsigned run of digits; only an optional leading sign differs. Delegating lets the
        // length-specialized deserializeUnsigned handle the digits, and preserves the two's-complement overflow
        // that makes Long.MIN_VALUE round-trip (the magnitude wraps, then negation restores it).
        byte first = numberBuffer[startIndex];
        if (first == NEGATIVE_CHAR) {
            return -deserializeUnsigned(numberBuffer, startIndex + 1, len - 1);
        } else if (first == POSITIVE_CHAR) {
            return deserializeUnsigned(numberBuffer, startIndex + 1, len - 1);
        }
        return deserializeUnsigned(numberBuffer, startIndex, len);
    }


    public static long deserializeUnsigned(SerDe.DeserializationContext context) {
        return deserializeUnsigned(context.getDeserializationBuffer(), context.getStartOffset(), context.getLength());
    }

    public static long deserializeUnsigned(byte[] numberBuffer, int startIndex, int len) {
        // Unrolled, branch-free digit combination for the common short lengths (e.g. small MsgSeqNum), exposing
        // ILP the accumulator loop cannot while preserving the exact per-digit validation of getNum. Longer
        // values fall through to the loop. The len 1-4 results fit in an int and widen to long on return.
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
                long num = 0;
                int end = startIndex + len;
                while (startIndex < end) {
                    num = num * 10 + getNum(numberBuffer[startIndex++]);
                }
                return num;
        }
    }

    public static void serialize(long value, byte[] dst) {
        byte sign = 0;
        if (value < 0) {
            if (value == Long.MIN_VALUE) { // Special case
                System.arraycopy(MIN_VALUE_BYTES, 0, dst, 0, dst.length);
                return;
            }
            sign = '-';
            value = -value;
        }
        long q, r;
        int charPos = dst.length;
        // Generate two digits per iteration
        while (value >= 65536) {
            q = value / 100;
            // really: r = i - (q * 100)
            r = value - ((q << 6) + (q << 5) + (q << 2));
            value = q;
            int index = (int) r;
            dst[--charPos] = DIGIT_ONES[index];
            dst[--charPos] = DIGIT_TENS[index];
        }

        // Fall thru to fast mode for smaller numbers
        while (true) {
            q = value * 52429 >>> 16 + 3;
            r = value - ((q << 3) + (q << 1)); // r = i-(q*10) ...
            dst[--charPos] = DIGITS[(int) r];
            value = q;
            if (value == 0) {
                break;
            }
        }
        if (sign != 0) {
            dst[--charPos] = sign;
        }
    }

    public static byte[] serialize(long value) {
        byte[] output = new byte[getLongSize(value)];
        serialize(value, output);
        return output;
    }

    public static byte[] cachedSerialize(long value) {
        byte[] output = BA_CACHE.get().forSize(getLongSize(value));
        serialize(value, output);
        return output;
    }

    private static int getLongSize(long data) {
        if (data < 0) {
            if (data == Long.MIN_VALUE) {
                return MIN_VALUE_BYTES.length;
            }
            return getPositiveLongSize(-data) + 1;
        }
        return getPositiveLongSize(data);
    }

    private static int getPositiveLongSize(long x) {
        for (int i = 0; ; i++) {
            if (x <= SIZE_TABLE[i]) {
                return i + 1;
            }
        }
    }
}