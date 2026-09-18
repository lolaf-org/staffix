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

import org.lolaf.staffix.api.serde.IllegalFieldValueException;

/**
 * The digit tables and constants the numeric serdes share.
 *
 * <p>Two-digit lookup tables rather than repeated division: formatting a number a pair of digits at a time
 * halves the divisions, which is measurable on a path that writes a sequence number and two timestamps per
 * message.
 */
public class NumberSerdeUtils {

    protected static final byte[] DIGIT_TENS = {
            '0', '0', '0', '0', '0', '0', '0', '0', '0', '0',
            '1', '1', '1', '1', '1', '1', '1', '1', '1', '1',
            '2', '2', '2', '2', '2', '2', '2', '2', '2', '2',
            '3', '3', '3', '3', '3', '3', '3', '3', '3', '3',
            '4', '4', '4', '4', '4', '4', '4', '4', '4', '4',
            '5', '5', '5', '5', '5', '5', '5', '5', '5', '5',
            '6', '6', '6', '6', '6', '6', '6', '6', '6', '6',
            '7', '7', '7', '7', '7', '7', '7', '7', '7', '7',
            '8', '8', '8', '8', '8', '8', '8', '8', '8', '8',
            '9', '9', '9', '9', '9', '9', '9', '9', '9', '9',
    };
    protected static final byte[] DIGIT_ONES = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    };
    protected static final byte[] DIGITS = {
            '0', '1', '2', '3', '4', '5',
            '6', '7', '8', '9', 'a', 'b',
            'c', 'd', 'e', 'f', 'g', 'h',
            'i', 'j', 'k', 'l', 'm', 'n',
            'o', 'p', 'q', 'r', 's', 't',
            'u', 'v', 'w', 'x', 'y', 'z'
    };

    protected static final int POSITIVE = -1;
    protected static final int NEGATIVE = -2;
    protected static final int POINT = -3;
    protected static final int EXPONENT = -4;
    protected static final byte POSITIVE_CHAR = '+';
    protected static final byte NEGATIVE_CHAR = '-';
    protected static final byte POINT_CHAR = '.';
    protected static final int POINT_CHAR_INT = '.';
    protected static final byte ASCII_ZERO = '0';
    protected static final byte EXPONENT_CHAR = 'e';
    protected static final byte EXPONENT_UPPER_CHAR = 'E';

    private NumberSerdeUtils() {

    }

    public static int getNum(byte num) {
        int numAsInt = num - ASCII_ZERO;
        if (numAsInt < 0 || numAsInt > 9) {
            throw new IllegalFieldValueException((char) num + " is not a number");
        }
        return numAsInt;
    }

    public static int getNumOrSign(byte num) {
        int numAsInt = num - ASCII_ZERO;
        if (numAsInt >= 0 && numAsInt <= 9) {
            return numAsInt;
        } else if (num == POSITIVE_CHAR) {
            return POSITIVE;
        } else if (num == NEGATIVE_CHAR) {
            return NEGATIVE;
        }
        throw new IllegalFieldValueException((char) num + " is not a number or a sign");
    }

    public static int getNumOrSignOrPoint(byte num) {
        int numAsInt = num - ASCII_ZERO;
        if (numAsInt >= 0 && numAsInt <= 9) {
            return numAsInt;
        } else if (num == POSITIVE_CHAR) {
            return POSITIVE;
        } else if (num == NEGATIVE_CHAR) {
            return NEGATIVE;
        } else if (num == POINT_CHAR) {
            return POINT;
        }
        throw new IllegalFieldValueException((char) num + " is not a number or a sign or a point");
    }

    public static int getNumOrPointOrExponent(byte num) {
        int numAsInt = num - ASCII_ZERO;
        if (numAsInt >= 0 && numAsInt <= 9) {
            return numAsInt;
        } else if (num == POINT_CHAR) {
            return POINT;
        } else if (num == EXPONENT_CHAR || num == EXPONENT_UPPER_CHAR) {
            return EXPONENT;
        }
        throw new IllegalFieldValueException((char) num + " is not a number or an exponent or a point");
    }
}
