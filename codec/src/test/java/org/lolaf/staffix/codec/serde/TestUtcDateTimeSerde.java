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
import org.lolaf.staffix.api.time.UTCTime;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

class TestUtcDateTimeSerde {

    @Test
    void testDeserializeGarbage() {
        try {
            UtcDateTimeSerde.deserialize("garbage".getBytes(), 0, "garbage".length());
            Assertions.fail("should have failed");
        } catch (IllegalFieldValueException ex) {
            Assertions.assertThat(ex.getMessage()).isEqualTo("Invalid UTC datetime: garbage");
        }
        try {
            UtcDateTimeSerde.deserialize("20241224-00:00:01.a30".getBytes(), 0, "20241224-00:00:01.a30".length());
            Assertions.fail("should have failed");
        } catch (IllegalFieldValueException ex) {
            Assertions.assertThat(ex.getMessage()).isEqualTo("a is not a number");
        }
    }

    @Test
    void testUtcDateTimeToEpochMillis() {
        UTCTime utcTime = UtcDateTimeSerde.deserialize("20241224-00:01:01".getBytes(), 0, 17);

        Instant instant = utcTime.asInstant();

        Assertions.assertThat(instant.toEpochMilli()).isEqualTo(utcTime.toEpochMillis());
    }

    @Test
    void testDeserializeLeapUtcDateTime() {
        UTCTime utcTime = UtcDateTimeSerde.deserialize("19981231-23:59:60".getBytes(), 0, 17);
        Assertions.assertThat(utcTime.getEpochDays()).isEqualTo(10591L);
        Assertions.assertThat(utcTime.getEpochSeconds()).isEqualTo(915148800L);
        Assertions.assertThat(utcTime.getNanosOfSecond()).isZero();
        Assertions.assertThat(utcTime.asInstant()).hasToString("1999-01-01T00:00:00Z");
    }

    @Test
    void testDeserializeUtcDateTime() {
        UTCTime utcTime = UtcDateTimeSerde.deserialize("20241224-00:01:01".getBytes(), 0, 17);
        Assertions.assertThat(utcTime.getEpochDays()).isEqualTo(20081L);
        Assertions.assertThat(utcTime.getEpochSeconds()).isEqualTo(1734998461L);
        Assertions.assertThat(utcTime.getNanosOfSecond()).isZero();
        Assertions.assertThat(utcTime.asInstant()).hasToString("2024-12-24T00:01:01Z");

        utcTime = UtcDateTimeSerde.deserialize("20241224-00:00:01.230".getBytes(), 0, 21);
        Assertions.assertThat(utcTime.getEpochDays()).isEqualTo(20081L);
        Assertions.assertThat(utcTime.getEpochSeconds()).isEqualTo(1734998401L);
        Assertions.assertThat(utcTime.getNanosOfSecond()).isEqualTo(230000000);
        Assertions.assertThat(utcTime.asInstant()).hasToString("2024-12-24T00:00:01.230Z");

        utcTime = UtcDateTimeSerde.deserialize("20241224-00:00:02.230230".getBytes(), 0, 24);
        Assertions.assertThat(utcTime.getEpochDays()).isEqualTo(20081L);
        Assertions.assertThat(utcTime.getEpochSeconds()).isEqualTo(1734998402L);
        Assertions.assertThat(utcTime.getNanosOfSecond()).isEqualTo(230230000);
        Assertions.assertThat(utcTime.asInstant()).hasToString("2024-12-24T00:00:02.230230Z");

        utcTime = UtcDateTimeSerde.deserialize("20241224-00:00:03.230230230".getBytes(), 0, 27);
        Assertions.assertThat(utcTime.getEpochDays()).isEqualTo(20081L);
        Assertions.assertThat(utcTime.getEpochSeconds()).isEqualTo(1734998403L);
        Assertions.assertThat(utcTime.getNanosOfSecond()).isEqualTo(230230230);
        Assertions.assertThat(utcTime.asInstant()).hasToString("2024-12-24T00:00:03.230230230Z");
    }

    @Test
    void testDeserializeToEpochNanos() {
        for (String timestamp : new String[]{
                "20241224-00:01:01",
                "20241224-00:00:01.230",
                "20241224-00:00:02.230230",
                "20241224-00:00:03.230230230",
                "19981231-23:59:60"}) {
            byte[] bytes = timestamp.getBytes();
            long expected = UtcDateTimeSerde.deserialize(bytes, 0, bytes.length).toEpochNanos();
            Assertions.assertThat(UtcDateTimeSerde.deserializeToEpochNanos(bytes, 0, bytes.length))
                    .as(timestamp)
                    .isEqualTo(expected);
        }

        // honours the given slice offset/length within a larger buffer
        byte[] framed = "34=252=20241224-00:00:01.230".getBytes();
        long expected = UtcDateTimeSerde.deserialize("20241224-00:00:01.230".getBytes(), 0, 21).toEpochNanos();
        Assertions.assertThat(UtcDateTimeSerde.deserializeToEpochNanos(framed, 8, 21)).isEqualTo(expected);

        Assertions.assertThatThrownBy(() -> UtcDateTimeSerde.deserializeToEpochNanos("garbage".getBytes(), 0, "garbage".length()))
                .isInstanceOf(IllegalFieldValueException.class);
    }

    @Test
    void testSerializeUtcDateTime() {
        UTCTime stamps = UtcDateTimeSerde.deserialize("20241224-00:01:01".getBytes(), 0, 17);
        byte[] serialized = UtcDateTimeSerde.serializeInstant(stamps.asInstant(), TimeUnit.SECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("20241224-00:01:01");
        serialized = UtcDateTimeSerde.serializeEpochDays(1, 0, TimeUnit.SECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("19700102-00:00:00");
        serialized = UtcDateTimeSerde.serializeEpochMillis(1, 0, TimeUnit.SECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("19700101-00:00:00");
        Assertions.assertThat(new String(UtcDateTimeSerde.serializeTime(stamps, TimeUnit.SECONDS))).isEqualTo("20241224-00:01:01");

        stamps = UtcDateTimeSerde.deserialize("20241224-00:00:01.230".getBytes(), 0, 21);
        serialized = UtcDateTimeSerde.serializeInstant(stamps.asInstant(), TimeUnit.MILLISECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("20241224-00:00:01.230");
        serialized = UtcDateTimeSerde.serializeEpochDays(1, 230000000L, TimeUnit.MILLISECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("19700102-00:00:00.230");
        serialized = UtcDateTimeSerde.serializeEpochMillis(1, 230000000L, TimeUnit.MILLISECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("19700101-00:00:00.230");

        stamps = UtcDateTimeSerde.deserialize("20241224-00:00:02.230230".getBytes(), 0, 24);
        serialized = UtcDateTimeSerde.serializeInstant(stamps.asInstant(), TimeUnit.MICROSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("20241224-00:00:02.230230");
        serialized = UtcDateTimeSerde.serializeEpochDays(1, 230230000L, TimeUnit.MICROSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("19700102-00:00:00.230230");
        serialized = UtcDateTimeSerde.serializeEpochMillis(1, 230230000L, TimeUnit.MICROSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("19700101-00:00:00.230230");

        stamps = UtcDateTimeSerde.deserialize("20241224-00:00:03.230230230".getBytes(), 0, 27);
        serialized = UtcDateTimeSerde.serializeInstant(stamps.asInstant(), TimeUnit.NANOSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("20241224-00:00:03.230230230");

        serialized = UtcDateTimeSerde.serializeTime(stamps, TimeUnit.NANOSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("20241224-00:00:03.230230230");


        serialized = UtcDateTimeSerde.serializeEpochDays(1, 230230230L, TimeUnit.NANOSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("19700102-00:00:00.230230230");
        serialized = UtcDateTimeSerde.serializeEpochMillis(1, 230230230L, TimeUnit.NANOSECONDS);
        Assertions.assertThat(new String(serialized)).isEqualTo("19700101-00:00:00.230230230");
    }
}
