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

import java.nio.ByteBuffer;
import java.time.OffsetTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

/**
 * Reads and writes {@code TZTimeOnly} - a time with an explicit offset, so unlike {@code LocalMktTime} it does
 * name a moment within its day.
 */
public class TzTimeOnlySerde implements SerDe<OffsetTime> {

    private static final TzTimeOnlySerde INSTANCE = new TzTimeOnlySerde();
    private final StringThreadLocalSerde stringThreadLocalSerde;
    private final DateTimeFormatter dateTimeFormatter;

    private TzTimeOnlySerde() {
        stringThreadLocalSerde = new StringThreadLocalSerde();
        // dateTimeFormatter is immutable and thread safe
        dateTimeFormatter = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("HH:mm")
                .optionalStart()
                .appendPattern(":ss")
                .optionalEnd()
                .appendOffset("+HH:mm", "Z")
                .parseStrict()
                .toFormatter();
    }

    public static TzTimeOnlySerde instance() {
        return INSTANCE;
    }

    @Override
    public void serialize(ByteBuffer out, OffsetTime value) {
        stringThreadLocalSerde.serialize(out, value.format(dateTimeFormatter));
    }

    @Override
    public OffsetTime deserialize(DeserializationContext serdeContext) {
        try {
            return OffsetTime.parse(stringThreadLocalSerde.deserialize(serdeContext), dateTimeFormatter);
        } catch (Exception ex) {
            throw new IllegalFieldValueException("Unable to parse TzTimeOnly: " + serdeContext.contentToString(), ex);
        }
    }
}