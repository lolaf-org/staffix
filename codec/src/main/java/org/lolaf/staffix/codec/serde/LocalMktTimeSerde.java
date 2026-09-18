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

import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;

import java.nio.ByteBuffer;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Reads and writes {@code LocalMktTime}, a wall-clock time in the market's own zone.
 *
 * <p>A {@link java.time.LocalTime} and not an instant, deliberately: the field carries no zone, so only the
 * venue's calendar can say what moment it means.
 */
public class LocalMktTimeSerde implements SerDe<LocalTime> {
    private static final LocalMktTimeSerde INSTANCE = new LocalMktTimeSerde();
    private final StringThreadLocalSerde stringThreadLocalSerde;

    private LocalMktTimeSerde() {
        stringThreadLocalSerde = new StringThreadLocalSerde();
    }

    public static LocalMktTimeSerde instance() {
        return INSTANCE;
    }

    @Override
    public void serialize(ByteBuffer out, LocalTime value) {
        stringThreadLocalSerde.serialize(out, value.format(DateTimeFormatter.ISO_LOCAL_TIME));
    }

    @Override
    public LocalTime deserialize(SerDe.DeserializationContext serdeContext) {
        try {
            return LocalTime.parse(stringThreadLocalSerde.deserialize(serdeContext), DateTimeFormatter.ISO_LOCAL_TIME);
        } catch (Exception ex) {
            throw new IllegalFieldValueException("Unable to parse LocalTime: " + serdeContext.contentToString(), ex);
        }
    }
}
