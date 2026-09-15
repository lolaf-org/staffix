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
package org.lolaf.staffix.codec.encoders;

import org.lolaf.staffix.api.codec.FixFieldsEncoder;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.serde.DecimalFloat;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.time.UTCTime;

import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The base of every generated repeating-group encoder: it forwards each {@code add*} call to the message encoder the
 * group belongs to, and returns the group encoder itself so the generated setters stay fluent.
 * <p>
 * <b>Why it exists.</b> A group encoder's body is nothing but setters delegating to {@code addString},
 * {@code addInt} and their siblings, so it depends on the group's field list and on nothing else - two messages
 * carrying the same group need the same class. The generator used to emit each group as a non-{@code static} inner
 * class of its message encoder, reaching the message through the implicit outer reference and through a forwarding
 * base generated once per message. That made every copy a distinct type: {@code staffix-fix-latest} held 20,985 group
 * encoder classes with 515 distinct implementations behind them, and 80% of its jar was the duplication. Holding the
 * message encoder in a field instead of an outer reference is what lets one class serve every message that carries
 * the group.
 * <p>
 * <b>Hot path.</b> {@code target} is typed as the implementation class rather than as {@link FixFieldsEncoder}, so
 * each forward is a virtual call to a method no generated encoder overrides, rather than an interface call across 93
 * message encoder types. It is final and set once at construction, as the outer reference it replaces was.
 *
 * @param <T> the concrete group encoder, so that every setter can return it
 */
public abstract class AbstractGroupEncoder<T extends FixFieldsEncoder<T>> implements FixFieldsEncoder<T> {

    /**
     * The message encoder this group belongs to. Every field the group encodes goes into that encoder's buffer, in
     * call order, exactly as the fields set directly on the message do.
     */
    private final FixMessageEncoderImpl<?> target;

    /**
     * {@code this} under the concrete group encoder's own type, computed once rather than cast on every setter
     * return.
     */
    private final T thisInstance;

    @SuppressWarnings("unchecked")
    protected AbstractGroupEncoder(FixMessageEncoderImpl<?> target) {
        this.target = target;
        this.thisInstance = (T) this;
    }

    @Override
    public <V> T addField(FixField fixField, V value, SerDe<V> serializer) {
        target.addField(fixField, value, serializer);
        return thisInstance;
    }

    @Override
    public T addBytes(FixField fixField, byte[] serializedField) {
        target.addBytes(fixField, serializedField);
        return thisInstance;
    }

    @Override
    public T addUUID(FixField fixField, UUID value) {
        target.addUUID(fixField, value);
        return thisInstance;
    }

    @Override
    public T addUtcDateTime(FixField fixField, long epochDay, long nanosOfDay, TimeUnit accuracy) {
        target.addUtcDateTime(fixField, epochDay, nanosOfDay, accuracy);
        return thisInstance;
    }

    @Override
    public T addUtcDateTime(FixField fixField, UTCTime value, TimeUnit accuracy) {
        target.addUtcDateTime(fixField, value, accuracy);
        return thisInstance;
    }

    @Override
    public T addUtcDateTime(FixField fixField, Instant value, TimeUnit accuracy) {
        target.addUtcDateTime(fixField, value, accuracy);
        return thisInstance;
    }

    @Override
    public T addUtcDate(FixField fixField, long epochDay) {
        target.addUtcDate(fixField, epochDay);
        return thisInstance;
    }

    @Override
    public T addUtcDate(FixField fixField, LocalDate value) {
        target.addUtcDate(fixField, value);
        return thisInstance;
    }

    @Override
    public T addUtcTime(FixField fixField, Instant value, TimeUnit accuracy) {
        target.addUtcTime(fixField, value, accuracy);
        return thisInstance;
    }

    @Override
    public T addUtcTime(FixField fixField, UTCTime value, TimeUnit accuracy) {
        target.addUtcTime(fixField, value, accuracy);
        return thisInstance;
    }

    @Override
    public T addUtcTime(FixField fixField, long nanosOfDay, TimeUnit accuracy) {
        target.addUtcTime(fixField, nanosOfDay, accuracy);
        return thisInstance;
    }

    @Override
    public T addUtcTime(FixField fixField, LocalTime value, TimeUnit accuracy) {
        target.addUtcTime(fixField, value, accuracy);
        return thisInstance;
    }

    @Override
    public T addTzTime(FixField fixField, OffsetTime offsetTime) {
        target.addTzTime(fixField, offsetTime);
        return thisInstance;
    }

    @Override
    public T addTzDateTime(FixField fixField, OffsetDateTime offsetDateTime, TimeUnit accuracy) {
        target.addTzDateTime(fixField, offsetDateTime, accuracy);
        return thisInstance;
    }

    @Override
    public T addInt(FixField fixField, int value) {
        target.addInt(fixField, value);
        return thisInstance;
    }

    @Override
    public T addLong(FixField fixField, long value) {
        target.addLong(fixField, value);
        return thisInstance;
    }

    @Override
    public T addDouble(FixField fixField, double value) {
        target.addDouble(fixField, value);
        return thisInstance;
    }

    @Override
    public T addDecimalFloat(FixField fixField, DecimalFloat value) {
        target.addDecimalFloat(fixField, value);
        return thisInstance;
    }

    @Override
    public T addBigDecimal(FixField fixField, BigDecimal value) {
        target.addBigDecimal(fixField, value);
        return thisInstance;
    }

    @Override
    public T addBoolean(FixField fixField, boolean value) {
        target.addBoolean(fixField, value);
        return thisInstance;
    }

    @Override
    public T addString(FixField fixField, String value) {
        target.addString(fixField, value);
        return thisInstance;
    }

    @Override
    public T addChar(FixField fixField, char value) {
        target.addChar(fixField, value);
        return thisInstance;
    }

    @Override
    public T addIntEnum(FixField fixField, FixField.IntValuesEnum value) {
        target.addIntEnum(fixField, value);
        return thisInstance;
    }

    @Override
    public T addStringEnum(FixField fixField, FixField.StringValuesEnum value) {
        target.addStringEnum(fixField, value);
        return thisInstance;
    }

    @Override
    public T addCharEnum(FixField fixField, FixField.CharValuesEnum value) {
        target.addCharEnum(fixField, value);
        return thisInstance;
    }
}
