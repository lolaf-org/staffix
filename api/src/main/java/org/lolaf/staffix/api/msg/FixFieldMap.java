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
package org.lolaf.staffix.api.msg;

import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.serde.DecimalFloat;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.time.UTCTime;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.function.BiConsumer;

/**
 * The fields of a message, by tag, including repeating groups.
 *
 * <p>The typed getters take a default rather than returning null or throwing, because an absent optional field
 * is ordinary in FIX and forcing every caller to branch on it would be noise. {@link #foreach} exists for the
 * cases that want every field without asking for each one.
 */
public interface FixFieldMap {

    /**
     * Removes every field and group, so the map can be reused.
     */
    void clear();

    /**
     * Visits every field in the order it was added, with its raw value. A group is visited as on the wire: its
     * NumInGroup field with the entry count, then each entry's fields.
     */
    void foreach(BiConsumer<FixField, byte[]> consumer);

    /**
     * Sets a field's raw value, replacing any value the field already had.
     */
    void add(FixField field, byte[] value);

    /**
     * Adds a repeating group, to be filled with {@link GroupFixFieldMap#addEntry()}.
     *
     * @param groupField   the group's NumInGroup field
     * @param entriesCount how many entries are expected, which sizes the group up front
     * @throws IllegalStateException if the map already holds that group
     */
    GroupFixFieldMap addGroup(FixField groupField, int entriesCount);

    /**
     * The group, or null if the map does not hold it.
     */
    GroupFixFieldMap getGroup(FixField groupField);

    /**
     * Removes a field or a group and returns what it held: the raw {@code byte[]} value, or the
     * {@link GroupFixFieldMap}. Null if absent.
     */
    <T> T remove(FixField field);

    /**
     * As {@link #getValue(FixField, SerDe, Object)}, looked up by tag number. It scans every field, so prefer the
     * {@link FixField} overload on a hot path.
     */
    <T> T getValue(int fieldCode, SerDe<T> deserializer, T defaultValue);

    /**
     * Reads the field with the given deserializer, for a type the typed getters do not cover.
     *
     * @return the value, or {@code defaultValue} if the field is absent
     */
    <T> T getValue(FixField field, SerDe<T> deserializer, T defaultValue);

    UTCTime getUTCDateTime(FixField field, UTCTime defaultValue);

    String getString(FixField field, String defaultValue);

    int getInt(FixField field, int defaultValue);

    long getLong(FixField field, long defaultValue);

    double getDouble(FixField field, double defaultValue);

    DecimalFloat getDecimalFloat(FixField field, DecimalFloat defaultValue);

    BigDecimal getBigDecimal(FixField field, BigDecimal defaultValue);

    char getChar(FixField field, char defaultValue);

    Boolean getBoolean(FixField field, Boolean defaultValue);

    boolean containsField(FixField field);

    /**
     * A repeating group: its NumInGroup field and its entries, each a {@link FixFieldMap} of its own.
     */
    interface GroupFixFieldMap {

        /**
         * The NumInGroup field that counts the entries.
         */
        FixField getGroupField();

        /**
         * Appends an empty entry and returns it for filling.
         */
        FixFieldMap addEntry();

        /**
         * The entries, in the order they were added.
         */
        Collection<FixFieldMap> getEntries();

        /**
         * The map holding this group, for walking back up out of a nested one.
         */
        FixFieldMap getParent();
    }
}
