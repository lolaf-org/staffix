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
package org.lolaf.staffix.api.fields;

import lombok.Getter;
import lombok.ToString;

/**
 * The FIX data types a field may have, which decide the serde that reads and writes it.
 *
 * <p>{@link #of(String)} maps the names as the dictionary XML spells them, so a dictionary is the authority on
 * a field's type rather than this enum.
 */
@Getter
@ToString
public enum FieldType {

    STRING,
    CHAR,
    PRICE,
    INT,
    AMT,
    QTY,
    CURRENCY,
    MULTIPLEVALUESTRING,
    MULTIPLESTRINGVALUE,
    MULTIPLECHARVALUE,
    EXCHANGE,
    BOOLEAN,
    DATA,
    FLOAT,
    PRICEOFFSET,
    MONTHYEAR,
    DAYOFMONTH,
    UTCTIMESTAMP,
    TZTIMESTAMP,
    UTCDATEONLY,
    UTCDATE,
    LOCALMKTDATE,
    LOCALMKTTIME,
    UTCTIMEONLY,
    TZTIMEONLY,
    NUMINGROUP,
    PERCENTAGE,
    SEQNUM,
    LENGTH,
    COUNTRY,
    LANGUAGE,
    XMLDATA, // not managed
    TAGNUM,
    XID,
    XIDREF,
    /**
     * Case for an unknown field (received field not in dictionary)
     */
    UNKNOWN;

    public static FieldType of(String name) {
        try {
            return FieldType.valueOf(name);
        } catch (IllegalArgumentException iae) {
            throw new IllegalStateException("Unknown field type " + name);
        }
    }

}