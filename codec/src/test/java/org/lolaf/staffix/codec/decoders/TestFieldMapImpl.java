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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.FixFieldMap;
import org.lolaf.staffix.api.serde.DecimalFloat;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.codec.serde.*;
import org.lolaf.staffix.tests.fix44.fields.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestFieldMapImpl {

    private FieldMapImpl fieldMap;

    @BeforeEach
    void setup() {
        fieldMap = new FieldMapImpl();
    }

    @Test
    void testEmptyMapReturnsDefaultsAndDoesNotContainAnyField() {
        assertThat(fieldMap.getFieldsValues()).isEmpty();
        assertThat(fieldMap.containsField(Symbol.get())).isFalse();
        assertThat(fieldMap.getString(Symbol.get(), "default")).isEqualTo("default");
        assertThat(fieldMap.getInt(MarketDepth.get(), -1)).isEqualTo(-1);
        assertThat(fieldMap.getLong(OrderQty.get(), -42L)).isEqualTo(-42L);
        assertThat(fieldMap.getDouble(MDEntryPx.get(), 1.5d)).isEqualTo(1.5d);
        assertThat(fieldMap.getChar(MDEntryType.get(), 'X')).isEqualTo('X');
        assertThat(fieldMap.getBoolean(MDReqID.get(), Boolean.TRUE)).isTrue();
        assertThat(fieldMap.getBigDecimal(OrderQty.get(), BigDecimal.ONE)).isEqualTo(BigDecimal.ONE);
        DecimalFloat decimalDefault = DecimalFloat.of(123L, (byte) 2);
        assertThat(fieldMap.getDecimalFloat(MDEntryPx.get(), decimalDefault)).isSameAs(decimalDefault);
        UTCTime utcDefault = UTCTime.ImmutableTimeImpl.from(0L, 0);
        assertThat(fieldMap.getUTCDateTime(TransactTime.get(), utcDefault)).isSameAs(utcDefault);
    }

    @Test
    void testAddAndContainsField() {
        fieldMap.add(Symbol.get(), "FOO".getBytes());

        assertThat(fieldMap.containsField(Symbol.get())).isTrue();
        assertThat(fieldMap.containsField(MarketDepth.get())).isFalse();
        assertThat(fieldMap.getFieldsValues()).hasSize(1).containsKey(Symbol.get());
    }

    @Test
    void testGetStringReturnsStoredValue() {
        fieldMap.add(Symbol.get(), "FOO".getBytes());

        assertThat(fieldMap.getString(Symbol.get(), "default")).isEqualTo("FOO");
    }

    @Test
    void testGetIntReturnsStoredValue() {
        fieldMap.add(MarketDepth.get(), IntSerde.serialize(7));

        assertThat(fieldMap.getInt(MarketDepth.get(), -1)).isEqualTo(7);
    }

    @Test
    void testGetLongReturnsStoredValue() {
        fieldMap.add(OrderQty.get(), LongSerde.serialize(123_456_789L));

        assertThat(fieldMap.getLong(OrderQty.get(), -1L)).isEqualTo(123_456_789L);
    }

    @Test
    void testGetDoubleReturnsStoredValue() {
        fieldMap.add(MDEntryPx.get(), DoubleSerde.serialize(42.5d));

        assertThat(fieldMap.getDouble(MDEntryPx.get(), -1d)).isEqualTo(42.5d);
    }

    @Test
    void testGetCharReturnsStoredValue() {
        fieldMap.add(MDEntryType.get(), new byte[]{CharSerde.serialize('A')});

        assertThat(fieldMap.getChar(MDEntryType.get(), 'Z')).isEqualTo('A');
    }

    @Test
    void testGetBooleanReturnsStoredValue() {
        fieldMap.add(MDReqID.get(), new byte[]{BooleanSerde.serialize(true)});

        assertThat(fieldMap.getBoolean(MDReqID.get(), Boolean.FALSE)).isTrue();
    }

    @Test
    void testGetBigDecimalReturnsStoredValue() {
        fieldMap.add(MDEntryPx.get(), "12.34".getBytes());

        assertThat(fieldMap.getBigDecimal(MDEntryPx.get(), BigDecimal.ZERO))
                .isEqualByComparingTo(new BigDecimal("12.34"));
    }

    @Test
    void testGetDecimalFloatReturnsStoredValue() {
        fieldMap.add(MDEntryPx.get(), "1.25".getBytes());

        DecimalFloat result = fieldMap.getDecimalFloat(MDEntryPx.get(), DecimalFloat.of(0, (byte) 0));
        assertThat(result).isNotNull();
        BigDecimal asBigDecimal = BigDecimal.valueOf(result.getUnscaledValue(), result.getScale());
        assertThat(asBigDecimal).isEqualByComparingTo(new BigDecimal("1.25"));
    }

    @Test
    void testGetUTCDateTimeReturnsStoredValue() {
        Instant instant = Instant.parse("2024-12-24T01:02:03.456789Z");
        byte[] serialized = UtcDateTimeSerde.serializeInstant(instant, TimeUnit.MICROSECONDS);
        fieldMap.add(TransactTime.get(), serialized);

        UTCTime result = fieldMap.getUTCDateTime(TransactTime.get(), null);
        assertThat(result).isNotNull();
        assertThat(result.getEpochSeconds()).isEqualTo(instant.getEpochSecond());
        assertThat(result.getNanosOfSecond()).isEqualTo(instant.getNano());
    }

    @Test
    void testGetValueByFieldUsesDeserializer() {
        fieldMap.add(Symbol.get(), "FOO".getBytes());

        String result = fieldMap.getValue(Symbol.get(), StringSerde.instance(), "default");
        assertThat(result).isEqualTo("FOO");
    }

    @Test
    void testGetValueByFieldReturnsDefaultWhenAbsent() {
        String result = fieldMap.getValue(Symbol.get(), StringSerde.instance(), "default");
        assertThat(result).isEqualTo("default");
    }

    @Test
    void testGetValueByFieldCodeLooksUpByCode() {
        fieldMap.add(Symbol.get(), "FOO".getBytes());

        String result = fieldMap.getValue(Symbol.get().getCode(), StringSerde.instance(), "default");
        assertThat(result).isEqualTo("FOO");
    }

    @Test
    void testGetValueByFieldCodeReturnsDefaultWhenAbsent() {
        fieldMap.add(Symbol.get(), "FOO".getBytes());

        String result = fieldMap.getValue(99999, StringSerde.instance(), "default");
        assertThat(result).isEqualTo("default");
    }

    @Test
    void testRemoveReturnsValueAndDeletesEntry() {
        byte[] value = "FOO".getBytes();
        fieldMap.add(Symbol.get(), value);

        Object removed = fieldMap.remove(Symbol.get());

        assertThat(removed).isSameAs(value);
        assertThat(fieldMap.containsField(Symbol.get())).isFalse();
    }

    @Test
    void testRemoveReturnsNullWhenAbsent() {
        Object removed = fieldMap.remove(Symbol.get());
        assertThat(removed).isNull();
    }

    @Test
    void testAddOverwritesPreviousValue() {
        fieldMap.add(Symbol.get(), "FOO".getBytes());
        fieldMap.add(Symbol.get(), "BAR".getBytes());

        assertThat(fieldMap.getString(Symbol.get(), null)).isEqualTo("BAR");
        assertThat(fieldMap.getFieldsValues()).hasSize(1);
    }

    @Test
    void testAddGroupReturnsGroupAndStoresIt() {
        FixFieldMap.GroupFixFieldMap group = fieldMap.addGroup(NoMDEntries.get(), 3);

        assertThat(group).isNotNull();
        assertThat(group.getGroupField()).isEqualTo(NoMDEntries.get());
        assertThat(group.getEntries()).isEmpty();
        assertThat(group.getParent()).isSameAs(fieldMap);
        assertThat(fieldMap.getGroup(NoMDEntries.get())).isSameAs(group);
        assertThat(fieldMap.containsField(NoMDEntries.get())).isTrue();
    }

    @Test
    void testAddGroupTwiceThrows() {
        fieldMap.addGroup(NoMDEntries.get(), 1);

        assertThatThrownBy(() -> fieldMap.addGroup(NoMDEntries.get(), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Group already exists");
    }

    @Test
    void testGetGroupReturnsNullWhenAbsent() {
        assertThat(fieldMap.getGroup(NoMDEntries.get())).isNull();
    }

    @Test
    void testGroupAddEntryAppendsEntries() {
        FixFieldMap.GroupFixFieldMap group = fieldMap.addGroup(NoMDEntries.get(), 2);

        FixFieldMap entry1 = group.addEntry();
        FixFieldMap entry2 = group.addEntry();

        assertThat(entry1).isNotNull().isNotSameAs(entry2);
        assertThat(group.getEntries()).hasSize(2).containsExactly(entry1, entry2);
    }

    @Test
    void testClearRemovesAllFieldsAndGroups() {
        fieldMap.add(Symbol.get(), "FOO".getBytes());
        FixFieldMap.GroupFixFieldMap group = fieldMap.addGroup(NoMDEntries.get(), 1);
        group.addEntry().add(MDEntryType.get(), new byte[]{CharSerde.serialize('0')});
        // force lazy ctx init via a deserialization read
        fieldMap.getString(Symbol.get(), null);

        fieldMap.clear();

        assertThat(fieldMap.getFieldsValues()).isEmpty();
        assertThat(fieldMap.containsField(Symbol.get())).isFalse();
        assertThat(fieldMap.getGroup(NoMDEntries.get())).isNull();
    }

    @Test
    void testClearOnEmptyMapDoesNotThrow() {
        fieldMap.clear();
        assertThat(fieldMap.getFieldsValues()).isEmpty();
    }

    @Test
    void testForeachIteratesScalarsAndExpandsGroup() {
        fieldMap.add(Symbol.get(), "FOO".getBytes());
        FixFieldMap.GroupFixFieldMap group = fieldMap.addGroup(NoMDEntries.get(), 2);
        FixFieldMap entry1 = group.addEntry();
        entry1.add(MDEntryType.get(), new byte[]{CharSerde.serialize('0')});
        FixFieldMap entry2 = group.addEntry();
        entry2.add(MDEntryType.get(), new byte[]{CharSerde.serialize('1')});
        fieldMap.add(MarketDepth.get(), IntSerde.serialize(5));

        List<FixField> fieldsOrder = new ArrayList<>();
        Map<FixField, byte[]> seen = new HashMap<>();
        AtomicInteger groupCountSeen = new AtomicInteger();

        BiConsumer<FixField, byte[]> consumer = (field, value) -> {
            fieldsOrder.add(field);
            if (field == NoMDEntries.get()) {
                groupCountSeen.set(IntSerde.deserialize(value));
            } else {
                seen.put(field, value);
            }
        };
        fieldMap.foreach(consumer);

        assertThat(fieldsOrder.subList(0, 2)).containsExactly(Symbol.get(), NoMDEntries.get());
        assertThat(fieldsOrder).contains(MarketDepth.get());
        assertThat(groupCountSeen.get()).isEqualTo(2);
        // Scalar fields and the two MDEntryType fields from the group entries are emitted
        assertThat(fieldsOrder).filteredOn(f -> f == MDEntryType.get()).hasSize(2);
        assertThat(seen.get(Symbol.get())).isEqualTo("FOO".getBytes());
        assertThat(IntSerde.deserialize(seen.get(MarketDepth.get()))).isEqualTo(5);
    }

    @Test
    void testConstructorFromMapCopiesEntries() {
        Map<FixField, Object> source = new HashMap<>();
        byte[] symbolValue = "FOO".getBytes();
        source.put(Symbol.get(), symbolValue);

        FieldMapImpl copy = new FieldMapImpl(source);

        assertThat(copy.getFieldsValues()).containsKey(Symbol.get());
        // Mutating the source must not affect the copy
        source.remove(Symbol.get());
        assertThat(copy.containsField(Symbol.get())).isTrue();
        assertThat(copy.getString(Symbol.get(), null)).isEqualTo("FOO");
    }

    @Test
    void testInsertionOrderIsPreserved() {
        fieldMap.add(MarketDepth.get(), IntSerde.serialize(1));
        fieldMap.add(Symbol.get(), "FOO".getBytes());
        fieldMap.addGroup(NoMDEntries.get(), 1);
        fieldMap.add(MDReqID.get(), "req".getBytes());

        assertThat(fieldMap.getFieldsValues().keySet())
                .containsExactly(MarketDepth.get(), Symbol.get(), NoMDEntries.get(), MDReqID.get());
    }
}
