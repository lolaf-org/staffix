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
package org.lolaf.staffix.generator;

import lombok.Getter;
import lombok.ToString;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.time.UTCTime;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;

/**
 * How each FIX field type maps to a Java type and the serde that reads it.
 */
@Getter
@ToString
public enum MappedFieldType {

    // see https://fiximate.fixtrading.org/en/FIX.Latest/fix_datatypes.html

    STRING,
    CHAR,
    PRICE(double.class),
    INT(int.class),
    AMT(double.class),
    QTY(double.class),
    CURRENCY,
    MULTIPLEVALUESTRING,
    MULTIPLESTRINGVALUE,
    MULTIPLECHARVALUE,
    EXCHANGE,
    BOOLEAN(boolean.class),
    LOCALMKTDATE(LocalDate.class),
    LOCALMKTTIME(LocalTime.class),
    DATA,
    FLOAT(double.class),
    PRICEOFFSET(double.class),
    MONTHYEAR,
    DAYOFMONTH(int.class),
    UTCTIMESTAMP(UTCTime.class),
    TZTIMESTAMP(OffsetDateTime.class),
    UTCDATEONLY(LocalDate.class),
    UTCDATE(LocalDate.class),
    UTCTIMEONLY(LocalTime.class),
    TZTIMEONLY(OffsetTime.class),
    NUMINGROUP(int.class),
    PERCENTAGE(double.class),
    SEQNUM(long.class),
    LENGTH(int.class),
    COUNTRY,
    LANGUAGE,
    XMLDATA,
    TAGNUM(int.class),
    XID,
    XIDREF;

    private final Class<?> type;

    MappedFieldType(Class<?> type) {
        this.type = type;
    }

    MappedFieldType() {
        this(String.class);
    }

    public static MappedFieldType of(FieldType ft) {
        return MappedFieldType.valueOf(ft.name());
    }
}