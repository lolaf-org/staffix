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
import org.lolaf.staffix.api.time.UTCTime;

import java.time.Instant;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

/**
 * Time-only represented in UTC (Universal Time Coordinated, also known as "GMT") in either HH:MM:SS (whole seconds)
 * or HH:MM:SS.sss (milliseconds) format, colons, and period required. This special-purpose field is paired with UTCDateOnly
 * to form a proper UTCTimestamp for bandwidth-sensitive messages.
 * Valid values: HH = 00-23, MM = 00-59, SS = 00-5960 (60 only if UTC leap second) (without milliseconds). HH = 00-23, MM = 00-59, SS = 00-5960 (60 only if UTC leap second), sss=000-999 (indicating milliseconds).
 */
/**
 * Reads and writes {@code UTCTimeOnly}, {@code HH:MM:SS} with optional sub-second digits.
 */
@UtilityClass
public class UtcTimeOnlySerde {
    static final int LENGTH_WITH_MINUTES = 5;
    static final int LENGTH_WITH_SECONDS = 8;
    static final int LENGTH_WITH_MILLISECONDS = 12;
    static final int LENGTH_WITH_MICROSECONDS = 15;
    static final int LENGTH_WITH_NANOSECONDS = 18;
    private static final int ZERO = 0;
    private static final int TWO = 2;
    private static final int ONE = 1;
    private static final int TEN = 10;
    private static final int TREE = 3;
    private static final int SIX = 6;
    private static final int NINE = 9;
    private static final int FIVE = 5;
    private static final int EIGHT = 8;
    private static final int FOUR = 4;
    private static final int SEVEN = 7;
    private static final int MILLIS_FIELD_LENGTH = TREE;
    private static final int MICROS_FIELD_LENGTH = SIX;
    private static final int NANOS_FIELD_LENGTH = 9;
    private static final int SECONDS_IN_MINUTE = 60;
    private static final int SECONDS_IN_HOUR = SECONDS_IN_MINUTE * 60;
    private static final long MILLIS_IN_SECOND = 1_000L;
    private static final long MICROS_IN_SECOND = MILLIS_IN_SECOND * 1000L;
    private static final long NANOS_IN_SECOND = MICROS_IN_SECOND * 1000L;
    private static final long NANOS_IN_MILLIS = 1000L * 1000L;
    private static final long NANOS_IN_MICROS = 1000L;
    private static final byte SEMICOLON = ':';
    private static final byte POINT = '.';
    private static final FastThreadLocal<ByteArraysTimeOnlyCache> BA_CACHE = FastThreadLocal.withInitial(ByteArraysTimeOnlyCache::new);
    private static final long NANOS_PER_SECOND = 1000_000_000L;
    private static final long NANOS_PER_MINUTE = NANOS_PER_SECOND * 60;
    private static final long NANOS_PER_HOUR = NANOS_PER_MINUTE * 60;
    private static final int FRACTION_START_OFFSET = 9;
    private static final byte ASCII_ZERO_BYTE = '0';
    private static final int HUNDRED = 100;
    private static final int MILLION = 100000;
    private static final int BILLION = 100000000;

    private static long toNanoOfDay(Instant instant) {
        long secondsOfDay = instant.getEpochSecond() % 86400;
        if (secondsOfDay < 0) secondsOfDay += 86400;
        return secondsOfDay * 1_000_000_000L + instant.getNano();
    }

    public static byte[] serializeForPrecision(Instant instant, TimeUnit encodedPrecision) {
        return serializeForPrecision(toNanoOfDay(instant), encodedPrecision);
    }

    public static byte[] serializeForPrecision(UTCTime localTime, TimeUnit encodedPrecision) {
        return serializeForPrecision(localTime.toNanoOfDay(), encodedPrecision);
    }

    /**
     * Serialized a local time, WARNING: the provided localTime should have values already set to UTC time, no conversion will be made here
     */
    public static byte[] serializeForPrecision(LocalTime localTime, TimeUnit encodedPrecision) {
        return serializeForPrecision(localTime.toNanoOfDay(), encodedPrecision);
    }

    public static byte[] serializeForPrecision(long nanoOfDay, TimeUnit encodedPrecision) {
        byte[] output = BA_CACHE.get().forPrecision(encodedPrecision);
        int hours = (int) (nanoOfDay / NANOS_PER_HOUR);
        if (hours < TEN) {
            output[ZERO] = ASCII_ZERO_BYTE;
            IntSerde.serialize(hours, output, ONE, ONE);
        } else {
            IntSerde.serialize(hours, output, TWO, ZERO);
        }
        nanoOfDay -= hours * NANOS_PER_HOUR;
        int minutes = (int) (nanoOfDay / NANOS_PER_MINUTE);
        if (minutes < TEN) {
            output[TREE] = ASCII_ZERO_BYTE;
            IntSerde.serialize(minutes, output, ONE, FOUR);
        } else {
            IntSerde.serialize(minutes, output, TWO, TREE);
        }
        nanoOfDay -= minutes * NANOS_PER_MINUTE;
        int seconds = (int) (nanoOfDay / NANOS_PER_SECOND);
        if (seconds < TEN) {
            output[SIX] = ASCII_ZERO_BYTE;
            IntSerde.serialize(seconds, output, ONE, SEVEN);
        } else {
            IntSerde.serialize(seconds, output, TWO, SIX);
        }
        nanoOfDay -= seconds * NANOS_PER_SECOND;
        if (encodedPrecision.equals(TimeUnit.MILLISECONDS)) {
            serializeFraction((int) (nanoOfDay / MICROS_IN_SECOND), HUNDRED, MILLIS_FIELD_LENGTH, output);
        } else if (encodedPrecision.equals(TimeUnit.MICROSECONDS)) {
            serializeFraction((int) (nanoOfDay / MILLIS_IN_SECOND), MILLION, MICROS_FIELD_LENGTH, output);
        } else if (encodedPrecision.equals(TimeUnit.NANOSECONDS)) {
            serializeFraction((int) nanoOfDay, BILLION, NANOS_FIELD_LENGTH, output);
        }
        return output;
    }

    private static void serializeFraction(int millis, int max, int valueSize, byte[] output) {
        int startOffset = FRACTION_START_OFFSET;
        int leadingZeros = ZERO;
        while (millis < max) {
            max = max / TEN;
            leadingZeros++;
            valueSize--;
        }
        if (leadingZeros > ZERO) {
            for (int i = ZERO; i < leadingZeros; i++) {
                output[startOffset++] = ASCII_ZERO_BYTE;
            }
        }
        IntSerde.serialize(millis, output, valueSize, startOffset);
    }

    /**
     * Deserialized a time only field and return a long containing the number of seconds, millis, micros or nanos since start of day depending on its
     * precision, can be used with {@link #toLocalUTCTime(long)} to create a LocalTime object.
     * {@link #getHour(long)},  {@link #getMinute(long)},  {@link #getSeconds(long)},  {@link #getNanos(long)} methods can also be used to retrieve time infos from the serialized field without allocation too much memory
     */
    public static long deserialize(SerDe.DeserializationContext context) {
        return deserializeNanoOfDay(context.getDeserializationBuffer(), context.getStartOffset(), context.getLength());
    }

    public static long deserializeNanoOfDay(byte[] serdeBuffer, int startOffset, int length) {
        if (length < LENGTH_WITH_MINUTES) {
            throw throwInvalidDateProvided(serdeBuffer, startOffset, length);
        }
        int hour = IntSerde.deserializeUnsigned(serdeBuffer, startOffset, TWO);
        if (hour < ZERO || hour > 24) {
            throw throwInvalidDateProvided(serdeBuffer, startOffset, length);
        }
        if (serdeBuffer[startOffset + TWO] != SEMICOLON) {
            throw throwInvalidDateProvided(serdeBuffer, startOffset, length);
        }
        int minute = IntSerde.deserializeUnsigned(serdeBuffer, startOffset + TREE, TWO);
        if (minute < ZERO || minute > 59) {
            throw throwInvalidDateProvided(serdeBuffer, startOffset, length);
        }
        if (length == LENGTH_WITH_MINUTES) {
            return NANOS_PER_SECOND * ((hour * SECONDS_IN_HOUR) + (minute * SECONDS_IN_MINUTE));
        }

        if (serdeBuffer[startOffset + FIVE] != SEMICOLON) {
            throw throwInvalidDateProvided(serdeBuffer, startOffset, length);
        }
        int second = IntSerde.deserializeUnsigned(serdeBuffer, startOffset + SIX, TWO);
        if (second < ZERO || second > 60) { // 60 for leap second
            throw throwInvalidDateProvided(serdeBuffer, startOffset, length);
        }
        long timeInNanos = NANOS_PER_SECOND * ((hour * SECONDS_IN_HOUR) + (minute * SECONDS_IN_MINUTE) + second);
        if (length == LENGTH_WITH_SECONDS) {
            return timeInNanos;
        }
        if (serdeBuffer[startOffset + EIGHT] != POINT) {
            throw throwInvalidDateProvided(serdeBuffer, startOffset, length);
        }
        if (length == LENGTH_WITH_MILLISECONDS) {
            return timeInNanos + (IntSerde.deserializeUnsigned(serdeBuffer, startOffset + FRACTION_START_OFFSET, TREE) * NANOS_IN_MILLIS);
        }
        if (length == LENGTH_WITH_MICROSECONDS) {
            return timeInNanos + (IntSerde.deserializeUnsigned(serdeBuffer, startOffset + FRACTION_START_OFFSET, SIX) * NANOS_IN_MICROS);
        }
        if (length == LENGTH_WITH_NANOSECONDS) {
            return timeInNanos + IntSerde.deserializeUnsigned(serdeBuffer, startOffset + FRACTION_START_OFFSET, NINE);
        }
        throw throwInvalidDateProvided(serdeBuffer, startOffset, length);
    }

    public static LocalTime toLocalUTCTime(long nanoOfDay) {
        return LocalTime.ofNanoOfDay(nanoOfDay);
    }

    public static int getNanos(long nanoOfDay) {
        int hours = (int) (nanoOfDay / NANOS_PER_HOUR);
        nanoOfDay -= hours * NANOS_PER_HOUR;
        int minutes = (int) (nanoOfDay / (NANOS_PER_MINUTE));
        nanoOfDay -= minutes * NANOS_PER_MINUTE;
        int seconds = (int) (nanoOfDay / NANOS_PER_SECOND);
        nanoOfDay -= seconds * NANOS_IN_SECOND;
        return (int) nanoOfDay;
    }

    public static int getSeconds(long nanoOfDay) {
        int hours = (int) (nanoOfDay / NANOS_PER_HOUR);
        nanoOfDay -= hours * NANOS_PER_HOUR;
        int minutes = (int) (nanoOfDay / (NANOS_PER_MINUTE));
        nanoOfDay -= minutes * NANOS_PER_MINUTE;
        return (int) (nanoOfDay / NANOS_PER_SECOND);
    }

    public static int getMinute(long nanoOfDay) {
        int hours = (int) (nanoOfDay / NANOS_PER_HOUR);
        nanoOfDay -= hours * NANOS_PER_HOUR;
        return (int) (nanoOfDay / NANOS_PER_MINUTE);
    }

    public static int getHour(long nanoOfDay) {
        return (int) (nanoOfDay / NANOS_PER_HOUR);
    }

    private static RuntimeException throwInvalidDateProvided(byte[] serdeBuffer, int startOffset, int length) {
        return new IllegalFieldValueException("Invalid UTC time: " + new String(serdeBuffer, startOffset, length, SerDe.CHARSET));
    }

    private static class ByteArraysTimeOnlyCache {
        private final byte[] secondsPrecision = new byte[LENGTH_WITH_SECONDS];
        private final byte[] millisPrecision = new byte[LENGTH_WITH_MILLISECONDS];
        private final byte[] microsPrecision = new byte[LENGTH_WITH_MICROSECONDS];
        private final byte[] nanosPrecision = new byte[LENGTH_WITH_NANOSECONDS];

        private ByteArraysTimeOnlyCache() {
            initArray(secondsPrecision);
            initArray(millisPrecision);
            initArray(microsPrecision);
            initArray(nanosPrecision);
        }

        byte[] forPrecision(TimeUnit timeUnit) {
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
            array[TWO] = SEMICOLON;
            array[FIVE] = SEMICOLON;
            if (array.length > LENGTH_WITH_SECONDS) {
                array[EIGHT] = POINT;
            }
        }
    }
}