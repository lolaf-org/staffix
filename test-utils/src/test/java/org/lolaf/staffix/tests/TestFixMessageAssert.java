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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.msg.MessageType;

import static org.assertj.core.api.Assertions.*;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;

class TestFixMessageAssert {

    private static final MessageType HEARTBEAT = MessageType.of("0", true);
    private static final MessageType REJECT = MessageType.of("3", true);

    /**
     * A Reject(35=3) carrying SessionRejectReason(373)=10, i.e. the case a substring assertion confuses with 373=1.
     */
    private static String reject(String sessionRejectReason) {
        return fix("8=FIX.4.4", "9=90", "35=3", "34=2", "49=SENDER", "56=TARGET", "45=1",
                "373=" + sessionRejectReason, "58=Some diagnostic text, with detail", "10=123");
    }

    private static String fix(String... fields) {
        return String.join(String.valueOf((char) 1), fields) + (char) 1;
    }

    @Test
    void testFieldValueIsMatchedExactlyAndNotAsASubstring() {
        // the hazard this DSL exists for: "373=1" is a substring of "373=10", so assertThat(..).contains("373=1")
        // passes on a message whose SessionRejectReason is actually 10
        String message = reject("10");
        assertThat(message).contains("373=1");

        assertThatCode(() -> assertThatFixMessage(message).containsFieldWithValue(373, "10")).doesNotThrowAnyException();
        assertThatThrownBy(() -> assertThatFixMessage(message).containsFieldWithValue(373, "1"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("tag(373)")
                .hasMessageContaining("it holds value(s)");
    }

    @Test
    void testTagIsMatchedExactlyAndNotAsTheSuffixOfALongerTag() {
        // "35=0" is a substring of "1035=0", so a substring assertion sees a Heartbeat where there is none
        String message = fix("8=FIX.4.4", "9=42", "35=3", "34=2", "1035=0", "10=123");
        assertThat(message).contains("35=0");

        assertThatFixMessage(message).hasMsgType(REJECT).doesNotHaveMsgType(HEARTBEAT);
        assertThatThrownBy(() -> assertThatFixMessage(message).hasMsgType(HEARTBEAT))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("but found");
    }

    @Test
    void testFieldValueIsNotMatchedInsideTheValueOfAnotherField() {
        // the Text(58) of this message quotes "35=0", which a substring assertion cannot tell from a real MsgType
        String message = fix("8=FIX.4.4", "9=60", "35=3", "34=2", "58=expected 35=0 but got something else", "10=123");
        assertThat(message).contains("35=0");

        assertThatFixMessage(message).doesNotHaveMsgType(HEARTBEAT);
    }

    @Test
    void testValuesContainingAnEqualsSignAreParsedOnTheFirstOne() {
        String message = fix("8=FIX.4.4", "9=40", "35=0", "34=2", "112=a=b=c", "10=123");

        assertThatFixMessage(message).containsFieldWithValue(112, "a=b=c");
    }

    @Test
    void testPresenceAndAbsenceOfAField() {
        String message = reject("10");

        assertThatFixMessage(message).containsField(373).doesNotContainField(112);
        assertThatThrownBy(() -> assertThatFixMessage(message).doesNotContainField(373))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("holds value(s)");
        assertThatThrownBy(() -> assertThatFixMessage(message).containsField(112))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("but it does not");
    }

    @Test
    void testValuePrefixMatching() {
        String message = reject("10");

        assertThatFixMessage(message).containsFieldWithValueStartingWith(new Tag(58), "Some diagnostic text");
        assertThatThrownBy(() -> assertThatFixMessage(message).containsFieldWithValueStartingWith(new Tag(58), "Another"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("starting with");
    }

    @Test
    void testFailureMessageRendersTheMessageReadably() {
        assertThatThrownBy(() -> assertThatFixMessage(reject("10")).containsFieldWithValue(112, "absent"))
                // SOH separators are rendered as pipes so the failure stays readable in a terminal
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("8=FIX.4.4|9=90|35=3|");
    }

    @Test
    void testAssertionsApplyToEveryMessageOfATextHoldingSeveral() {
        String twoMessages = fix("8=FIX.4.4", "9=40", "35=3", "34=2", "10=123")
                + fix("8=FIX.4.4", "9=40", "35=5", "34=3", "10=124");

        assertThatFixMessage(twoMessages).hasMsgType(REJECT).doesNotHaveMsgType(HEARTBEAT);
    }

    /**
     * Minimal {@link org.lolaf.staffix.api.fields.FixField} standing in for a generated field constant, this module
     * depending on the api only.
     */
    private static final class Tag implements org.lolaf.staffix.api.fields.FixField {

        private final int code;

        private Tag(int code) {
            this.code = code;
        }

        @Override
        public int getCode() {
            return code;
        }

        @Override
        public byte[] serialized() {
            return (code + "=").getBytes();
        }

        @Override
        public int checksum() {
            return 0;
        }

        @Override
        public org.lolaf.staffix.api.fields.FieldType getType() {
            return org.lolaf.staffix.api.fields.FieldType.STRING;
        }

        @Override
        public org.lolaf.staffix.api.fields.FieldLocation getLocation() {
            return org.lolaf.staffix.api.fields.FieldLocation.BODY;
        }

        @Override
        public int getAsInt() {
            return code;
        }
    }
}
