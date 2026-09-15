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
package org.lolaf.staffix.tests;

import org.lolaf.staffix.api.fields.CoreFields;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the fields out of the raw text of a FIX message, so that tests can match on fields rather than on substrings
 * of the wire format. Shared by {@link FixMessageAssert} and by the message reading of
 * {@link RawFixSocketClient.Session}.
 * <p>
 * The parsing is deliberately naive: the text is split on SOH and each token on its first {@code =}. It is meant for
 * the session level messages exchanged by the tests, not for messages carrying a data field whose value may itself
 * contain an SOH. Any text is accepted, including one holding several consecutive messages, in which case the fields
 * of all of them are returned.
 */
public final class FixMessageFields {

    private FixMessageFields() {
    }

    static List<Field> parse(String message) {
        List<Field> parsed = new ArrayList<>();
        if (message == null) {
            return parsed;
        }
        for (String token : message.split(String.valueOf(CoreFields.FIELD_SEPARATOR))) {
            int equals = token.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            try {
                parsed.add(new Field(Integer.parseInt(token.substring(0, equals)), token.substring(equals + 1)));
            } catch (NumberFormatException notAFixField) {
                // not a tag=value token, ignore it: the tests also feed in garbled messages on purpose
            }
        }
        return parsed;
    }

    public static List<String> valuesOf(String message, int tag) {
        List<String> values = new ArrayList<>();
        for (Field field : parse(message)) {
            if (field.getTag() == tag) {
                values.add(field.getValue());
            }
        }
        return values;
    }

    public static boolean hasFieldWithValue(String message, int tag, String value) {
        return valuesOf(message, tag).contains(value);
    }

    static final class Field {

        private final int tag;
        private final String value;

        private Field(int tag, String value) {
            this.tag = tag;
            this.value = value;
        }

        int getTag() {
            return tag;
        }

        String getValue() {
            return value;
        }
    }
}
