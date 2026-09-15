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
package org.lolaf.staffix.api.codec;

import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.serde.DecimalFloat;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.time.UTCTime;

import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Appends typed FIX fields to a message being encoded.
 * <p>
 * Each {@code addXxx} method serializes a {@code tag=value} field for the given {@link FixField} into the encoder's
 * output and returns the encoder itself, so calls can be chained fluently. The numerous typed overloads let callers
 * write the appropriate value type directly without boxing or intermediate string conversion, which keeps encoding on
 * the allocation-free hot path; {@link #addField(FixField, Object, SerDe)} is the escape hatch for value types that
 * have no dedicated method, delegating serialization to a caller-supplied {@link SerDe}.
 * <p>
 * Fields are written in call order, which is the order they appear in the encoded message. Implementations are not
 * thread-safe and are intended to be driven by a single encoding thread.
 *
 * @param <T> the concrete encoder type returned for fluent chaining (self-type)
 * @see FixField
 * @see FixMessageEncoder
 */
public interface FixFieldsEncoder<T> {

    /**
     * Appends a field whose value is serialized by a caller-supplied {@link SerDe}, for value types that have no
     * dedicated {@code addXxx} method.
     *
     * @param fixField   the field (tag) to write
     * @param value      the value to serialize
     * @param serializer the serializer used to render {@code value}
     * @param <V>        the value type
     * @return this encoder, for chaining
     */
    <V> T addField(FixField fixField, V value, SerDe<V> serializer);

    /**
     * Appends a field whose value is already serialized to its FIX byte representation.
     *
     * @param fixField        the field (tag) to write
     * @param serializedField the pre-serialized field value bytes
     * @return this encoder, for chaining
     */
    T addBytes(FixField fixField, byte[] serializedField);

    /**
     * Appends a UTC timestamp (date and time) field from an epoch day and nanoseconds-of-day.
     *
     * @param fixField   the field (tag) to write
     * @param epochDay   the date as days since the epoch
     * @param nanosOfDay the time as nanoseconds since midnight
     * @param accuracy   the precision (e.g. seconds, millis, micros, nanos) to which the time is rendered
     * @return this encoder, for chaining
     */
    T addUtcDateTime(FixField fixField, long epochDay, long nanosOfDay, TimeUnit accuracy);

    /**
     * Appends a UTC timestamp (date and time) field from a {@link UTCTime}.
     *
     * @param fixField the field (tag) to write
     * @param value    the timestamp value
     * @param accuracy the precision to which the time is rendered
     * @return this encoder, for chaining
     */
    T addUtcDateTime(FixField fixField, UTCTime value, TimeUnit accuracy);

    /**
     * Appends a UTC timestamp (date and time) field from an {@link Instant}.
     *
     * @param fixField the field (tag) to write
     * @param value    the timestamp value
     * @param accuracy the precision to which the time is rendered
     * @return this encoder, for chaining
     */
    T addUtcDateTime(FixField fixField, Instant value, TimeUnit accuracy);

    /**
     * Appends a UTC date-only field from an epoch day.
     *
     * @param fixField the field (tag) to write
     * @param epochDay the date as days since the epoch
     * @return this encoder, for chaining
     */
    T addUtcDate(FixField fixField, long epochDay);

    /**
     * Appends a UTC date-only field from a {@link LocalDate}.
     *
     * @param fixField the field (tag) to write
     * @param value    the date value
     * @return this encoder, for chaining
     */
    T addUtcDate(FixField fixField, LocalDate value);

    /**
     * Appends a UTC time-only field from an {@link Instant}.
     *
     * @param fixField the field (tag) to write
     * @param value    the time value
     * @param accuracy the precision to which the time is rendered
     * @return this encoder, for chaining
     */
    T addUtcTime(FixField fixField, Instant value, TimeUnit accuracy);

    /**
     * Appends a UTC time-only field from a {@link UTCTime}.
     *
     * @param fixField the field (tag) to write
     * @param value    the time value
     * @param accuracy the precision to which the time is rendered
     * @return this encoder, for chaining
     */
    T addUtcTime(FixField fixField, UTCTime value, TimeUnit accuracy);

    /**
     * Appends a UTC time-only field from nanoseconds-of-day.
     *
     * @param fixField   the field (tag) to write
     * @param nanosOfDay the time as nanoseconds since midnight
     * @param accuracy   the precision to which the time is rendered
     * @return this encoder, for chaining
     */
    T addUtcTime(FixField fixField, long nanosOfDay, TimeUnit accuracy);

    /**
     * Appends a UTC time-only field from a {@link LocalTime}.
     *
     * @param fixField the field (tag) to write
     * @param value    the time value
     * @param accuracy the precision to which the time is rendered
     * @return this encoder, for chaining
     */
    T addUtcTime(FixField fixField, LocalTime value, TimeUnit accuracy);

    /**
     * Appends a time-with-timezone-offset field from an {@link OffsetTime}.
     *
     * @param fixField   the field (tag) to write
     * @param offsetTime the time-with-offset value
     * @return this encoder, for chaining
     */
    T addTzTime(FixField fixField, OffsetTime offsetTime);

    /**
     * Appends a timestamp-with-timezone-offset field from an {@link OffsetDateTime}.
     *
     * @param fixField       the field (tag) to write
     * @param offsetDateTime the timestamp-with-offset value
     * @param accuracy       the precision to which the time is rendered
     * @return this encoder, for chaining
     */
    T addTzDateTime(FixField fixField, OffsetDateTime offsetDateTime, TimeUnit accuracy);

    /**
     * Appends an integer field.
     *
     * @param fixField the field (tag) to write
     * @param value    the integer value
     * @return this encoder, for chaining
     */
    T addInt(FixField fixField, int value);

    /**
     * Appends a long field.
     *
     * @param fixField the field (tag) to write
     * @param value    the long value
     * @return this encoder, for chaining
     */
    T addLong(FixField fixField, long value);

    /**
     * Appends a floating-point field.
     *
     * @param fixField the field (tag) to write
     * @param value    the double value
     * @return this encoder, for chaining
     */
    T addDouble(FixField fixField, double value);

    /**
     * Appends a decimal field from a {@link DecimalFloat}, the allocation-free decimal representation.
     *
     * @param fixField the field (tag) to write
     * @param value    the decimal value
     * @return this encoder, for chaining
     */
    T addDecimalFloat(FixField fixField, DecimalFloat value);

    /**
     * Appends a decimal field from a {@link BigDecimal}.
     *
     * @param fixField the field (tag) to write
     * @param value    the decimal value
     * @return this encoder, for chaining
     */
    T addBigDecimal(FixField fixField, BigDecimal value);

    /**
     * Appends a boolean field, rendered as the FIX {@code Y}/{@code N} representation.
     *
     * @param fixField the field (tag) to write
     * @param value    the boolean value
     * @return this encoder, for chaining
     */
    T addBoolean(FixField fixField, boolean value);

    /**
     * Appends a string field.
     *
     * @param fixField the field (tag) to write
     * @param value    the string value
     * @return this encoder, for chaining
     */
    T addString(FixField fixField, String value);

    /**
     * Appends a single-character field.
     *
     * @param fixField the field (tag) to write
     * @param value    the character value
     * @return this encoder, for chaining
     */
    T addChar(FixField fixField, char value);

    /**
     * Appends an enumerated field whose underlying FIX representation is an integer.
     *
     * @param fixField the field (tag) to write
     * @param value    the enum value
     * @return this encoder, for chaining
     */
    T addIntEnum(FixField fixField, FixField.IntValuesEnum value);

    /**
     * Appends an enumerated field whose underlying FIX representation is a string.
     *
     * @param fixField the field (tag) to write
     * @param value    the enum value
     * @return this encoder, for chaining
     */
    T addStringEnum(FixField fixField, FixField.StringValuesEnum value);

    /**
     * Appends an enumerated field whose underlying FIX representation is a single character.
     *
     * @param fixField the field (tag) to write
     * @param value    the enum value
     * @return this encoder, for chaining
     */
    T addCharEnum(FixField fixField, FixField.CharValuesEnum value);

    /**
     * Appends a {@link UUID} field.
     *
     * @param fixField the field (tag) to write
     * @param value    the UUID value
     * @return this encoder, for chaining
     */
    T addUUID(FixField fixField, UUID value);

}