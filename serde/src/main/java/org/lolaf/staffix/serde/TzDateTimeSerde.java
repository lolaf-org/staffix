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

import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.concurrent.TimeUnit;

/**
 * Reads and writes {@code TZTimestamp}, a date and time with an explicit offset.
 */
public class TzDateTimeSerde {

    private static final TzDateTimeSerde INSTANCE = new TzDateTimeSerde();
    private final StringThreadLocalSerde stringThreadLocalSerde;
    private final DateTimeFormatter dateTimeFormatter;
    private final DateTimeFormatterForTimeUnit dateTimeFormatterCache;

    private TzDateTimeSerde() {
        // dateTimeFormatter is immutable and thread safe
        dateTimeFormatter = getDateTimeFormatter("[.SSSSSSSSS][.SSSSSS][.SSS]");
        dateTimeFormatterCache = new DateTimeFormatterForTimeUnit();
        stringThreadLocalSerde = new StringThreadLocalSerde();
    }

    public static TzDateTimeSerde instance() {
        return INSTANCE;
    }

    private static DateTimeFormatter getDateTimeFormatter(String precisionPattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("yyyyMMdd-HH:mm:ss")
                .appendPattern(precisionPattern)
                .optionalStart()
                .appendOffset("+HH:mm", "Z")
                .parseStrict()
                .toFormatter();
    }

    public byte[] serialize(OffsetDateTime value, TimeUnit timeUnit) {
        return stringThreadLocalSerde.serialize(value.format(dateTimeFormatterCache.forTimeUnit(timeUnit)));
    }

    public OffsetDateTime deserialize(SerDe.DeserializationContext serdeContext) {
        try {
            return OffsetDateTime.parse(stringThreadLocalSerde.deserialize(serdeContext), dateTimeFormatter);
        } catch (Exception ex) {
            throw new IllegalFieldValueException("Unable to parse TzDateTimeOnly: " + serdeContext.contentToString(), ex);
        }
    }

    private static class DateTimeFormatterForTimeUnit {
        private final DateTimeFormatter secondsPrecision;
        private final DateTimeFormatter millisPrecision;
        private final DateTimeFormatter microsPrecision;
        private final DateTimeFormatter nanosPrecision;

        private DateTimeFormatterForTimeUnit() {
            secondsPrecision = getDateTimeFormatter("");
            millisPrecision = getDateTimeFormatter(".SSS");
            microsPrecision = getDateTimeFormatter(".SSSSSS");
            nanosPrecision = getDateTimeFormatter(".SSSSSSSSS");
        }

        DateTimeFormatter forTimeUnit(TimeUnit timeUnit) {
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
    }
}