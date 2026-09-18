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

import lombok.Getter;
import lombok.Setter;
import lombok.Value;
import lombok.experimental.UtilityClass;
import org.lolaf.ringos.threading.FastThreadLocal;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.time.UTCTime;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Reads and writes {@code UTCTimestamp}, {@code YYYYMMDD-HH:MM:SS} with optional sub-second digits.
 *
 * <p>SendingTime(52) is on every message, so this runs twice per message in each direction and is worth the
 * fixed-offset treatment. The fractional part is optional in the specification and its length varies, which is
 * the only part that has to be scanned.
 */
@UtilityClass
public class UtcDateTimeSerde {

    public static final int SIZE_WITH_SECONDS = 17;
    public static final int SIZE_WITH_MILLIS = 21;
    public static final int SIZE_WITH_MICROS = 24;
    public static final int SIZE_WITH_NANOS = 27;
    private static final byte DELIMITER = '-';
    private static final long SECONDS_PER_DAY = 60L * 60 * 24;
    private static final long NANOS_IN_SECOND = 1000L * 1000L * 1000L;
    private static final FastThreadLocal<UTCDateTime> DATE_TIME_STAMPS = FastThreadLocal.withInitial(UTCDateTime::new);
    private static final FastThreadLocal<ByteArraysDateTimeOnlyCache> BA_CACHE = FastThreadLocal.withInitial(ByteArraysDateTimeOnlyCache::new);

    public static UTCTime deserialize(SerDe.DeserializationContext context) {
        return deserialize(context.getDeserializationBuffer(), context.getStartOffset(), context.getLength());
    }

    public static UTCTime deserialize(byte[] dateTimeBuffer) {
        return deserialize(dateTimeBuffer, 0, dateTimeBuffer.length);
    }

    static UTCTime deserialize(byte[] serdeBuffer, int startOffset, int length) {
        if (length < SIZE_WITH_SECONDS || length > SIZE_WITH_NANOS) {
            throw invalidUTCDate(serdeBuffer, startOffset, length);
        }
        UTCDateTime dateTimeStamps = DATE_TIME_STAMPS.get().clean();
        int epochDay = UtcDateOnlySerde.deserializeToEpochDays(serdeBuffer, startOffset, UtcDateOnlySerde.LENGTH);
        dateTimeStamps.setEpochDays(epochDay);
        startOffset = startOffset + UtcDateOnlySerde.LENGTH;
        if (serdeBuffer[startOffset++] != DELIMITER) {
            throw invalidUTCDate(serdeBuffer, startOffset, length);
        }
        switch (length) {
            case SIZE_WITH_SECONDS:
                long nanoOfDay = UtcTimeOnlySerde.deserializeNanoOfDay(serdeBuffer, startOffset, UtcTimeOnlySerde.LENGTH_WITH_SECONDS);
                dateTimeStamps.setEpochSeconds(epochDay * SECONDS_PER_DAY + (nanoOfDay / NANOS_IN_SECOND));
                dateTimeStamps.setNanosOfSecond(0);
                break;
            case SIZE_WITH_MILLIS:
                computeUtcDateTime(serdeBuffer, startOffset, UtcTimeOnlySerde.LENGTH_WITH_MILLISECONDS, dateTimeStamps, epochDay);
                break;
            case SIZE_WITH_MICROS:
                computeUtcDateTime(serdeBuffer, startOffset, UtcTimeOnlySerde.LENGTH_WITH_MICROSECONDS, dateTimeStamps, epochDay);
                break;
            case SIZE_WITH_NANOS:
                computeUtcDateTime(serdeBuffer, startOffset, UtcTimeOnlySerde.LENGTH_WITH_NANOSECONDS, dateTimeStamps, epochDay);
                break;
            default:
                throw invalidUTCDate(serdeBuffer, startOffset, length);
        }
        return dateTimeStamps;
    }

    /**
     * Deserializes a UTC timestamp straight to epoch nanoseconds without allocating or populating a {@link UTCTime}.
     * Faster than {@code deserialize(...).toEpochNanos()} for callers that only need the numeric instant (e.g. comparing
     * two timestamps). {@code startOffset}/{@code length} address the timestamp bytes inside {@code serdeBuffer}.
     */
    public static long deserializeToEpochNanos(byte[] serdeBuffer, int startOffset, int length) {
        if (length < SIZE_WITH_SECONDS || length > SIZE_WITH_NANOS) {
            throw invalidUTCDate(serdeBuffer, startOffset, length);
        }
        int epochDay = UtcDateOnlySerde.deserializeToEpochDays(serdeBuffer, startOffset, UtcDateOnlySerde.LENGTH);
        int timeOffset = startOffset + UtcDateOnlySerde.LENGTH;
        if (serdeBuffer[timeOffset++] != DELIMITER) {
            throw invalidUTCDate(serdeBuffer, startOffset, length);
        }
        long nanoOfDay;
        switch (length) {
            case SIZE_WITH_SECONDS:
                nanoOfDay = UtcTimeOnlySerde.deserializeNanoOfDay(serdeBuffer, timeOffset, UtcTimeOnlySerde.LENGTH_WITH_SECONDS);
                break;
            case SIZE_WITH_MILLIS:
                nanoOfDay = UtcTimeOnlySerde.deserializeNanoOfDay(serdeBuffer, timeOffset, UtcTimeOnlySerde.LENGTH_WITH_MILLISECONDS);
                break;
            case SIZE_WITH_MICROS:
                nanoOfDay = UtcTimeOnlySerde.deserializeNanoOfDay(serdeBuffer, timeOffset, UtcTimeOnlySerde.LENGTH_WITH_MICROSECONDS);
                break;
            case SIZE_WITH_NANOS:
                nanoOfDay = UtcTimeOnlySerde.deserializeNanoOfDay(serdeBuffer, timeOffset, UtcTimeOnlySerde.LENGTH_WITH_NANOSECONDS);
                break;
            default:
                throw invalidUTCDate(serdeBuffer, startOffset, length);
        }
        return epochDay * SECONDS_PER_DAY * NANOS_IN_SECOND + nanoOfDay;
    }

    private static void computeUtcDateTime(byte[] serdeBuffer, int startOffset, int length, UTCDateTime utcDateTime, int epochDay) {
        long nanoOfDay = UtcTimeOnlySerde.deserializeNanoOfDay(serdeBuffer, startOffset, length);
        int nanosOfSecond = UtcTimeOnlySerde.getNanos(nanoOfDay);
        utcDateTime.setEpochSeconds(epochDay * SECONDS_PER_DAY + ((nanoOfDay - nanosOfSecond) / NANOS_IN_SECOND));
        utcDateTime.setNanosOfSecond(nanosOfSecond);
    }

    private static IllegalFieldValueException invalidUTCDate(byte[] serdeBuffer, int startOffset, int length) {
        return new IllegalFieldValueException("Invalid UTC datetime: " + new String(serdeBuffer, startOffset, length, SerDe.CHARSET));
    }

    public static byte[] serializeEpochDays(long epochDays, long nanosOfDay, TimeUnit timeUnit) {
        byte[] targetArray = BA_CACHE.get().forTimeunit(timeUnit);
        byte[] serializedDate = UtcDateOnlySerde.serializeEpochDay(epochDays);
        return serialize(nanosOfDay, timeUnit, serializedDate, targetArray);
    }

    public static byte[] serializeEpochMillis(long epochMillis, long nanosOfDay, TimeUnit timeUnit) {
        byte[] targetArray = BA_CACHE.get().forTimeunit(timeUnit);
        byte[] serializedDate = UtcDateOnlySerde.serializeEpochMillis(epochMillis);
        return serialize(nanosOfDay, timeUnit, serializedDate, targetArray);
    }

    private static byte[] serialize(long nanosOfDay, TimeUnit timeUnit, byte[] serializedDate, byte[] targetArray) {
        System.arraycopy(serializedDate, 0, targetArray, 0, serializedDate.length);
        byte[] serializedTime = UtcTimeOnlySerde.serializeForPrecision(nanosOfDay, timeUnit);
        System.arraycopy(serializedTime, 0, targetArray, serializedDate.length + 1, serializedTime.length);
        return targetArray;
    }

    public static byte[] serializeTime(UTCTime utcTime, TimeUnit timeUnit) {
        byte[] targetArray = BA_CACHE.get().forTimeunit(timeUnit);
        byte[] serializedDate = UtcDateOnlySerde.serializeEpochDay(utcTime.getEpochDays());
        System.arraycopy(serializedDate, 0, targetArray, 0, serializedDate.length);
        byte[] serializedTime = UtcTimeOnlySerde.serializeForPrecision(utcTime.toNanoOfDay(), timeUnit);
        System.arraycopy(serializedTime, 0, targetArray, serializedDate.length + 1, serializedTime.length);
        return targetArray;
    }

    public static byte[] serializeInstant(Instant instant, TimeUnit timeUnit) {
        byte[] targetArray = BA_CACHE.get().forTimeunit(timeUnit);
        byte[] serializedDate = UtcDateOnlySerde.serializeEpochMillis(instant.toEpochMilli());
        System.arraycopy(serializedDate, 0, targetArray, 0, serializedDate.length);
        long nanosOfDay = (instant.getEpochSecond() % SECONDS_PER_DAY) * NANOS_IN_SECOND + instant.getNano();
        byte[] serializedTime = UtcTimeOnlySerde.serializeForPrecision(nanosOfDay, timeUnit);
        System.arraycopy(serializedTime, 0, targetArray, serializedDate.length + 1, serializedTime.length);
        return targetArray;
    }

    private static class ByteArraysDateTimeOnlyCache {
        private final byte[] secondsPrecision = new byte[SIZE_WITH_SECONDS];
        private final byte[] millisPrecision = new byte[SIZE_WITH_MILLIS];
        private final byte[] microsPrecision = new byte[SIZE_WITH_MICROS];
        private final byte[] nanosPrecision = new byte[SIZE_WITH_NANOS];

        private ByteArraysDateTimeOnlyCache() {
            initArray(secondsPrecision);
            initArray(millisPrecision);
            initArray(microsPrecision);
            initArray(nanosPrecision);
        }

        byte[] forTimeunit(TimeUnit timeUnit) {
            switch (timeUnit) {
                case MILLISECONDS:
                    return millisPrecision;
                case MICROSECONDS:
                    return microsPrecision;
                case SECONDS:
                    return secondsPrecision;
                case NANOSECONDS:
                    return nanosPrecision;
                default:
                    throw new IllegalFieldValueException("Unsupported time unit: " + timeUnit);
            }
        }

        private void initArray(byte[] array) {
            array[8] = DELIMITER;
        }
    }

    @Getter
    @Setter
    private static final class UTCDateTime implements UTCTime {

        private int epochDays;
        private long epochSeconds;
        private int nanosOfSecond;

        public UTCDateTime clean() {
            epochSeconds = 0L;
            epochDays = nanosOfSecond = 0;
            return this;
        }

        @Override
        public UTCTime asImmutable() {
            return new ImmutableUTCDateTime(epochDays, epochSeconds, nanosOfSecond);
        }

        @Override
        public boolean isImmutable() {
            return false;
        }
    }

    @Value
    private static class ImmutableUTCDateTime implements UTCTime {

        int epochDays;
        long epochSeconds;
        int nanosOfSecond;

        @Override
        public UTCTime asImmutable() {
            return this;
        }

        @Override
        public boolean isImmutable() {
            return true;
        }
    }
}