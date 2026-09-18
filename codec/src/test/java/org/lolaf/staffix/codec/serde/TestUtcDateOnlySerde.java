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

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

class TestUtcDateOnlySerde {

    private static final long MILLIS_IN_A_DAY = 1000L * 60 * 60 * 24;

    @Test
    void testDeserializeGarbage() {
        try {
            UtcDateOnlySerde.deserializeToEpochDays("garbage".getBytes(), 0, "garbage".length());
            Assertions.fail("should have failed");
        } catch (IllegalFieldValueException ex) {
            Assertions.assertThat(ex.getMessage()).isEqualTo("Invalid UTC date: garbage");
        }
        try {
            UtcDateOnlySerde.deserializeToEpochDays("aaaabbcc".getBytes(), 0, "aaaabbcc".length());
            Assertions.fail("should have failed");
        } catch (IllegalFieldValueException ex) {
            Assertions.assertThat(ex.getMessage()).isEqualTo("a is not a number");
        }
    }

    @Test
    void testToLocalDate() {
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
        Calendar cal = GregorianCalendar.getInstance();
        cal.setTimeZone(TimeZone.getTimeZone(ZoneId.of("UTC")));
        cal.set(2000, 1, 1, 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);

        String dateToTest = df.format(new Date(cal.getTimeInMillis()));
        int epochDays = UtcDateOnlySerde.deserializeToEpochDays(dateToTest.replace("-", "").getBytes(), 0, UtcDateOnlySerde.LENGTH);
        Assertions.assertThat(UtcDateOnlySerde.toLocalDate(epochDays)).isEqualTo(LocalDate.of(2000, 2, 1)); // month is zero based in cal.set()
    }

    @Test
    void testDeserializeUtcDateOnly() {
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
        Calendar cal = GregorianCalendar.getInstance();
        cal.setTimeZone(TimeZone.getTimeZone(ZoneId.of("UTC")));
        cal.set(2000, 1, 1, 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        while (cal.getTimeInMillis() < System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_MONTH, 1);

            String dateToTest = df.format(new Date(cal.getTimeInMillis()));
            int epochDays = UtcDateOnlySerde.deserializeToEpochDays(dateToTest.replace("-", "").getBytes(), 0, UtcDateOnlySerde.LENGTH);
            Assertions.assertThat(cal.getTimeInMillis()).isEqualTo(epochDays * MILLIS_IN_A_DAY);
        }
    }

    @Test
    void testSerializeUtcDateOnly() {
        LocalDate localDate = LocalDate.of(2024, 01, 01);
        byte[] serializedUsingEpochDay = UtcDateOnlySerde.serializeEpochDay(localDate.toEpochDay());
        Assertions.assertThat(new String(serializedUsingEpochDay)).isEqualTo("20240101");

        Assertions.assertThat(new String(UtcDateOnlySerde.serializeEpochMillis(1))).isEqualTo("19700101");

        DateFormat df = new SimpleDateFormat("yyyyMMdd");
        Calendar cal = GregorianCalendar.getInstance();
        cal.setTimeZone(TimeZone.getTimeZone(ZoneId.of("UTC")));
        cal.set(2000, 01, 01, 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        while (cal.getTimeInMillis() < System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_MONTH, 1);
            String date = df.format(new Date(cal.getTimeInMillis()));

            byte[] serializedUsingUtcMillis = UtcDateOnlySerde.serializeEpochMillis(cal.getTimeInMillis());

            Assertions.assertThat(new String(serializedUsingUtcMillis)).isEqualTo(date);
        }
    }
}
