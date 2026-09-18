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

import static org.lolaf.staffix.codec.serde.NumberSerdeUtils.*;

/**
 * Reads and writes a double in the decimal form FIX requires - never scientific notation, whatever the
 * magnitude.
 *
 * <p>Prefer {@link DecimalFloatSerde} for prices and quantities. A double cannot represent most decimal
 * fractions exactly, and a price that arrives as 1.1 and leaves as 1.1000000000000001 is a reconciliation
 * break; {@link org.lolaf.staffix.api.serde.DecimalFloat} keeps the digits the counterparty sent.
 */
@UtilityClass
public class DoubleSerde {

    public static double deserialize(SerDe.DeserializationContext context) {
        return deserialize(context.getDeserializationBuffer(), context.getStartOffset(), context.getLength());
    }

    public static double deserialize(byte[] doubleBuffer) {
        return deserialize(doubleBuffer, 0, doubleBuffer.length);
    }

    /**
     * Serializes a {@code double} to its FIX float wire representation: plain
     * decimal notation (no scientific form) with at most 15 significant digits,
     * banker's-rounded (half-to-even). The FIX spec bounds precision at 15
     * significant digits but does not bound magnitude, so any finite double is
     * accepted &mdash; values with tiny or huge magnitude simply expand into
     * long plain-decimal strings (e.g. {@link Double#MAX_VALUE} produces a
     * ~310-char integer).
     *
     * <p>Special values:
     * <ul>
     *   <li>{@code 0.0} and {@code -0.0} both encode as {@code "0"}.</li>
     *   <li>{@code NaN} and {@code ±Infinity} have no legal FIX wire form
     *       (alphabet is digits, {@code '-'}, {@code '.'}) and throw
     *       {@link IllegalFieldValueException}.</li>
     * </ul>
     *
     * <p>The returned array is borrowed from a per-thread cache: it is valid only
     * until the next call to {@code serialize} on the same thread. Callers that
     * need to retain the bytes beyond that must copy them.
     *
     * @param value the double to serialize
     * @return a byte array containing the ASCII-encoded FIX float representation,
     * sized exactly to the encoded length
     * @throws IllegalFieldValueException if {@code value} is {@code NaN} or
     *                                    {@code ±Infinity}
     */
    public static byte[] serialize(double value) throws IllegalFieldValueException {
        return DoubleToDecimal.toDecimal(value);
    }

    static double deserialize(String value) {
        return deserialize(value.getBytes(SerDe.CHARSET), 0, value.length());
    }

    private static double deserialize(byte[] value, int offset, int len) {
        try {
            // Threshold is 9 (not 10): a 10-digit decimal like "9999999999" = 9_999_999_999
            // exceeds Integer.MAX_VALUE (2_147_483_647) and would silently wrap in the int
            // accumulator of the small path. The big path uses a long accumulator.
            return len <= 9 ? deserializeSmallDouble(value, offset, len) : deserializeBigDouble(value, offset, len);
        } catch (Exception ex) {
            throw new IllegalFieldValueException("Unable to parse number: " + new String(value, offset, len, SerDe.CHARSET), ex);
        }
    }

    private static double deserializeSmallDouble(byte[] value, int offset, int len) {
        int exponent = 1;
        int num = 0;
        int firstNum = getNumOrSignOrPoint(value[offset]);
        boolean isPositive = true;
        if (firstNum >= 0) {
            exponent *= 10;
            num = firstNum;
        } else {
            // we have a .0001 number notation
            if (firstNum == POINT) {
                int i = 0;
                while (++i < len) {
                    exponent *= 10;
                    num = num * 10 + getNum(value[offset + i]);
                }
                return num / (double) exponent;
            }
            // we have a sign +-
            isPositive = firstNum != NEGATIVE;
        }
        for (int i = 1; i < len; i++) {
            int newNum = getNumOrPointOrExponent(value[offset + i]);
            if (newNum >= 0) {
                exponent *= 10;
                num = num * 10 + newNum;
            } else if (newNum == POINT) {
                exponent = 1;
                while (++i < len) {
                    exponent *= 10;
                    newNum = getNumOrPointOrExponent(value[offset + i]);
                    if (newNum == EXPONENT) {
                        // 8.099018835588945E-4 like notation
                        return safelyParseDouble(value, offset, len);
                    }
                    num = num * 10 + newNum;
                }
                return isPositive ? num / (double) exponent : -num / (double) exponent;
            } else {
                // exponent notation we fallback to regular double impl
                return safelyParseDouble(value, offset, len);
            }
        }
        return isPositive ? num : -num;
    }

    private static double deserializeBigDouble(byte[] value, int offset, int len) {
        long exponent = 1;
        long num = 0;
        int firstNum = getNumOrSignOrPoint(value[offset]);
        boolean isPositive = true;
        if (firstNum >= 0) {
            exponent *= 10;
            num = firstNum;
        } else {
            // we have a .0001 number notation
            if (firstNum == POINT) {
                int i = 0;
                while (++i < len) {
                    exponent *= 10;
                    num = num * 10 + getNum(value[offset + i]);
                    if (num < 0 || exponent < 0) {
                        return safelyParseDouble(value, offset, len);
                    }
                }
                return num / (double) exponent;
            }
            // we have a sign +-
            isPositive = firstNum != NEGATIVE;
        }
        for (int i = 1; i < len; i++) {
            int newNum = getNumOrPointOrExponent(value[offset + i]);
            if (newNum >= 0) {
                exponent *= 10;
                num = num * 10 + newNum;
                if (num < 0 || exponent < 0) {
                    return safelyParseDouble(value, offset, len);
                }
            } else if (newNum == POINT) {
                exponent = 1;
                while (++i < len) {
                    exponent *= 10;
                    newNum = getNumOrPointOrExponent(value[offset + i]);
                    if (newNum == EXPONENT) {
                        return safelyParseDouble(value, offset, len);
                    }
                    num = num * 10 + newNum;
                    if (num < 0 || exponent < 0) {
                        return safelyParseDouble(value, offset, len);
                    }
                }
                return isPositive ? num / (double) exponent : -num / (double) exponent;
            } else {
                // exponent notation we fallback to regular double impl
                return safelyParseDouble(value, offset, len);
            }
        }
        return isPositive ? num : -num;
    }

    private static double safelyParseDouble(byte[] value, int offset, int len) {
        return Double.parseDouble(new String(value, offset, len, SerDe.CHARSET));
    }

}