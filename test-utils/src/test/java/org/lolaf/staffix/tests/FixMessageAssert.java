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

import org.assertj.core.api.AbstractAssert;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.MessageType;

import java.util.List;

/**
 * AssertJ assertions on the raw text of a received FIX message, asserting on its <em>fields</em> rather than on
 * substrings of the wire format:
 * <pre>{@code
 * assertThatFixMessage(reply)
 *         .hasMsgType(MessageTypes.Heartbeat)
 *         .containsFieldWithValue(TestReqID.get(), "hello")
 *         .doesNotContainField(SessionRejectReason.get());
 * }</pre>
 * Beyond reading better than {@code assertThat(reply).contains("35=0")}, this avoids the false matches a substring
 * check is exposed to: {@code contains("373=1")} is also satisfied by {@code 373=10}, and {@code contains("35=0")} by
 * any field whose tag ends in 35 or whose value happens to contain that text.
 * <p>
 * The parsing is deliberately naive: the text is split on SOH and each token on its first {@code =}. It is meant for
 * the session level messages exchanged by the tests, not for messages carrying a data field whose value may itself
 * contain an SOH. Any text is accepted, including one holding several consecutive messages, in which case the
 * assertions apply to the fields of all of them.
 */
public final class FixMessageAssert extends AbstractAssert<FixMessageAssert, String> {

    private static final char SOH = CoreFields.FIELD_SEPARATOR;

    private FixMessageAssert(String actual) {
        super(actual, FixMessageAssert.class);
    }

    public static FixMessageAssert assertThatFixMessage(String actual) {
        return new FixMessageAssert(actual);
    }

    private static String label(FixField field) {
        return field.getClass().getSimpleName() + "(" + field.getCode() + ")";
    }

    /**
     * Renders the SOH separators so that a failure message stays readable in a terminal.
     */
    private static String printable(String message) {
        return message == null ? "null" : message.replace(SOH, '|');
    }

    /**
     * Asserts the message carries the given field with exactly the given value.
     */
    public FixMessageAssert containsFieldWithValue(FixField field, String value) {
        return containsFieldWithValue(field.getCode(), label(field), value);
    }

    /**
     * Asserts the message carries the given tag with exactly the given value, for the tags having no generated
     * {@link FixField} constant at hand.
     */
    public FixMessageAssert containsFieldWithValue(int tag, String value) {
        return containsFieldWithValue(tag, "tag(" + tag + ")", value);
    }

    private FixMessageAssert containsFieldWithValue(int tag, String label, String value) {
        isNotNull();
        if (valuesOf(tag).stream().noneMatch(value::equals)) {
            failWithMessage("%nExpecting message to contain field %s with value:%n  <%s>%nbut %s%nmessage was:%n  <%s>",
                    label, value, describeActualValues(tag), printable(actual));
        }
        return this;
    }

    /**
     * Asserts the message carries the given field with a value starting with the given prefix. Useful for the
     * Text(58) field of a Reject, whose diagnostic wording is asserted only by its beginning.
     */
    public FixMessageAssert containsFieldWithValueStartingWith(FixField field, String prefix) {
        isNotNull();
        if (valuesOf(field.getCode()).stream().noneMatch(value -> value.startsWith(prefix))) {
            failWithMessage("%nExpecting message to contain field %s with a value starting with:%n  <%s>%nbut %s%nmessage was:%n  <%s>",
                    label(field), prefix, describeActualValues(field.getCode()), printable(actual));
        }
        return this;
    }

    /**
     * Asserts the message carries the given field with a value holding the given text anywhere in it. For the
     * diagnostics two engines word differently around the same substance.
     */
    public FixMessageAssert containsFieldWithValueContaining(FixField field, String text) {
        return containsFieldWithValueContaining(field.getCode(), label(field), text);
    }

    /**
     * Asserts the message carries the given tag with a value holding the given text anywhere in it, for the tags
     * having no generated {@link FixField} constant at hand.
     */
    public FixMessageAssert containsFieldWithValueContaining(int tag, String text) {
        return containsFieldWithValueContaining(tag, "tag(" + tag + ")", text);
    }

    private FixMessageAssert containsFieldWithValueContaining(int tag, String label, String text) {
        isNotNull();
        if (valuesOf(tag).stream().noneMatch(value -> value.contains(text))) {
            failWithMessage("%nExpecting message to contain field %s with a value holding:%n  <%s>%nbut %s%nmessage was:%n  <%s>",
                    label, text, describeActualValues(tag), printable(actual));
        }
        return this;
    }

    /**
     * Asserts the message carries the given field, whatever its value.
     */
    public FixMessageAssert containsField(FixField field) {
        return containsField(field.getCode(), label(field));
    }

    /**
     * Asserts the message carries the given tag, whatever its value.
     */
    public FixMessageAssert containsField(int tag) {
        return containsField(tag, "tag(" + tag + ")");
    }

    private FixMessageAssert containsField(int tag, String label) {
        isNotNull();
        if (valuesOf(tag).isEmpty()) {
            failWithMessage("%nExpecting message to contain field %s but it does not%nmessage was:%n  <%s>",
                    label, printable(actual));
        }
        return this;
    }

    /**
     * Asserts the message does not carry the given field at all.
     */
    public FixMessageAssert doesNotContainField(FixField field) {
        return doesNotContainField(field.getCode(), label(field));
    }

    /**
     * Asserts the message does not carry the given tag at all.
     */
    public FixMessageAssert doesNotContainField(int tag) {
        return doesNotContainField(tag, "tag(" + tag + ")");
    }

    private FixMessageAssert doesNotContainField(int tag, String label) {
        isNotNull();
        List<String> values = valuesOf(tag);
        if (!values.isEmpty()) {
            failWithMessage("%nExpecting message not to contain field %s but it holds value(s):%n  <%s>%nmessage was:%n  <%s>",
                    label, String.join(", ", values), printable(actual));
        }
        return this;
    }

    /**
     * Asserts the message does not carry the given field with that particular value.
     */
    public FixMessageAssert doesNotContainFieldWithValue(FixField field, String value) {
        isNotNull();
        if (valuesOf(field.getCode()).stream().anyMatch(value::equals)) {
            failWithMessage("%nExpecting message not to contain field %s with value:%n  <%s>%nbut it does%nmessage was:%n  <%s>",
                    label(field), value, printable(actual));
        }
        return this;
    }

    /**
     * Asserts the MsgType(35) of the message.
     */
    public FixMessageAssert hasMsgType(MessageType messageType) {
        isNotNull();
        if (valuesOf(CoreFields.MESSAGE_TYPE).stream().noneMatch(value -> value.equals(messageType.code()))) {
            failWithMessage("%nExpecting message to have MsgType(35):%n  <%s>%nbut found:%n  <%s>%nmessage was:%n  <%s>",
                    messageType.code(), String.join(", ", valuesOf(CoreFields.MESSAGE_TYPE)), printable(actual));
        }
        return this;
    }

    /**
     * Asserts none of the messages in the asserted text has the given MsgType(35).
     */
    public FixMessageAssert doesNotHaveMsgType(MessageType messageType) {
        isNotNull();
        if (valuesOf(CoreFields.MESSAGE_TYPE).stream().anyMatch(value -> value.equals(messageType.code()))) {
            failWithMessage("%nExpecting no message with MsgType(35):%n  <%s>%nbut there is one%nmessage was:%n  <%s>",
                    messageType.code(), printable(actual));
        }
        return this;
    }

    private List<String> valuesOf(int tag) {
        return FixMessageFields.valuesOf(actual, tag);
    }

    private String describeActualValues(int tag) {
        List<String> values = valuesOf(tag);
        // no %n here: this is an argument of the format, not part of it
        return values.isEmpty() ? "the message does not carry that field at all"
                : "it holds value(s) <" + String.join(", ", values) + ">";
    }

}
