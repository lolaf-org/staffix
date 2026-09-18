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

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;

import java.math.BigDecimal;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestBigDecimalSerde {

    private final BigDecimalSerde serde = BigDecimalSerde.instance();

    @Test
    void testDeserialize() {
        SerDe.DeserializationContext ctx = new TestingDeserializationContext();
        String numberToDeserialize = "1.123456789";
        ctx.setup(numberToDeserialize.getBytes());

        BigDecimal deserialized = serde.deserialize(ctx);
        Assertions.assertThat(deserialized.toPlainString()).isEqualTo(numberToDeserialize);
    }

    @Test
    void testSerialize() {
        BigDecimal numberToSerialize = new BigDecimal("1.123456789");

        ByteBuffer out = ByteBuffer.allocate(20);
        serde.serialize(out, numberToSerialize);

        byte[] value = new byte[out.position()];
        out.flip().get(value);
        Assertions.assertThat(new String(value)).isEqualTo(numberToSerialize.toPlainString());
    }

    @Test
    void testDeserializeWithNonZeroStartOffset() {
        // Guards against the previously-silent loop-bounds bug: startOffset was not
        // added to the loop end, so bytes past the prefix were skipped and stale
        // cache data corrupted the parsed value.
        byte[] payload = "XX123.45YY".getBytes();
        SerDe.DeserializationContext ctx = new TestingDeserializationContext();
        ctx.setup(payload, 2, 6);

        BigDecimal parsed = serde.deserialize(ctx);
        Assertions.assertThat(parsed).isEqualByComparingTo(new BigDecimal("123.45"));

        // Second call with a shorter value on the same thread: cached char[] still
        // holds remnants from the first call. Correct implementation must only read
        // the first `length` chars.
        ctx.setup("7.5".getBytes(), 0, 3);
        Assertions.assertThat(serde.deserialize(ctx)).isEqualByComparingTo(new BigDecimal("7.5"));
    }

    @Test
    void testDeserializeFixShapes() {
        // Leading zeros: "00023.23" = "23.23".
        Assertions.assertThat(deserialize("00023.23")).isEqualByComparingTo(new BigDecimal("23.23"));
        // Trailing zeros after the decimal point preserved in scale.
        Assertions.assertThat(deserialize("23.0000").toPlainString()).isEqualTo("23.0000");
        // Integer form (no decimal point).
        Assertions.assertThat(deserialize("42")).isEqualByComparingTo(new BigDecimal("42"));
        // Negative.
        Assertions.assertThat(deserialize("-123.45")).isEqualByComparingTo(new BigDecimal("-123.45"));
        // Leading positive sign (accepted as leniency).
        Assertions.assertThat(deserialize("+42")).isEqualByComparingTo(new BigDecimal("42"));
        // Exactly 15 significant digits (boundary, accepted).
        Assertions.assertThat(deserialize("999999999999999")).isEqualByComparingTo(new BigDecimal("999999999999999"));
        Assertions.assertThat(deserialize("99999999999.9999")).isEqualByComparingTo(new BigDecimal("99999999999.9999"));
    }

    @Test
    void testDeserializeRejectsMalformed() {
        // Scientific notation: not in the FIX float alphabet.
        assertThatThrownBy(() -> deserialize("1e3")).isInstanceOf(IllegalFieldValueException.class);
        assertThatThrownBy(() -> deserialize("1.5E-2")).isInstanceOf(IllegalFieldValueException.class);
        // Non-numeric characters.
        assertThatThrownBy(() -> deserialize("abc")).isInstanceOf(IllegalFieldValueException.class);
        // Multiple decimal points.
        assertThatThrownBy(() -> deserialize("1.2.3")).isInstanceOf(IllegalFieldValueException.class);
        // Empty and sign-only.
        assertThatThrownBy(() -> deserialize("")).isInstanceOf(IllegalFieldValueException.class);
        assertThatThrownBy(() -> deserialize("+")).isInstanceOf(IllegalFieldValueException.class);
        assertThatThrownBy(() -> deserialize("-")).isInstanceOf(IllegalFieldValueException.class);
        // 16 significant digits: exceeds FIX precision.
        assertThatThrownBy(() -> deserialize("1234567890123456")).isInstanceOf(IllegalFieldValueException.class);
        // Trailing zeros do not contribute to precision per FIX spec — these are accepted.
        Assertions.assertThat(deserialize("1.00000000000000000")).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void testSerializeRejectsExcessiveSignificantDigits() {
        // 16 significant digits.
        assertThatThrownBy(() -> roundTripSerialize(new BigDecimal("1234567890123456")))
                .isInstanceOf(IllegalFieldValueException.class);
        assertThatThrownBy(() -> roundTripSerialize(new BigDecimal("0.12345678901234567")))
                .isInstanceOf(IllegalFieldValueException.class);
        // Trailing zeros are not significant — value still has only 1 sig digit after strip.
        Assertions.assertThat(roundTripSerialize(new BigDecimal("1000000000000000000"))).isEqualTo("1000000000000000000");

        Assertions.assertThat(deserialize("10000000000000100")).isEqualByComparingTo(new BigDecimal("10000000000000100"));
        // "underflow" magnitudes are now legal: 1e-16 has 1 sig digit.
        Assertions.assertThat(deserialize("0.0000000000000001")).isEqualByComparingTo(new BigDecimal("0.0000000000000001"));
    }

    @Test
    void testSerializeBasics() {
        Assertions.assertThat(roundTripSerialize(new BigDecimal("0"))).isEqualTo("0");
        Assertions.assertThat(roundTripSerialize(new BigDecimal("-123.45"))).isEqualTo("-123.45");
        Assertions.assertThat(roundTripSerialize(new BigDecimal("23.0000"))).isEqualTo("23.0000");
        Assertions.assertThat(roundTripSerialize(new BigDecimal("999999999999999"))).isEqualTo("999999999999999");
    }

    private BigDecimal deserialize(String input) {
        SerDe.DeserializationContext ctx = new TestingDeserializationContext();
        ctx.setup(input.getBytes());
        return serde.deserialize(ctx);
    }

    private String roundTripSerialize(BigDecimal value) {
        ByteBuffer out = ByteBuffer.allocate(64);
        serde.serialize(out, value);
        byte[] bytes = new byte[out.position()];
        out.flip().get(bytes);
        return new String(bytes);
    }
}
