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


import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestIntSerde {


    private static void testSerializeDeserialize(int data) {
        byte[] serialized = IntSerde.serialize(data);
        int deserialized = IntSerde.deserialize(serialized);
        Assertions.assertThat(data).isEqualTo(deserialized);
    }

    private static void assertSerializeAtOffset(int value, int startOffset) {
        byte[] expected = Integer.toString(value).getBytes(StandardCharsets.US_ASCII);
        int size = expected.length;
        byte[] buffer = new byte[startOffset + size + 3];
        Arrays.fill(buffer, (byte) '.');

        IntSerde.serialize(value, buffer, size, startOffset);

        String context = "value=" + value + " startOffset=" + startOffset;
        // Target region holds the exact decimal representation.
        assertThat(Arrays.copyOfRange(buffer, startOffset, startOffset + size))
                .as(context)
                .isEqualTo(expected);
        // Nothing before the target slice was touched (guards against the sign-offset bug).
        for (int i = 0; i < startOffset; i++) {
            assertThat(buffer[i]).as(context + " leading index " + i).isEqualTo((byte) '.');
        }
        // Nothing after the target slice was touched.
        for (int i = startOffset + size; i < buffer.length; i++) {
            assertThat(buffer[i]).as(context + " trailing index " + i).isEqualTo((byte) '.');
        }
    }

    @Test
    void testIntConvert() {
        for (int i = 0; i < 10 * 1024 * 1024; i++) {
            int data = (int) (Math.floor(Math.random() * Integer.MAX_VALUE));
            if (Math.random() < 0.5d) {
                data = -data;
            }
            testSerializeDeserialize(data);
        }
        // Special cases
        testSerializeDeserialize(0);
        testSerializeDeserialize(Integer.MIN_VALUE);
        testSerializeDeserialize(Integer.MAX_VALUE);
    }

    @Test
    void testIntConvertFromString() {
        for (int i = 0; i < 10 * 1024 * 1024; i++) {
            int data = (int) (Math.floor(Math.random() * Integer.MAX_VALUE));
            if (Math.random() < 0.5d) {
                data = -data;
            }
            Assertions.assertThat(IntSerde.deserialize(Integer.toString(data).getBytes())).isEqualTo(data);
        }
        // Special cases
        Assertions.assertThat(IntSerde.deserialize("0".getBytes())).isZero();
        Assertions.assertThat(IntSerde.deserialize(Integer.toString(Integer.MIN_VALUE).getBytes())).isEqualTo(Integer.MIN_VALUE);
        Assertions.assertThat(IntSerde.deserialize(Integer.toString(Integer.MAX_VALUE).getBytes())).isEqualTo(Integer.MAX_VALUE);
    }

    /**
     * Serializing into a slice of a larger buffer must write exactly the {@code [startOffset, startOffset + size)}
     * region and touch nothing else — including the sign byte for negative values. This pins the fix for the
     * sign-offset defect where the '-' was written at index 0 instead of {@code startOffset}.
     */
    @Test
    void testSerializeIntoBufferAtOffset() {
        int[] values = {0, 7, 42, 999, 65535, 65536, 1234567, 2147483647, -1, -5, -42, -65536, -98765, Integer.MIN_VALUE};
        int[] offsets = {0, 1, 3, 8};
        for (int value : values) {
            for (int offset : offsets) {
                assertSerializeAtOffset(value, offset);
            }
        }
    }

    @Test
    void testDeserializeUnsigned() {
        int[] values = {0, 1, 7, 42, 100, 999, 65535, 65536, 1000000, 999999999, 1234567890, Integer.MAX_VALUE};
        for (int value : values) {
            byte[] bytes = Integer.toString(value).getBytes(StandardCharsets.US_ASCII);
            assertThat(IntSerde.deserializeUnsigned(bytes, 0, bytes.length))
                    .as("value=" + value)
                    .isEqualTo(value);
        }
    }

    /**
     * {@code deserializeUnsigned} is called on a sub-range of a larger buffer in the hot parsing path
     * (e.g. a field tag embedded in the message bytes); reading must respect {@code startIndex}/{@code len}.
     */
    @Test
    void testDeserializeUnsignedFromSubRange() {
        byte[] buffer = "junk123=morejunk".getBytes(StandardCharsets.US_ASCII);
        assertThat(IntSerde.deserializeUnsigned(buffer, 4, 3)).isEqualTo(123);
    }

    /**
     * Exercises the unrolled length-2/3/4 fast paths of {@code deserializeUnsigned}, including leading zeros and
     * non-zero start offsets, so the specialization stays equivalent to the generic accumulator loop.
     */
    @Test
    void testDeserializeUnsignedFixedLengthArms() {
        assertThat(IntSerde.deserializeUnsigned("00".getBytes(StandardCharsets.US_ASCII), 0, 2)).isZero();
        assertThat(IntSerde.deserializeUnsigned("42".getBytes(StandardCharsets.US_ASCII), 0, 2)).isEqualTo(42);
        assertThat(IntSerde.deserializeUnsigned("099".getBytes(StandardCharsets.US_ASCII), 0, 3)).isEqualTo(99);
        assertThat(IntSerde.deserializeUnsigned("789".getBytes(StandardCharsets.US_ASCII), 0, 3)).isEqualTo(789);
        assertThat(IntSerde.deserializeUnsigned("0007".getBytes(StandardCharsets.US_ASCII), 0, 4)).isEqualTo(7);
        assertThat(IntSerde.deserializeUnsigned("6789".getBytes(StandardCharsets.US_ASCII), 0, 4)).isEqualTo(6789);

        // Sub-range reads at a non-zero offset (mirrors "TAG=value" parsing).
        byte[] buffer = "TAG=6789|".getBytes(StandardCharsets.US_ASCII);
        assertThat(IntSerde.deserializeUnsigned(buffer, 4, 4)).isEqualTo(6789);
        assertThat(IntSerde.deserializeUnsigned(buffer, 4, 2)).isEqualTo(67);
        assertThat(IntSerde.deserializeUnsigned(buffer, 5, 3)).isEqualTo(789);
    }

    /**
     * The unrolled arms must validate every digit position exactly like the loop path — a non-digit anywhere
     * must still be rejected.
     */
    @Test
    void testDeserializeUnsignedRejectsNonDigitInEachArm() {
        String[] malformed = {"4x", "x2", "7x9", "x89", "67x9", "678x", "x789"};
        for (String bad : malformed) {
            byte[] bytes = bad.getBytes(StandardCharsets.US_ASCII);
            assertThatThrownBy(() -> IntSerde.deserializeUnsigned(bytes, 0, bytes.length))
                    .as("input=" + bad)
                    .isInstanceOf(IllegalFieldValueException.class);
        }
    }

    @Test
    void testDeserializeAcceptsLeadingPlusSign() {
        assertThat(IntSerde.deserialize("+0".getBytes(StandardCharsets.US_ASCII))).isZero();
        assertThat(IntSerde.deserialize("+7".getBytes(StandardCharsets.US_ASCII))).isEqualTo(7);
        assertThat(IntSerde.deserialize("+2147483647".getBytes(StandardCharsets.US_ASCII))).isEqualTo(Integer.MAX_VALUE);
    }

    /**
     * Round-trips one value at every decimal length (1..10 digits) plus the signed boundaries, so a change to
     * the digit-generation algorithm cannot silently regress a specific magnitude band.
     */
    @Test
    void testEachDigitLength() {
        int value = 1;
        for (int digits = 1; digits <= 9; digits++) {
            testSerializeDeserialize(value);
            testSerializeDeserialize(-value);
            value *= 10;
        }
        testSerializeDeserialize(Integer.MAX_VALUE); // 10 digits
        testSerializeDeserialize(Integer.MIN_VALUE); // 10 digits + sign
    }

    /**
     * Pins the current {@code getPositiveIntSize} contract before it is rewritten as a branch-free O(1) formula.
     */
    @Test
    void testGetPositiveIntSize() {
        assertThat(IntSerde.getPositiveIntSize(0)).isEqualTo(1);
        assertThat(IntSerde.getPositiveIntSize(9)).isEqualTo(1);
        assertThat(IntSerde.getPositiveIntSize(10)).isEqualTo(2);
        assertThat(IntSerde.getPositiveIntSize(99)).isEqualTo(2);
        assertThat(IntSerde.getPositiveIntSize(100)).isEqualTo(3);
        assertThat(IntSerde.getPositiveIntSize(999)).isEqualTo(3);
        assertThat(IntSerde.getPositiveIntSize(1000)).isEqualTo(4);
        assertThat(IntSerde.getPositiveIntSize(999999999)).isEqualTo(9);
        assertThat(IntSerde.getPositiveIntSize(1000000000)).isEqualTo(10);
        assertThat(IntSerde.getPositiveIntSize(Integer.MAX_VALUE)).isEqualTo(10);
    }

    @Test
    void testCachedSerializeMatchesSerialize() {
        int[] values = {0, 7, 42, 65536, 1234567, 2147483647, -1, -98765, Integer.MIN_VALUE};
        for (int value : values) {
            assertThat(IntSerde.cachedSerialize(value))
                    .as("value=" + value)
                    .isEqualTo(Integer.toString(value).getBytes(StandardCharsets.US_ASCII));
        }
    }
}
