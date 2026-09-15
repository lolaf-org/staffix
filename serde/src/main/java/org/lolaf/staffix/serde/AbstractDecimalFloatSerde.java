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
package org.lolaf.staffix.serde;

import org.lolaf.ringos.threading.FastThreadLocal;
import org.lolaf.staffix.api.serde.DecimalFloat;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;

import java.nio.ByteBuffer;

/**
 * The shared encoding of a {@link org.lolaf.staffix.api.serde.DecimalFloat}: an unscaled long and a scale,
 * written as the digits a FIX price or quantity is made of.
 *
 * <p>Digits are emitted least-significant first into a fixed scratch buffer and then reversed, which avoids
 * dividing to find the length first. Subclasses differ only in whether the decoded value is cached.
 */
public abstract class AbstractDecimalFloatSerde implements SerDe<DecimalFloat> {

    private final FastThreadLocal<byte[]> byteArrayCache;

    protected AbstractDecimalFloatSerde() {
        byteArrayCache = FastThreadLocal.withInitial(() -> new byte[15]);
    }

    @Override
    public void serialize(ByteBuffer out, DecimalFloat value) {
        long unscaled = value.getUnscaledValue();
        int scale = value.getScale();

        if (unscaled == 0) {
            out.put(NumberSerdeUtils.ASCII_ZERO);
            if (scale > 0) {
                out.put(NumberSerdeUtils.POINT_CHAR);
                for (int i = 0; i < scale; i++) {
                    out.put(NumberSerdeUtils.ASCII_ZERO);
                }
            }
            return;
        }

        boolean negative = unscaled < 0;
        if (negative) {
            out.put(NumberSerdeUtils.NEGATIVE_CHAR);
        }

        // Emit absolute-value digits into a 15-byte scratch buffer, LSB first.
        // |unscaled| < 10^15 is enforced by DecimalFloat.of, so 15 digits always suffice.
        byte[] digits = byteArrayCache.get();
        int digitCount = 0;
        long magnitude = negative ? -unscaled : unscaled;
        while (magnitude > 0) {
            digits[digitCount++] = (byte) (NumberSerdeUtils.ASCII_ZERO + (magnitude % 10));
            magnitude /= 10;
        }

        if (scale <= 0) {
            // Integer with (-scale) trailing zeros, e.g. (12, -3) -> "12000"
            for (int i = digitCount - 1; i >= 0; i--) {
                out.put(digits[i]);
            }
            for (int i = 0; i < -scale; i++) {
                out.put(NumberSerdeUtils.ASCII_ZERO);
            }
        } else if (scale < digitCount) {
            // Decimal point sits inside the digit sequence, e.g. (12345, 2) -> "123.45"
            int intPartLen = digitCount - scale;
            for (int i = 0; i < intPartLen; i++) {
                out.put(digits[digitCount - 1 - i]);
            }
            out.put(NumberSerdeUtils.POINT_CHAR);
            for (int i = 0; i < scale; i++) {
                out.put(digits[scale - 1 - i]);
            }
        } else {
            // Leading "0." + padding zeros + digits, e.g. (1, 5) -> "0.00001"
            out.put(NumberSerdeUtils.ASCII_ZERO);
            out.put(NumberSerdeUtils.POINT_CHAR);
            for (int i = 0; i < scale - digitCount; i++) {
                out.put(NumberSerdeUtils.ASCII_ZERO);
            }
            for (int i = digitCount - 1; i >= 0; i--) {
                out.put(digits[i]);
            }
        }
    }

    @Override
    public DecimalFloat deserialize(DeserializationContext ctx) throws IllegalFieldValueException {
        byte[] buffer = ctx.getDeserializationBuffer();
        int offset = ctx.getStartOffset();
        int len = ctx.getLength();
        if (len == 0) {
            throw new IllegalFieldValueException("Unable to parse number: <empty>");
        }

        boolean negative = false;
        int i = 0;
        byte first = buffer[offset];
        if (first == NumberSerdeUtils.NEGATIVE_CHAR) {
            negative = true;
            i = 1;
        } else if (first == NumberSerdeUtils.POSITIVE_CHAR) {
            i = 1;
        }

        long unscaled = 0;
        byte scale = 0;
        boolean seenPoint = false;
        boolean seenDigit = false;
        // Once |unscaled| reaches 10^15, any further digits would exceed 15 sig digits
        // — unless they are trailing zeros (Reading A: trailing zeros are not significant).
        boolean atMaxPrecision = false;

        for (; i < len; i++) {
            byte b = buffer[offset + i];
            if (b == NumberSerdeUtils.POINT_CHAR) {
                if (seenPoint) {
                    throw new IllegalFieldValueException("multiple decimal points");
                }
                seenPoint = true;
                continue;
            }
            int d = b - NumberSerdeUtils.ASCII_ZERO;
            if (d < 0 || d > 9) {
                throw new IllegalFieldValueException((char) b + " is not a digit or a point");
            }
            seenDigit = true;

            if (atMaxPrecision) {
                if (d != 0) {
                    throw new IllegalFieldValueException("unscaledValue exceeds 15 significant digits");
                }
                // Trailing zero past the cap: drop if after decimal, widen magnitude via scale otherwise.
                if (!seenPoint) {
                    if (scale == Byte.MIN_VALUE) {
                        throw new IllegalFieldValueException("scale cannot be smaller than " + Byte.MIN_VALUE);
                    }
                    scale--;
                }
                continue;
            }

            long newUnscaled = negative ? unscaled * 10 - d : unscaled * 10 + d;
            // Short-circuit well below Long overflow (10^15 vs ~9.2·10^18) so a
            // malformed 20-digit input can't silently wrap into a valid-looking value.
            if (newUnscaled >= DecimalFloat.MAX_UNSCALED_MAGNITUDE
                    || newUnscaled <= -DecimalFloat.MAX_UNSCALED_MAGNITUDE) {
                if (d != 0) {
                    throw new IllegalFieldValueException("unscaledValue exceeds 15 significant digits");
                }
                atMaxPrecision = true;
                if (!seenPoint) {
                    scale--;
                }
                continue;
            }
            unscaled = newUnscaled;
            if (seenPoint) {
                if (scale == Byte.MAX_VALUE) {
                    throw new IllegalFieldValueException("scale cannot be greater than " + Byte.MAX_VALUE);
                }
                scale++;
            }
        }

        if (!seenDigit) {
            throw new IllegalFieldValueException("no digits");
        }
        return getInstance(unscaled, scale);
    }

    protected abstract DecimalFloat getInstance(long unscaled, byte scale);
}