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

import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

class TestUtcTimeOnlySerde {

    @Test
    void testDeserializeGarbage() {
        try {
            UtcTimeOnlySerde.deserializeNanoOfDay("test".getBytes(), 0, "test".length());
            Assertions.fail("should have failed");
        } catch (IllegalFieldValueException ex) {
            Assertions.assertThat(ex.getMessage()).isEqualTo("Invalid UTC time: test");
        }
        try {
            UtcTimeOnlySerde.deserializeNanoOfDay("a3:12:10".getBytes(), 0, "a3:12:10".length());
            Assertions.fail("should have failed");
        } catch (IllegalFieldValueException ex) {
            Assertions.assertThat(ex.getMessage()).isEqualTo("a is not a number");
        }
    }

    @Test
    void testDeserializeUtcTimeOnly() {
        long nanoOfDay = UtcTimeOnlySerde.deserializeNanoOfDay("23:12".getBytes(), 0, 5);
        Assertions.assertThat(nanoOfDay).isEqualTo(83520000000000L);
        Assertions.assertThat(UtcTimeOnlySerde.toLocalUTCTime(nanoOfDay)).isEqualTo(LocalTime.of(23, 12, 0));
        Assertions.assertThat(UtcTimeOnlySerde.getHour(nanoOfDay)).isEqualTo(23);
        Assertions.assertThat(UtcTimeOnlySerde.getMinute(nanoOfDay)).isEqualTo(12);
        Assertions.assertThat(UtcTimeOnlySerde.getSeconds(nanoOfDay)).isZero();
        Assertions.assertThat(UtcTimeOnlySerde.getNanos(nanoOfDay)).isZero();
        Assertions.assertThat(UtcTimeOnlySerde.deserializeNanoOfDay("00:00".getBytes(), 0, 5)).isZero();

        nanoOfDay = UtcTimeOnlySerde.deserializeNanoOfDay("23:12:10".getBytes(), 0, 8);
        Assertions.assertThat(nanoOfDay).isEqualTo(83530000000000L);
        Assertions.assertThat(UtcTimeOnlySerde.toLocalUTCTime(nanoOfDay)).isEqualTo(LocalTime.of(23, 12, 10));
        Assertions.assertThat(UtcTimeOnlySerde.getHour(nanoOfDay)).isEqualTo(23);
        Assertions.assertThat(UtcTimeOnlySerde.getMinute(nanoOfDay)).isEqualTo(12);
        Assertions.assertThat(UtcTimeOnlySerde.getSeconds(nanoOfDay)).isEqualTo(10);
        Assertions.assertThat(UtcTimeOnlySerde.getNanos(nanoOfDay)).isZero();
        Assertions.assertThat(UtcTimeOnlySerde.deserializeNanoOfDay("00:00:00".getBytes(), 0, 8)).isZero();

        nanoOfDay = UtcTimeOnlySerde.deserializeNanoOfDay("23:12:10.230".getBytes(), 0, 12);
        Assertions.assertThat(nanoOfDay).isEqualTo(83530230000000L);
        Assertions.assertThat(UtcTimeOnlySerde.toLocalUTCTime(nanoOfDay)).isEqualTo(LocalTime.of(23, 12, 10).plusNanos(230000000));
        Assertions.assertThat(UtcTimeOnlySerde.getHour(nanoOfDay)).isEqualTo(23);
        Assertions.assertThat(UtcTimeOnlySerde.getMinute(nanoOfDay)).isEqualTo(12);
        Assertions.assertThat(UtcTimeOnlySerde.getSeconds(nanoOfDay)).isEqualTo(10);
        Assertions.assertThat(UtcTimeOnlySerde.getNanos(nanoOfDay)).isEqualTo(230000000L);
        Assertions.assertThat(UtcTimeOnlySerde.deserializeNanoOfDay("00:00:00.230".getBytes(), 0, 12)).isEqualTo(230000000L);

        nanoOfDay = UtcTimeOnlySerde.deserializeNanoOfDay("23:12:10.230230".getBytes(), 0, 15);
        Assertions.assertThat(nanoOfDay).isEqualTo(83530230230000L);
        Assertions.assertThat(UtcTimeOnlySerde.toLocalUTCTime(nanoOfDay)).isEqualTo(LocalTime.of(23, 12, 10).plusNanos(230230000));
        Assertions.assertThat(UtcTimeOnlySerde.getHour(nanoOfDay)).isEqualTo(23);
        Assertions.assertThat(UtcTimeOnlySerde.getMinute(nanoOfDay)).isEqualTo(12);
        Assertions.assertThat(UtcTimeOnlySerde.getSeconds(nanoOfDay)).isEqualTo(10);
        Assertions.assertThat(UtcTimeOnlySerde.getNanos(nanoOfDay)).isEqualTo(230230000);
        Assertions.assertThat(UtcTimeOnlySerde.deserializeNanoOfDay("00:00:00.230230".getBytes(), 0, 15)).isEqualTo(230230000L);

        nanoOfDay = UtcTimeOnlySerde.deserializeNanoOfDay("23:12:10.230230230".getBytes(), 0, 18);
        Assertions.assertThat(nanoOfDay).isEqualTo(83530230230230L);
        Assertions.assertThat(UtcTimeOnlySerde.toLocalUTCTime(nanoOfDay)).isEqualTo(LocalTime.of(23, 12, 10).plusNanos(230230230));
        Assertions.assertThat(UtcTimeOnlySerde.getHour(nanoOfDay)).isEqualTo(23);
        Assertions.assertThat(UtcTimeOnlySerde.getMinute(nanoOfDay)).isEqualTo(12);
        Assertions.assertThat(UtcTimeOnlySerde.getSeconds(nanoOfDay)).isEqualTo(10);
        Assertions.assertThat(UtcTimeOnlySerde.getNanos(nanoOfDay)).isEqualTo(230230230);
        Assertions.assertThat(UtcTimeOnlySerde.deserializeNanoOfDay("00:00:00.230230230".getBytes(), 0, 18)).isEqualTo(230230230);
    }

    @Test
    void testSerializeUtcTimeOnly() {
        byte[] serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(9, 9, 9, (int) TimeUnit.MILLISECONDS.toNanos(230)).toNanoOfDay(), TimeUnit.SECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("09:09:09");

        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, (int) TimeUnit.MILLISECONDS.toNanos(9)).toNanoOfDay(), TimeUnit.MILLISECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.009");
        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, (int) TimeUnit.MILLISECONDS.toNanos(99)).toNanoOfDay(), TimeUnit.MILLISECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.099");
        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, (int) TimeUnit.MILLISECONDS.toNanos(999)).toNanoOfDay(), TimeUnit.MILLISECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.999");

        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, (int) TimeUnit.MICROSECONDS.toNanos(9)).toNanoOfDay(), TimeUnit.MICROSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.000009");
        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, (int) TimeUnit.MICROSECONDS.toNanos(99)).toNanoOfDay(), TimeUnit.MICROSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.000099");
        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, (int) TimeUnit.MICROSECONDS.toNanos(999)).toNanoOfDay(), TimeUnit.MICROSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.000999");
        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, (int) TimeUnit.MICROSECONDS.toNanos(9999)).toNanoOfDay(), TimeUnit.MICROSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.009999");
        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, (int) TimeUnit.MICROSECONDS.toNanos(99999)).toNanoOfDay(), TimeUnit.MICROSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.099999");
        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, (int) TimeUnit.MICROSECONDS.toNanos(999999)).toNanoOfDay(), TimeUnit.MICROSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.999999");


        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, 9).toNanoOfDay(), TimeUnit.NANOSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.000000009");
        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, 99).toNanoOfDay(), TimeUnit.NANOSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.000000099");
        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, 999).toNanoOfDay(), TimeUnit.NANOSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.000000999");
        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, 9999).toNanoOfDay(), TimeUnit.NANOSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.000009999");
        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, 99999).toNanoOfDay(), TimeUnit.NANOSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.000099999");
        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, 999999).toNanoOfDay(), TimeUnit.NANOSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.000999999");
        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, 9999999).toNanoOfDay(), TimeUnit.NANOSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.009999999");
        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, 99999999).toNanoOfDay(), TimeUnit.NANOSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.099999999");
        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, 999999999).toNanoOfDay(), TimeUnit.NANOSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.999999999");

        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10, (int) TimeUnit.MILLISECONDS.toNanos(9)), TimeUnit.MILLISECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10.009");

        serialized = UtcTimeOnlySerde.serializeForPrecision(LocalTime.of(23, 12, 10), TimeUnit.SECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("23:12:10");
    }
}
