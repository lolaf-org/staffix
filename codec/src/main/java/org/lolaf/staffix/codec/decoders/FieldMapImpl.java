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
package org.lolaf.staffix.codec.decoders;

import lombok.Getter;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.FixFieldMap;
import org.lolaf.staffix.api.serde.DecimalFloat;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.codec.serde.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Fields by tag, in the order they arrived.
 *
 * <p>Insertion-ordered because FIX fixes the order of the header and of a repeating group's entries, and a
 * message re-encoded from this map has to come out the way it went in.
 */
public class FieldMapImpl implements FixFieldMap {

    @Getter
    private final Map<FixField, Object> fieldsValues;
    private SerDe.DeserializationContext ctx;

    public FieldMapImpl(Map<FixField, Object> fieldsValues) {
        this.fieldsValues = new LinkedHashMap<>(fieldsValues);
    }

    public FieldMapImpl() {
        // linked map to keep insertion order
        this(new LinkedHashMap<>());
    }

    @Override
    public void clear() {
        fieldsValues.clear();
        if (ctx != null) {
            ctx.clean();
            ctx = null;
        }
    }

    private SerDe.DeserializationContext setupCtx(byte[] value) {
        if (ctx == null) {
            ctx = new DeserializationContextImpl();
        }
        return ctx.setup(value);
    }

    @Override
    public <T> T remove(FixField field) {
        return (T) fieldsValues.remove(field);
    }

    @Override
    public void add(FixField field, byte[] value) {
        fieldsValues.put(field, value);
    }

    @Override
    public GroupFixFieldMap addGroup(FixField groupField, int entriesCount) {
        GroupFixFieldMap groupFixFieldMap = new GroupFixFieldMapImpl(groupField, entriesCount, this);
        Object oldGroup = fieldsValues.put(groupFixFieldMap.getGroupField(), groupFixFieldMap);
        if (oldGroup != null) {
            throw new IllegalStateException("Group already exists " + groupField);
        }
        return groupFixFieldMap;
    }

    @Override
    public GroupFixFieldMap getGroup(FixField groupField) {
        return (GroupFixFieldMap) fieldsValues.get(groupField);
    }

    @Override
    public void foreach(BiConsumer<FixField, byte[]> consumer) {
        fieldsValues.forEach((field, value) -> {
            if (value instanceof GroupFixFieldMap) {
                GroupFixFieldMap groupFixFieldMap = (GroupFixFieldMap) value;
                byte[] serializedNumGroup = IntSerde.serialize(groupFixFieldMap.getEntries().size());
                consumer.accept(groupFixFieldMap.getGroupField(), serializedNumGroup);
                groupFixFieldMap.getEntries().forEach(e -> e.foreach(consumer));
            } else {
                consumer.accept(field, (byte[]) value);
            }
        });
    }

    @Override
    public <T> T getValue(int fieldCode, SerDe<T> deserializer, T defaultValue) {
        byte[] value = fieldsValues.entrySet().stream()
                .filter(e -> e.getKey().getCode() == fieldCode)
                .findFirst().map(e -> (byte[]) e.getValue()).orElse(null);
        return value != null ? deserializer.deserialize(setupCtx(value)) : defaultValue;
    }

    @Override
    public <T> T getValue(FixField field, SerDe<T> deserializer, T defaultValue) {
        byte[] value = (byte[]) fieldsValues.get(field);
        return value != null ? deserializer.deserialize(setupCtx(value)) : defaultValue;
    }

    @Override
    public UTCTime getUTCDateTime(FixField field, UTCTime defaultValue) {
        byte[] value = (byte[]) fieldsValues.get(field);
        return value != null ? UtcDateTimeSerde.deserialize(value) : defaultValue;
    }

    @Override
    public String getString(FixField field, String defaultValue) {
        byte[] value = (byte[]) fieldsValues.get(field);
        return value != null ? StringSerde.instance().deserialize(setupCtx(value)) : defaultValue;
    }

    @Override
    public int getInt(FixField field, int defaultValue) {
        byte[] value = (byte[]) fieldsValues.get(field);
        return value != null ? IntSerde.deserialize(value) : defaultValue;
    }

    @Override
    public long getLong(FixField field, long defaultValue) {
        byte[] value = (byte[]) fieldsValues.get(field);
        return value != null ? LongSerde.deserialize(value) : defaultValue;
    }

    @Override
    public double getDouble(FixField field, double defaultValue) {
        byte[] value = (byte[]) fieldsValues.get(field);
        return value != null ? DoubleSerde.deserialize(value) : defaultValue;
    }

    @Override
    public DecimalFloat getDecimalFloat(FixField field, DecimalFloat defaultValue) {
        return getValue(field, DecimalFloatSerde.instance(), defaultValue);
    }

    @Override
    public BigDecimal getBigDecimal(FixField field, BigDecimal defaultValue) {
        return getValue(field, BigDecimalSerde.instance(), defaultValue);
    }

    @Override
    public Boolean getBoolean(FixField field, Boolean defaultValue) {
        byte[] value = (byte[]) fieldsValues.get(field);
        if (value == null) {
            return defaultValue;
        }
        return BooleanSerde.deserialize(setupCtx(value));
    }

    @Override
    public char getChar(FixField field, char defaultValue) {
        byte[] value = (byte[]) fieldsValues.get(field);
        return value != null ? CharSerde.deserialize(setupCtx(value)) : defaultValue;
    }

    @Override
    public boolean containsField(FixField field) {
        return fieldsValues.containsKey(field);
    }

    @Getter
    private static class GroupFixFieldMapImpl implements GroupFixFieldMap {
        private final FixField groupField;
        private final Collection<FixFieldMap> entries;
        private final FixFieldMap parent;

        public GroupFixFieldMapImpl(FixField groupField, int entriesCount, FixFieldMap parent) {
            this.groupField = groupField;
            this.entries = new ArrayList<>(entriesCount);
            this.parent = parent;
        }

        @Override
        public FixFieldMap addEntry() {
            FieldMapImpl newEntry = new FieldMapImpl();
            entries.add(newEntry);
            return newEntry;
        }
    }
}