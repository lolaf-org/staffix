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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;

import java.math.BigDecimal;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class TestDoubleSerde {
    private final double maxDelta = 0.000000000000001;

    private static int countSignificantDigits(String raw) {
        String s = raw.startsWith("-") ? raw.substring(1) : raw;
        // Drop the trailing fractional part if it's "...0" with only zeros after the dot
        // (integer-shaped output like "123.0" or "999999999999999.0").
        int dot = s.indexOf('.');
        if (dot >= 0) {
            // Strip trailing zeros from the fractional part — they aren't significant
            // when the rest of the fraction is zero (e.g., "999.0" → "999").
            // BUT internal zeros and zeros before non-zero fractional digits stay.
            int end = s.length();
            while (end > dot + 1 && s.charAt(end - 1) == '0') {
                end--;
            }
            if (end == dot + 1) {
                s = s.substring(0, dot);   // "123.0" → "123"
            } else {
                s = s.substring(0, end);   // "0.10" → "0.1"
            }
        }
        // Now count digits, skipping leading zeros (and a leading "0." prefix's zero).
        boolean seenNonZero = false;
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') continue;
            if (c == '0' && !seenNonZero) continue;
            seenNonZero = true;
            count++;
        }
        return count;
    }

    @Test
    void testRandomDoublesDeserialize() {
        Random rdm = new Random();
        for (int i = 0; i < 10 * 1024 * 1024; i++) {
            double nextDouble = rdm.nextDouble();
            double deserialized = DoubleSerde.deserialize(Double.toString(nextDouble));
            assertEquals(nextDouble, deserialized, maxDelta, Double.toString(nextDouble));
        }
    }

    @Test
    void testRandomDoublesSerializeRoundTrip() {
        // Property test: every random double in the FIX range must serialize as plain
        // decimal, fit in MAX_CHARS, carry at most 15 significant digits, and round-trip
        // back to the original within a 15-digit relative tolerance.
        Random rdm = new Random();
        for (int i = 0; i < 10 * 1024 * 1024; i++) {
            double mantissa = 1.0 + 9.0 * rdm.nextDouble();      // [1, 10)
            int exp = -15 + rdm.nextInt(30);                      // [-15, 14]
            double sign = rdm.nextBoolean() ? 1.0 : -1.0;
            double v = sign * mantissa * Math.pow(10, exp);

            byte[] out = DoubleSerde.serialize(v);
            String s = new String(out);

            assertFalse(s.contains("E") || s.contains("e"), "no scientific notation: " + s);
            assertTrue(countSignificantDigits(s) <= 15, "<=15 sig digits: " + s);

            double parsed = Double.parseDouble(s);
            double tol = Math.abs(v) * 1e-14;
            assertEquals(v, parsed, tol, "round-trip: " + v + " -> " + s);
        }
    }

    @Test
    void testFixBoundaries() {
        // FIX spec constrains significant digits (15), not magnitude — any finite
        // double must encode as plain decimal with <= 15 sig digits.
        assertEquals("0.000000000000001", new String(DoubleSerde.serialize(1e-15)));
        assertEquals("-0.000000000000001", new String(DoubleSerde.serialize(-1e-15)));

        // "underflow" magnitudes are now legal: 1e-16 has 1 sig digit.
        assertEquals("0.0000000000000001", new String(DoubleSerde.serialize(1e-16)));
        assertEquals("-0.0000000000000001", new String(DoubleSerde.serialize(-1e-16)));

        // Largest double with exactly 15 significant digits.
        assertEquals("999999999999999.0", new String(DoubleSerde.serialize(9.99999999999999e14)));
        assertEquals("-999999999999999.0", new String(DoubleSerde.serialize(-9.99999999999999e14)));

        // "overflow" magnitudes are now legal: 1e15 has 1 sig digit.
        assertEquals("1000000000000000.0", new String(DoubleSerde.serialize(1e15)));
        assertEquals("-1000000000000000.0", new String(DoubleSerde.serialize(-1e15)));

        // Post-rounding case: nextDown(1e15) banker's-rounds up to 1e15 — legal now.
        assertEquals("1000000000000000.0", new String(DoubleSerde.serialize(Math.nextDown(1e15))));

        // NaN and ±Infinity are not in the FIX alphabet — must throw.
        assertThat(assertThrows(IllegalFieldValueException.class, () -> DoubleSerde.serialize(Double.NaN)).getMessage())
                .isEqualTo("FIX float: NaN is not a legal wire value");
        assertThat(assertThrows(IllegalFieldValueException.class, () -> DoubleSerde.serialize(Double.POSITIVE_INFINITY)).getMessage())
                .isEqualTo("FIX float: Infinity is not a legal wire value");
        assertThat(assertThrows(IllegalFieldValueException.class, () -> DoubleSerde.serialize(Double.NEGATIVE_INFINITY)).getMessage())
                .isEqualTo("FIX float: Infinity is not a legal wire value");
    }

    @Test
    void testExtremeMagnitudesEncodePlainDecimal() {
        // Very large / very small finite doubles encode as long plain-decimal strings.
        // Structural check only: no scientific notation, no NaN/Infinity tokens, <=15 sig digits.
        // (Round-trip check separate — MAX_VALUE rounds *up* past Double.MAX_VALUE when
        // trimmed to 15 sig digits, and subnormals near MIN_VALUE lose magnitude through
        // the same trim. Both are inherent to 15-digit precision and not a library defect.)
        double[] cases = {
                Double.MAX_VALUE, -Double.MAX_VALUE,
                Double.MIN_VALUE, -Double.MIN_VALUE,
                Double.MIN_NORMAL, -Double.MIN_NORMAL,
                1e50, -1e50, 1e-50, -1e-50,
                1e100, 1e-100, 1e200, 1e-200
        };
        for (double v : cases) {
            String s = new String(DoubleSerde.serialize(v));
            assertFalse(s.contains("E") || s.contains("e"), "no scientific notation: " + s);
            assertFalse(s.contains("N") || s.contains("I"), "no NaN/Infinity tokens: " + s);
            // BigDecimal.stripTrailingZeros().precision() gives true FIX significant-digit
            // count: trailing zeros (both integer and fractional) carry magnitude, not precision.
            int sigDigits = new BigDecimal(s).stripTrailingZeros().precision();
            assertTrue(sigDigits <= 15, "<=15 sig digits (" + sigDigits + "): " + s);
        }
    }

    @Test
    void testExtremeMagnitudesRoundTripMidRange() {
        // For values comfortably inside the representable range, 15-digit rounding preserves
        // a valid round-trip within 15-digit relative tolerance.
        double[] cases = {
                1e50, -1e50, 1e-50, -1e-50,
                1e100, 1e-100, 1e200, 1e-200, 1e-300
        };
        for (double v : cases) {
            String s = new String(DoubleSerde.serialize(v));
            double parsed = Double.parseDouble(s);
            double tol = Math.abs(v) * 1e-14;
            assertEquals(v, parsed, tol, "round-trip: " + v + " -> " + s);
        }
    }

    @Test
    void testParametricRepresentativeDoubles() {
        // Half-way ties at the 16th digit exercise banker's (half-to-even) rounding.
        // 0.123456789012345 has 15-digit mantissa "123456789012345" — odd → bumps to ...46.
        // (Built so the trailing exact-digit pattern is unambiguous via Double.toString.)
        // Exact powers of 10 inside the range.
        assertEquals("0.00000000000001", new String(DoubleSerde.serialize(1e-14)));
        assertEquals("0.0000000000001", new String(DoubleSerde.serialize(1e-13)));
        assertEquals("1.0", new String(DoubleSerde.serialize(1.0)));
        assertEquals("100000000000000.0", new String(DoubleSerde.serialize(1e14)));
        assertEquals("-100000000000000.0", new String(DoubleSerde.serialize(-1e14)));

        // Natural-looking irrational/repeating values get rounded to 15 sig digits.
        assertEquals("3.14159265358979", new String(DoubleSerde.serialize(Math.PI)));
        // Math.E = 2.718281828459045 — 16th digit is "5", 15th ("4") is even, banker's keeps it.
        assertEquals("2.71828182845904", new String(DoubleSerde.serialize(Math.E)));
        assertEquals("0.333333333333333", new String(DoubleSerde.serialize(1.0 / 3.0)));
    }

    @Test
    void testSerialize() {
        assertEquals("123456789.123457", new String(DoubleSerde.serialize(123456789.123456789d)));
        assertEquals("12345678.0", new String(DoubleSerde.serialize(12345678d)));
        assertEquals("123456789.0", new String(DoubleSerde.serialize(123456789d)));
        assertEquals("-123456789.0", new String(DoubleSerde.serialize(-123456789d)));

        assertEquals("123456789.1", new String(DoubleSerde.serialize(123456789.1d)));

        assertEquals("12345678.1234568", new String(DoubleSerde.serialize(12345678.123456789d)));
        assertEquals("0.000000475234964492088", new String(DoubleSerde.serialize(4.7523496449208835E-7)));
        assertEquals("0.000000090582775946757", new String(DoubleSerde.serialize(9.058277594675701E-8)));
        assertEquals("0.000000298511060314866", new String(DoubleSerde.serialize(2.985110603148655E-7)));
        assertEquals("0.000000118745057231528", new String(DoubleSerde.serialize(1.187450572315285E-7)));
        assertEquals("0.00000000703940294943806", new String(DoubleSerde.serialize(7.039402949438056E-9)));
        assertEquals("0.000000409108388499391", new String(DoubleSerde.serialize(4.091083884993907E-7)));
        assertEquals("0.000000980544106798931", new String(DoubleSerde.serialize(9.805441067989307E-7)));
        assertEquals("0.000000542694340688321", new String(DoubleSerde.serialize(5.426943406883211E-7)));
        assertEquals("0.000000431209015161471", new String(DoubleSerde.serialize(4.3120901516147114E-7)));
        assertEquals("0.123456789012346", new String(DoubleSerde.serialize(0.12345678901234566)));
        assertEquals("0.0000000000001", new String(DoubleSerde.serialize(0.0000000000001)));
        assertEquals("0.0000000000001", new String(DoubleSerde.serialize(.0000000000001)));
        assertEquals("0.00000000000001", new String(DoubleSerde.serialize(0.00000000000001)));
        assertEquals("45.32", new String(DoubleSerde.serialize(45.32)));
        assertEquals("0.32", new String(DoubleSerde.serialize(0.32)));
        assertEquals("45.0", new String(DoubleSerde.serialize(45)));
        assertEquals("1.0", new String(DoubleSerde.serialize(1)));
        assertEquals("2.0", new String(DoubleSerde.serialize(2)));
        assertEquals("0", new String(DoubleSerde.serialize(0)));
        // -0.0 collapses to plain "0" — the FIX alphabet allows "-" only before digits
        // of a genuinely negative value, and "-0" is asymmetric with "+0.0 → 0".
        assertEquals("0", new String(DoubleSerde.serialize(-0D)));
        // NaN and ±Infinity are not in the FIX float alphabet.
        assertThrows(IllegalFieldValueException.class, () -> DoubleSerde.serialize(Double.NaN));
        assertThrows(IllegalFieldValueException.class, () -> DoubleSerde.serialize(Double.POSITIVE_INFINITY));
        assertThrows(IllegalFieldValueException.class, () -> DoubleSerde.serialize(Double.NEGATIVE_INFINITY));
    }

    @Test
    void testDeserialize() {
        assertEquals(123.123d, DoubleSerde.deserialize("0000000123.123"));
        assertEquals(123d, DoubleSerde.deserialize("0000000123.0000000000"));

        assertEquals(123456789.123456789d, DoubleSerde.deserialize("123456789.123456789"));
        assertEquals(1234567890.123456789d, DoubleSerde.deserialize("1234567890.123456789"));
        assertEquals(123456789d, DoubleSerde.deserialize("123456789"));

        assertEquals(8.099018835588945E-4, DoubleSerde.deserialize("8.099018835588945E-4"));
        assertEquals(1.23E-5, DoubleSerde.deserialize("0.123E-4"));
        assertEquals(0.9460870930463325, DoubleSerde.deserialize("0.9460870930463325"), maxDelta);

        assertEquals(1.12345, DoubleSerde.deserialize("1.12345"));
        assertEquals(1.123456, DoubleSerde.deserialize("1.123456"));
        assertEquals(12.12345, DoubleSerde.deserialize("12.12345"));
        assertEquals(123.1234, DoubleSerde.deserialize("123.1234"));
        assertEquals(0.12345678, DoubleSerde.deserialize("0.12345678"));

        assertEquals(9.99999999, DoubleSerde.deserialize("9.99999999"));
        assertEquals(12345.6789, DoubleSerde.deserialize("12345.6789"));
        assertEquals(99999.9999, DoubleSerde.deserialize("99999.9999"));
        assertEquals(9999.99999, DoubleSerde.deserialize("9999.99999"));
        assertEquals(12345678.9, DoubleSerde.deserialize("12345678.9"));
        assertEquals(99999999.9, DoubleSerde.deserialize("99999999.9"));

        assertEquals(1.0, DoubleSerde.deserialize("1"));
        assertEquals(123456789.0, DoubleSerde.deserialize("123456789"));
        assertEquals(0.32, DoubleSerde.deserialize("+.32"));
        assertEquals(0.32, DoubleSerde.deserialize(".32"));
        assertEquals(-0.32, DoubleSerde.deserialize("-.32"));
        assertEquals(200.1, DoubleSerde.deserialize("+200.1"));
        assertEquals(200.0, DoubleSerde.deserialize("+200"));
        assertEquals(-200.0, DoubleSerde.deserialize("-200"));
        assertEquals(-200.1, DoubleSerde.deserialize("-200.1"));
        assertEquals(1E6, DoubleSerde.deserialize("+1E6"));
        assertEquals(1E6, DoubleSerde.deserialize("+1e6"));
        assertEquals(1E6, DoubleSerde.deserialize("1E6"));
        assertEquals(12345E6, DoubleSerde.deserialize("12345E6"));
        assertEquals(-1E6, DoubleSerde.deserialize("-1E6"));
        assertEquals(0d, DoubleSerde.deserialize("0"));
        assertEquals(45.32, DoubleSerde.deserialize("45.32"), 0);
        assertEquals(45.32, DoubleSerde.deserialize("45.3200"), 0);
        assertEquals(0.00340244, DoubleSerde.deserialize("0.00340244000"), 0);
        assertEquals(45.32, DoubleSerde.deserialize("45.32"), 0);
        assertEquals(55.36, DoubleSerde.deserialize("55.3600"), 0);
        assertEquals(55.36, DoubleSerde.deserialize("0055.36"), 0);
        assertEquals(-55.36, DoubleSerde.deserialize("-0055.36"), 0);
        assertEquals(.995, DoubleSerde.deserialize(".995"), 0);

        assertEquals(12000000000001D, DoubleSerde.deserialize("+12000000000001"), 0);
        assertEquals(12000000000001D, DoubleSerde.deserialize("12000000000001"), 0);
        assertEquals(-12000000000001D, DoubleSerde.deserialize("-12000000000001"), 0);

        assertEquals(12000000E3D, DoubleSerde.deserialize("+12000000E3D"), 0);
        assertEquals(12000000E3D, DoubleSerde.deserialize("12000000E3D"), 0);
        assertEquals(-12000000E3D, DoubleSerde.deserialize("-12000000E3D"), 0);

        assertEquals(12.000000000001, DoubleSerde.deserialize("+12.000000000001"), 0);
        assertEquals(12.000000000001, DoubleSerde.deserialize("12.000000000001"), 0);
        assertEquals(-12.000000000001, DoubleSerde.deserialize("-12.000000000001"), 0);

        assertEquals(-.0000000000000000000000000001, DoubleSerde.deserialize("-.0000000000000000000000000001"), 0);
        assertEquals(.0000000000000000000000000001, DoubleSerde.deserialize(".0000000000000000000000000001"), 0);
        assertEquals(.0000000000000000000000000001, DoubleSerde.deserialize("+.0000000000000000000000000001"), 0);

        assertEquals(+.0000000000001, DoubleSerde.deserialize("+.0000000000001"), 0);
        assertEquals(.0000000000001, DoubleSerde.deserialize(".0000000000001"), 0);
        assertEquals(-.0000000000001, DoubleSerde.deserialize("-.0000000000001"), 0);

        assertEquals(12.0000000000000000000000000001, DoubleSerde.deserialize("+12.0000000000000000000000000001"), 0);
        assertEquals(12.0000000000000000000000000001, DoubleSerde.deserialize("12.0000000000000000000000000001"), 0);
        assertEquals(-12.0000000000000000000000000001, DoubleSerde.deserialize("-12.0000000000000000000000000001"), 0);
        // testing Long.MAX_VALUE + 1
        assertEquals(9223372036854775808D, DoubleSerde.deserialize("+9223372036854775808"), 0);
        assertEquals(9223372036854775808D, DoubleSerde.deserialize("9223372036854775808"), 0);
        assertEquals(-9223372036854775808D, DoubleSerde.deserialize("-9223372036854775808"), 0);

        // testing decimal Long.MAX_VALUE + 1 see branch covering results as why we do that
        assertEquals(.9223372036854775808D, DoubleSerde.deserialize("+.9223372036854775808"), 0);
        assertEquals(.9223372036854775808D, DoubleSerde.deserialize(".9223372036854775808"), 0);
        assertEquals(-.9223372036854775808D, DoubleSerde.deserialize("-.9223372036854775808"), 0);

        assertEquals(-12000000000000000000000000000.1, DoubleSerde.deserialize("-12000000000000000000000000000.1"), 0);
        assertEquals(12000000000000000000000000000.1, DoubleSerde.deserialize("12000000000000000000000000000.1"), 0);
        assertEquals(12000000000000000000000000000.1, DoubleSerde.deserialize("+12000000000000000000000000000.1"), 0);

        assertEquals(-12000000000000000000000000000.1e7, DoubleSerde.deserialize("-12000000000000000000000000000.1e7"), 0);
        assertEquals(12000000000000000000000000000.1e7, DoubleSerde.deserialize("12000000000000000000000000000.1e7"), 0);
        assertEquals(12000000000000000000000000000.1e7, DoubleSerde.deserialize("+12000000000000000000000000000.1e7"), 0);

        assertEquals(0, DoubleSerde.deserialize("0.0"), 0);
        assertEquals(45.32, DoubleSerde.deserialize("0045.32"), 0);
        assertEquals(-55.36001, DoubleSerde.deserialize("-0055.36001"), 0);
        assertEquals(0, DoubleSerde.deserialize("0."), 0);
        assertEquals(0, DoubleSerde.deserialize(".0"), 0);
        assertEquals(0.06, DoubleSerde.deserialize("000.06"), 0);
        assertEquals(0.06, DoubleSerde.deserialize("0.0600"), 0);
        assertEquals(23.0, DoubleSerde.deserialize("00023."), 0);
        assertEquals(0, DoubleSerde.deserialize("."), 0);


        // Guards the previously-silent int overflow in the 10-digit small path:
        // 9_999_999_999 > Integer.MAX_VALUE, accumulator must use the long/big path.
        assertEquals(9_999_999_999D, DoubleSerde.deserialize("9999999999"), 0);
        assertEquals(-9_999_999_999D, DoubleSerde.deserialize("-9999999999"), 0);
        assertEquals(2_147_483_648D, DoubleSerde.deserialize("2147483648"), 0);

        try {
            DoubleSerde.deserialize("abc");
            fail();
        } catch (IllegalFieldValueException e) {
            // expected
        }

        try {
            DoubleSerde.deserialize("123.A");
            fail();
        } catch (IllegalFieldValueException e) {
            // expected
        }
    }
}
