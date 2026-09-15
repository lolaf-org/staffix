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

import lombok.experimental.UtilityClass;
import org.lolaf.ringos.threading.FastThreadLocal;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;

import java.time.LocalDate;
import java.time.Year;

/**
 * Reads and writes {@code UTCDateOnly}, {@code YYYYMMDD}.
 *
 * <p>Fixed width, so the digits are read at known offsets rather than scanned.
 */
@UtilityClass
public class UtcDateOnlySerde {

    private static final int SIZE_OF_YEAR = 4;
    private static final int SIZE_OF_MONTH = 2;
    private static final int SIZE_OF_DAY = 2;
    static final int LENGTH = SIZE_OF_YEAR + SIZE_OF_MONTH + SIZE_OF_DAY;
    private static final FastThreadLocal<byte[]> BA_CACHE = FastThreadLocal.withInitial(() -> new byte[LENGTH]);
    private static final int DAYS_UNTIL_START_OF_EPOCH = 719528;
    private static final int MAX_DAYS_IN_YEAR = 365;
    private static final int MONTHS_IN_YEAR = 12;
    private static final int DAYS_IN_400_YEAR_CYCLE = 146097;
    private static final int DAYS_UNTIL_START_OF_UNIX_EPOCH = 719528;
    private static final long MILLIS_IN_A_DAY = 1000L * 60 * 60 * 24;
    private static final byte ASCII_ZERO_BYTE = '0';
    private static final int MONTH_START_INDEX = SIZE_OF_YEAR;
    private static final int DAY_START_INDEX = MONTH_START_INDEX + SIZE_OF_MONTH;
    private static final int MIN_EPOCH_DAYS = -719162;
    private static final int MAX_EPOCH_DAYS = 2932896;


    /**
     * Deserialize the number of days since epoch
     */
    public static int deserializeToEpochDays(SerDe.DeserializationContext context) {
        return deserializeToEpochDays(context.getDeserializationBuffer(), context.getStartOffset(), context.getLength());
    }

    /**
     * Deserialize the date to the number of days since epoch
     */
    public static int deserializeToEpochDays(byte[] serdeBuffer, int startOffset, int length) {
        if (length != LENGTH) {
            throw new IllegalFieldValueException("Invalid UTC date: " + new String(serdeBuffer, startOffset, length, SerDe.CHARSET));
        }
        int endMonthStartOffset = startOffset + SIZE_OF_YEAR;
        int endDayStartOffset = endMonthStartOffset + SIZE_OF_DAY;
        return toEpochDay(
                IntSerde.deserializeUnsigned(serdeBuffer, startOffset, SIZE_OF_YEAR),
                IntSerde.deserializeUnsigned(serdeBuffer, endMonthStartOffset, SIZE_OF_MONTH),
                IntSerde.deserializeUnsigned(serdeBuffer, endDayStartOffset, SIZE_OF_DAY));
    }

    public static LocalDate toLocalDate(int epochDay) {
        return LocalDate.ofEpochDay(epochDay);
    }

    public static byte[] serializeEpochMillis(long epochMillis) {
        return serializeEpochDay(epochMillis / MILLIS_IN_A_DAY);
    }

    public static byte[] serializeEpochDay(long epochDays) {
        if (epochDays < MIN_EPOCH_DAYS || epochDays > MAX_EPOCH_DAYS) {
            throw new IllegalFieldValueException(epochDays + " is outside of the valid range");
        }
        byte[] output = BA_CACHE.get();
        // adjust to 0000-03-01 so leap day is at end of four year cycle
        final long zeroDay = epochDays + DAYS_UNTIL_START_OF_UNIX_EPOCH - 60;
        long yearEstimate = (400 * zeroDay + 591) / DAYS_IN_400_YEAR_CYCLE;
        long dayEstimate = estimateDayOfYear(zeroDay, yearEstimate);
        if (dayEstimate < 0) {
            // fix estimate
            yearEstimate--;
            dayEstimate = estimateDayOfYear(zeroDay, yearEstimate);
        }
        int marchDay0 = (int) dayEstimate;
        // convert march-based values back to january-based
        int marchMonth0 = (marchDay0 * 5 + 2) / 153;
        int month = (marchMonth0 + 2) % 12 + 1;
        int day = marchDay0 - (marchMonth0 * 306 + 5) / 10 + 1;
        int year = (int) (yearEstimate + marchMonth0 / 10);

        IntSerde.serialize(year, output, SIZE_OF_YEAR, 0);
        if (month < 10) {
            output[MONTH_START_INDEX] = ASCII_ZERO_BYTE;
            IntSerde.serialize(month, output, 1, MONTH_START_INDEX + 1);
        } else {
            IntSerde.serialize(month, output, SIZE_OF_MONTH, MONTH_START_INDEX);
        }
        if (day < 10) {
            output[DAY_START_INDEX] = ASCII_ZERO_BYTE;
            IntSerde.serialize(day, output, 1, DAY_START_INDEX + 1);
        } else {
            IntSerde.serialize(day, output, SIZE_OF_DAY, DAY_START_INDEX);
        }
        return output;
    }

    private static long estimateDayOfYear(final long zeroDay, final long yearEst) {
        return zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400);
    }

    private static int toEpochDay(int year, int month, int day) {
        if (month < 1 || month > 12) {
            throw new IllegalFieldValueException("Invalid month in UTC date: " + month);
        }
        if (day < 1 || day > 31) {
            throw new IllegalFieldValueException("Invalid day in UTC date: " + day);
        }

        return yearsToDays(year) + monthsToDays(month, year) + (day - 1) - DAYS_UNTIL_START_OF_EPOCH;
    }

    private static int yearsToDays(int years) {
        return MAX_DAYS_IN_YEAR * years + (years + 3) / 4 - (years + 99) / 100 + (years + 399) / 400;
    }

    private static int monthsToDays(int month, int year) {
        int days = (367 * month - 362) / MONTHS_IN_YEAR;
        if (month > 2) {
            days--;
            if (!Year.isLeap(year)) {
                days--;
            }
        }
        return days;
    }
}
