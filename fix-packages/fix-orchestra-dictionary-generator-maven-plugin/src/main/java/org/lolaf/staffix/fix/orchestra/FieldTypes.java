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
package org.lolaf.staffix.fix.orchestra;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The datatype and code names of an orchestration, turned into what a FIX dictionary spells them.
 * <p>
 * Two conversions, both of them lossless in practice and both checked against the FIX Latest EP300 repository and
 * the dictionaries this engine already ships.
 */
final class FieldTypes {

    /**
     * The type names a dictionary may carry, which are the constants of {@code org.lolaf.staffix.api.fields.FieldType}
     * - the encoders generator does {@code FieldType.valueOf(field.getAttribute("type"))}, so anything outside this
     * set fails the build that consumes what is written here rather than this one.
     * <p>
     * Held as names rather than as the enum: this plugin runs inside Maven, against whatever version of the engine
     * the reactor is building, and a plugin that depended on the api would pin a version of it.
     */
    private static final Set<String> DICTIONARY_TYPES = new HashSet<>(Arrays.asList(
            "STRING", "CHAR", "PRICE", "INT", "AMT", "QTY", "CURRENCY", "MULTIPLEVALUESTRING", "MULTIPLESTRINGVALUE",
            "MULTIPLECHARVALUE", "EXCHANGE", "BOOLEAN", "DATA", "FLOAT", "PRICEOFFSET", "MONTHYEAR", "DAYOFMONTH",
            "UTCTIMESTAMP", "TZTIMESTAMP", "UTCDATEONLY", "UTCDATE", "LOCALMKTDATE", "LOCALMKTTIME", "UTCTIMEONLY",
            "TZTIMEONLY", "NUMINGROUP", "PERCENTAGE", "SEQNUM", "LENGTH", "COUNTRY", "LANGUAGE", "XMLDATA", "TAGNUM",
            "XID", "XIDREF"));

    private FieldTypes() {
    }

    /**
     * An Orchestra datatype name as a dictionary type. The two vocabularies differ only in case - {@code UTCTimestamp}
     * against {@code UTCTIMESTAMP}, {@code char} against {@code CHAR} - for every datatype the repository actually
     * uses on a field, which is 30 of the 38 it declares. The rest ({@code Pattern}, {@code Tenor}, the
     * {@code Reserved*Plus} union markers) only ever appear as a base datatype or a union type, never as the type of
     * a field, so a name outside the set is a real gap and says so rather than being quietly turned into a String -
     * a field decoded as the wrong type corrupts parsing far from here.
     */
    static String toDictionaryType(String orchestraDatatype, String fieldName) {
        String candidate = orchestraDatatype.toUpperCase(Locale.ROOT);
        if (!DICTIONARY_TYPES.contains(candidate)) {
            throw new IllegalStateException("Datatype " + orchestraDatatype + " of field " + fieldName
                    + " has no FIX dictionary type, add the mapping rather than letting the field be mistyped");
        }
        return candidate;
    }

    /**
     * A code name as the {@code description} of a dictionary value, which is what the encoders generator turns into
     * the name of an enum constant: {@code Buy} becomes {@code BUY}, {@code BuyMinus} becomes {@code BUY_MINUS}, and
     * a run of capitals stays together so {@code FIXMLMessage} becomes {@code FIXML_MESSAGE} rather than
     * {@code F_I_X_M_L_MESSAGE}.
     */
    static String toValueDescription(String codeName) {
        StringBuilder description = new StringBuilder(codeName.length() + 4);
        for (int i = 0; i < codeName.length(); i++) {
            char current = codeName.charAt(i);
            if (!Character.isLetterOrDigit(current)) {
                appendSeparator(description);
                continue;
            }
            if (i > 0 && startsAWord(codeName, i)) {
                appendSeparator(description);
            }
            description.append(Character.toUpperCase(current));
        }
        return description.toString();
    }

    /**
     * Whether the character at {@code index} opens a new word: an upper case letter after something lower case or a
     * digit, or the last upper case of a run that turns out to be a word of its own - the M of {@code FIXMLMessage}.
     */
    private static boolean startsAWord(String name, int index) {
        char current = name.charAt(index);
        if (!Character.isUpperCase(current)) {
            return false;
        }
        char previous = name.charAt(index - 1);
        if (Character.isLowerCase(previous) || Character.isDigit(previous)) {
            return true;
        }
        return Character.isUpperCase(previous)
                && index + 1 < name.length()
                && Character.isLowerCase(name.charAt(index + 1));
    }

    private static void appendSeparator(StringBuilder description) {
        if (description.length() > 0 && description.charAt(description.length() - 1) != '_') {
            description.append('_');
        }
    }
}
