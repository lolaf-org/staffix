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

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestLongSerde {


    private static void testSerializeDeserialize(long data) {
        byte[] serialized = LongSerde.serialize(data);
        long deserialized = LongSerde.deserialize(serialized);
        Assertions.assertThat(data).isEqualTo(deserialized);
    }

    @Test
    void testLongConvert() {
        Random rdm = new Random();
        for (int i = 0; i < 10 * 1024 * 1024; i++) {
            long data = rdm.nextLong();
            if (Math.random() < 0.5d) {
                data = -data;
            }
            testSerializeDeserialize(data);
        }
        // Special cases
        testSerializeDeserialize(0);
        testSerializeDeserialize(Long.MIN_VALUE);
        testSerializeDeserialize(Long.MAX_VALUE);
    }

    @Test
    void testLongConvertFromString() {
        Random rdm = new Random();
        for (int i = 0; i < 10 * 1024 * 1024; i++) {
            long data = rdm.nextLong();
            if (Math.random() < 0.5d) {
                data = -data;
            }
            Assertions.assertThat(LongSerde.deserialize(Long.toString(data).getBytes())).isEqualTo(data);
        }
        // Special cases
        Assertions.assertThat(LongSerde.deserialize("0".getBytes())).isZero();
        Assertions.assertThat(LongSerde.deserialize(Long.toString(Long.MIN_VALUE).getBytes())).isEqualTo(Long.MIN_VALUE);
        Assertions.assertThat(LongSerde.deserialize(Long.toString(Long.MAX_VALUE).getBytes())).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void testDeserializeUnsigned() {
        long[] values = {0, 1, 7, 42, 100, 999, 65535, 65536, 1000000, 999999999L, 1234567890L, 9999999999999999L, Long.MAX_VALUE};
        for (long value : values) {
            byte[] bytes = Long.toString(value).getBytes(StandardCharsets.US_ASCII);
            assertThat(LongSerde.deserializeUnsigned(bytes, 0, bytes.length))
                    .as("value=" + value)
                    .isEqualTo(value);
        }
    }

    /**
     * Exercises the unrolled length-2/3/4 fast paths of {@code deserializeUnsigned}, including leading zeros and
     * non-zero start offsets, so the specialization stays equivalent to the generic accumulator loop.
     */
    @Test
    void testDeserializeUnsignedFixedLengthArms() {
        assertThat(LongSerde.deserializeUnsigned("00".getBytes(StandardCharsets.US_ASCII), 0, 2)).isZero();
        assertThat(LongSerde.deserializeUnsigned("42".getBytes(StandardCharsets.US_ASCII), 0, 2)).isEqualTo(42);
        assertThat(LongSerde.deserializeUnsigned("099".getBytes(StandardCharsets.US_ASCII), 0, 3)).isEqualTo(99);
        assertThat(LongSerde.deserializeUnsigned("789".getBytes(StandardCharsets.US_ASCII), 0, 3)).isEqualTo(789);
        assertThat(LongSerde.deserializeUnsigned("0007".getBytes(StandardCharsets.US_ASCII), 0, 4)).isEqualTo(7);
        assertThat(LongSerde.deserializeUnsigned("6789".getBytes(StandardCharsets.US_ASCII), 0, 4)).isEqualTo(6789);

        // Sub-range reads at a non-zero offset (mirrors "TAG=value" parsing).
        byte[] buffer = "34=6789|".getBytes(StandardCharsets.US_ASCII);
        assertThat(LongSerde.deserializeUnsigned(buffer, 3, 4)).isEqualTo(6789);
        assertThat(LongSerde.deserializeUnsigned(buffer, 3, 2)).isEqualTo(67);
        assertThat(LongSerde.deserializeUnsigned(buffer, 4, 3)).isEqualTo(789);
    }

    @Test
    void testDeserializeUnsignedRejectsNonDigitInEachArm() {
        String[] malformed = {"4x", "x2", "7x9", "x89", "67x9", "678x", "x789"};
        for (String bad : malformed) {
            byte[] bytes = bad.getBytes(StandardCharsets.US_ASCII);
            assertThatThrownBy(() -> LongSerde.deserializeUnsigned(bytes, 0, bytes.length))
                    .as("input=" + bad)
                    .isInstanceOf(IllegalFieldValueException.class);
        }
    }

    @Test
    void testDeserializeAcceptsLeadingPlusSign() {
        assertThat(LongSerde.deserialize("+0".getBytes(StandardCharsets.US_ASCII))).isZero();
        assertThat(LongSerde.deserialize("+7".getBytes(StandardCharsets.US_ASCII))).isEqualTo(7);
        assertThat(LongSerde.deserialize("+9223372036854775807".getBytes(StandardCharsets.US_ASCII))).isEqualTo(Long.MAX_VALUE);
    }
}