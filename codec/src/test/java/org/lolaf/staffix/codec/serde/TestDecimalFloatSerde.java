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
import org.lolaf.staffix.api.serde.DecimalFloat;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TestDecimalFloatSerde {

    private final DecimalFloatSerde serde = DecimalFloatSerde.instance();

    @Test
    void testDeserializeBasics() {
        assertEquals(DecimalFloat.of(0, (byte) 0), deserialize("0"));
        assertEquals(DecimalFloat.of(123, (byte) 0), deserialize("123"));
        assertEquals(DecimalFloat.of(-123, (byte) 0), deserialize("-123"));
        assertEquals(DecimalFloat.of(42, (byte) 0), deserialize("+42"));
        assertEquals(DecimalFloat.of(12345, (byte) 2), deserialize("123.45"));
        assertEquals(DecimalFloat.of(-1, (byte) 3), deserialize("-0.001"));
        assertEquals(DecimalFloat.of(5, (byte) 1), deserialize(".5"));
        assertEquals(DecimalFloat.of(0, (byte) 1), deserialize("0.0"));
        assertEquals(DecimalFloat.of(12345, (byte) 2), deserialize("00123.45"));
        // Trailing zeros in the fractional part are preserved as scale digits.
        assertEquals(DecimalFloat.of(12300, (byte) 4), deserialize("1.2300"));
    }

    @Test
    void testDeserializeBoundaries() {
        // Largest in-range magnitude: 10^15 - 1 (15 nines).
        assertEquals(DecimalFloat.of(999_999_999_999_999L, (byte) 0), deserialize("999999999999999"));
        assertEquals(DecimalFloat.of(-999_999_999_999_999L, (byte) 0), deserialize("-999999999999999"));
    }

    @Test
    void testDeserializeRejectsMalformed() {
        assertThatThrownBy(() -> deserialize("abc")).isInstanceOf(IllegalFieldValueException.class);
        assertThatThrownBy(() -> deserialize("1.2.3")).isInstanceOf(IllegalFieldValueException.class);
        // Scientific notation is not supported for fixed-point DecimalFloat.
        assertThatThrownBy(() -> deserialize("1e3")).isInstanceOf(IllegalFieldValueException.class);
        assertThatThrownBy(() -> deserialize("")).isInstanceOf(IllegalFieldValueException.class);
        assertThatThrownBy(() -> deserialize("+")).isInstanceOf(IllegalFieldValueException.class);
        // 16 significant digits: exceeds the 15-significant-digit FIX float precision.
        assertThatThrownBy(() -> deserialize("1000000000000001"))
                .isInstanceOf(IllegalFieldValueException.class);
        assertThatThrownBy(() -> deserialize("-1000000000000001"))
                .isInstanceOf(IllegalFieldValueException.class);
        assertThatThrownBy(() -> deserialize("9999999999999999"))
                .isInstanceOf(IllegalFieldValueException.class);
        // Long overflow (> Long.MAX_VALUE) — 20 nines = 20 sig digits.
        assertThatThrownBy(() -> deserialize("99999999999999999999"))
                .isInstanceOf(IllegalFieldValueException.class);
        // Non-zero digit after reaching the precision cap: still a sig-digit violation.
        assertThatThrownBy(() -> deserialize("100000000000000100"))
                .isInstanceOf(IllegalFieldValueException.class);

        // scale is max 127
        assertThatThrownBy(() -> deserialize("0." + "0".repeat(128))).isInstanceOf(IllegalFieldValueException.class);
        // scale is min 128 (-128-14) = 143
        assertThatThrownBy(() -> deserialize(("1" + "0".repeat(143)))).isInstanceOf(IllegalFieldValueException.class);
    }

    @Test
    void testDeserializeReadingALenience() {
        // 1 followed by 17 zeros (value 10^17, 1 sig digit)
        assertEquals(DecimalFloat.of(100_000_000_000_000_000L, (byte) 0),
                deserialize("100000000000000000"));
    }

    @Test
    void testSerializeBasics() {
        assertEquals("0", serialize(DecimalFloat.of(0, (byte) 0)));
        assertEquals("123.45", serialize(DecimalFloat.of(12345, (byte) 2)));
        assertEquals("-123.45", serialize(DecimalFloat.of(-12345, (byte) 2)));
        assertEquals("12000", serialize(DecimalFloat.of(12, (byte) -3)));
        assertEquals("100000", serialize(DecimalFloat.of(1L, (byte) -5)));
        assertEquals("0.00001", serialize(DecimalFloat.of(1, (byte) 5)));
        assertEquals("0.1", serialize(DecimalFloat.of(1, (byte) 1)));
        assertEquals("-0.1", serialize(DecimalFloat.of(-1, (byte) 1)));
        assertEquals("1.0", serialize(DecimalFloat.of(10, (byte) 1)));
        // Zero retains scale as trailing zeros in the fractional part.
        assertEquals("0.000", serialize(DecimalFloat.of(0, (byte) 3)));
    }

    @Test
    void testSerializeBoundaries() {
        assertEquals("9999999999999990", serialize(DecimalFloat.of(999_999_999_999_9990L, (byte) 0)));
        assertEquals("999999999999999", serialize(DecimalFloat.of(999_999_999_999_999L, (byte) 0)));
        assertEquals("-999999999999999", serialize(DecimalFloat.of(-999_999_999_999_999L, (byte) 0)));
    }

    @Test
    void testUnscaledValueRangeCheck() {
        // Reading A: (10^15, 0) has only 1 significant digit after stripping trailing zeros.
        // Accepted; normalized to (10^14, -1) since |10^15| is not strictly less than 10^15.
        DecimalFloat normalizedPositive =
                DecimalFloat.of(1_000_000_000_000_000L, (byte) 0);
        assertEquals(100_000_000_000_000L, normalizedPositive.getUnscaledValue());
        assertEquals(-1, normalizedPositive.getScale());
        DecimalFloat normalizedNegative =
                DecimalFloat.of(-1_000_000_000_000_000L, (byte) 0);
        assertEquals(-100_000_000_000_000L, normalizedNegative.getUnscaledValue());
        assertEquals(-1, normalizedNegative.getScale());
        // (10^16, 0) strips two trailing zeros -> (10^14, -2).
        DecimalFloat tenToSixteen =
                DecimalFloat.of(10_000_000_000_000_000L, (byte) 0);
        assertEquals(100_000_000_000_000L, tenToSixteen.getUnscaledValue());
        assertEquals(-2, tenToSixteen.getScale());

        // Inputs whose stripped form still has >15 sig digits are rejected.
        assertThatThrownBy(() -> DecimalFloat.of(1_000_000_000_000_001L, (byte) 0))
                .isInstanceOf(IllegalFieldValueException.class);
        assertThatThrownBy(() -> DecimalFloat.of(Long.MAX_VALUE, (byte) 0))
                .isInstanceOf(IllegalFieldValueException.class);
        assertThatThrownBy(() -> DecimalFloat.of(Long.MIN_VALUE, (byte) 0))
                .isInstanceOf(IllegalFieldValueException.class);
        // Bounds are exclusive at 10^15 — 10^15 - 1 is accepted as-is (no trailing zero to strip).
        assertEquals(999_999_999_999_999L, DecimalFloat.of(999_999_999_999_999L, (byte) 0).getUnscaledValue());
        assertEquals(-999_999_999_999_999L, DecimalFloat.of(-999_999_999_999_999L, (byte) 0).getUnscaledValue());
    }

    @Test
    void testRandomRoundTrip() {
        // For round-trip equality we need non-zero unscaled values and non-negative scale
        // (negative scale loses its identity through the ASCII wire form: "12000" has no
        // record of whether it came from (12, -3) or (12000, 0)).
        Random rdm = new Random(42);
        for (int i = 0; i < 1_000_000; i++) {
            long unscaled;
            do {
                // Bounded to |unscaled| < 10^15 (FIX 15-significant-digit limit).
                unscaled = (rdm.nextLong() % DecimalFloat.MAX_UNSCALED_MAGNITUDE);
            } while (unscaled == 0);
            int scale = rdm.nextInt(128); // [0, 127]
            DecimalFloat original = DecimalFloat.of(unscaled, (byte) scale);

            String wire = serialize(original);
            DecimalFloat roundTripped = deserialize(wire);

            assertThat(roundTripped).as("round-trip via '%s'", wire).isEqualTo(original);
        }
    }

    private DecimalFloat deserialize(String s) {
        SerDe.DeserializationContext ctx = new TestingDeserializationContext();
        ctx.setup(s.getBytes());
        return serde.deserialize(ctx);
    }

    private String serialize(DecimalFloat value) {
        // Max emitted length: "-" + 20 digits + 127 fractional zeros ≈ 148 bytes.
        ByteBuffer out = ByteBuffer.allocate(256);
        serde.serialize(out, value);
        byte[] bytes = new byte[out.position()];
        out.flip().get(bytes);
        return new String(bytes);
    }
}
