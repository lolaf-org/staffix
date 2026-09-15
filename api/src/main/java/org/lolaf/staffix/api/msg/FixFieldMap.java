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

    void clear();

    void foreach(BiConsumer<FixField, byte[]> consumer);

    void add(FixField field, byte[] value);

    GroupFixFieldMap addGroup(FixField groupField, int entriesCount);

    GroupFixFieldMap getGroup(FixField groupField);

    <T> T remove(FixField field);

    <T> T getValue(int fieldCode, SerDe<T> deserializer, T defaultValue);

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

    interface GroupFixFieldMap {

        FixField getGroupField();

        FixFieldMap addEntry();

        Collection<FixFieldMap> getEntries();

        FixFieldMap getParent();
    }
}
