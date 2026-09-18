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
import org.lolaf.staffix.TestingClock;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What an operator may hand to the admin API, and what it is turned into: the body of a message this session's
 * dictionary describes, or an error naming what is wrong with it.
 */
class TestAdminFixMessageTransformer {

    /**
     * An Email(35=C) as it appears in a log: a full frame, a repeating group of two entries, and encoded-text data
     * fields. Its BodyLength(9) and CheckSum(10) are the ones of that message, not of the one being sent.
     */
    private static final String EMAIL_MESSAGE = "8=FIX.4.4|9=192|35=C|34=6|49=SENDER44_TEST|56=TARGET44_TEST|"
            + "52=20241027-21:48:15.516767|94=0|164=test thread id|147=test subject|33=2|58=test text|354=12|"
            + "355=encoded text|58=test text|354=12|355=encoded text|10=020|";

    private FieldsRegistry fieldsRegistry;
    private AdminFixMessageTransformer transformer;

    private static String soh(String pipeSeparated) {
        return pipeSeparated.replace('|', '\001');
    }

    @BeforeEach
    void before() {
        FixDictionaryId fixDictionaryId = FixDictionaryId.of("tests", FixRegularVersion.VERSION_44);
        MessageTypeRegistry messageTypeRegistry = MessageTypeRegistry.Registry.getInstance(fixDictionaryId);
        fieldsRegistry = FieldsRegistry.Registry.getInstance(fixDictionaryId);
        FixSessionId fixSessionId = FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                .id("admin-send-test")
                .senderCompID("SENDER44_TEST")
                .targetCompID("TARGET44_TEST")
                .build());
        transformer = new AdminFixMessageTransformer(fixSessionId, messageTypeRegistry, fieldsRegistry,
                FixSessionSettings.ValidationSettings.builder().build(), TestingClock.get(), TimeUnit.MICROSECONDS);
    }

    @Test
    void aPipeSeparatedMessageKeepsItsBodyAndLosesItsHeaderAndTrailer() {
        DecodedFixMessage body = transformer.transform(EMAIL_MESSAGE, '|', false);

        assertThat(body.getMessageType().code()).isEqualTo("C");
        assertThat(body.containsField(fieldsRegistry.find(164))).as("EmailThreadID(164), a body field").isTrue();
        assertThat(body.containsField(fieldsRegistry.find(147))).as("Subject(147), a body field").isTrue();
        assertThat(body.containsField(fieldsRegistry.find(94))).as("EmailType(94), a body field").isTrue();

        assertThat(body.containsField(fieldsRegistry.find(CoreFields.MESSAGE_TYPE))).as("MsgType(35)").isFalse();
        assertThat(body.containsField(fieldsRegistry.find(CoreFields.MESSAGE_SEQ_NUM))).as("MsgSeqNum(34)").isFalse();
        assertThat(body.containsField(fieldsRegistry.find(CoreFields.SENDING_TIME))).as("SendingTime(52)").isFalse();
        assertThat(body.containsField(fieldsRegistry.find(CoreFields.BEGIN_STRING))).as("BeginString(8)").isFalse();
        assertThat(body.containsField(fieldsRegistry.find(CoreFields.BODY_LENGTH))).as("BodyLength(9)").isFalse();
        assertThat(body.containsField(fieldsRegistry.find(CoreFields.CHECKSUM))).as("CheckSum(10)").isFalse();
        assertThat(body.containsField(fieldsRegistry.find(CoreFields.SENDER_COMP_ID))).as("SenderCompID(49)").isFalse();
        assertThat(body.containsField(fieldsRegistry.find(CoreFields.TARGET_COMP_ID))).as("TargetCompID(56)").isFalse();
    }

    @Test
    void aSohSeparatedMessageIsReadTheSameWay() {
        DecodedFixMessage body = transformer.transform(soh(EMAIL_MESSAGE), CoreFields.FIELD_SEPARATOR, false);

        assertThat(body.getMessageType().code()).isEqualTo("C");
        assertThat(body.containsField(fieldsRegistry.find(164))).isTrue();
    }

    @Test
    void aRepeatingGroupKeepsItsEntries() {
        DecodedFixMessage body = transformer.transform(EMAIL_MESSAGE, '|', false);

        assertThat(body.containsField(fieldsRegistry.find(33))).isTrue();
        assertThat(body.getGroup(fieldsRegistry.find(33)).getEntries())
                .as("NoLinesOfText(33) entries")
                .hasSize(2);
    }

    /**
     * The case a splitter on the separator cannot do: EncodedText(355) is a data field, its length announced by
     * EncodedTextLen(354), and its value is free to contain the separator itself. Read through the length by the
     * parser, as a peer's message would be.
     */
    @Test
    void aDataFieldValueMayContainTheSeparator() {
        String withSeparatorInData = soh("8=FIX.4.4|9=0|35=C|34=6|49=SENDER44_TEST|56=TARGET44_TEST|"
                + "52=20241027-21:48:15.516767|94=0|164=thread|147=subject|33=1|58=text|354=3|355=aXb|10=000|")
                .replace("aXb", "a\001b");

        DecodedFixMessage body = transformer.transform(withSeparatorInData, CoreFields.FIELD_SEPARATOR, false);

        assertThat(body.getGroup(fieldsRegistry.find(33)).getEntries()).hasSize(1);
        assertThat(body.containsField(fieldsRegistry.find(147))).as("the field after the data one was still read").isTrue();
    }

    @Test
    void theBodyLengthAndCheckSumOfThePastedMessageAreIgnored() {
        String wrongFrame = EMAIL_MESSAGE.replace("9=192", "9=1").replace("10=020", "10=999");

        assertThat(transformer.transform(wrongFrame, '|', false).getMessageType().code()).isEqualTo("C");
    }

    @Test
    void aMessageWithoutFrameIsAccepted() {
        String bodyOnly = "35=C|94=0|164=test thread id|147=test subject|33=1|58=test text|";

        assertThat(transformer.transform(bodyOnly, '|', false).containsField(fieldsRegistry.find(164))).isTrue();
    }

    @Test
    void aMessageNotStartingWithItsMessageTypeIsRefused() {
        assertThatThrownBy(() -> transformer.transform("94=0|164=thread|", '|', false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start with MsgType(35)", '|', false);
    }

    /**
     * The parser resolves MsgType(35) and refuses one the dictionary does not define, with the words a peer would be
     * given for the same message, and nothing else: the group the message carries is not followed.
     */
    @Test
    void anUnknownMessageTypeIsRefused() {
        assertThatThrownBy(() -> transformer.transform(EMAIL_MESSAGE.replace("35=C", "35=ZZ"), '|', false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageEndingWith(": tag 35 Invalid MsgType");
    }

    /**
     * A user defined MsgType(35) is a valid one the dictionary merely does not carry, which section 4.5.4 has answered
     * as a business level rejection rather than a session level one - so it says something else.
     */
    @Test
    void aUserDefinedMessageTypeTheDictionaryDoesNotCarryIsRefused() {
        assertThatThrownBy(() -> transformer.transform(EMAIL_MESSAGE.replace("35=C", "35=U1"), '|', false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageEndingWith(": tag 35 Message type not supported");
    }

    @Test
    void aStringHoldingTwoMessagesIsRefused() {
        assertThatThrownBy(() -> transformer.transform(EMAIL_MESSAGE + EMAIL_MESSAGE, '|', false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messages, one is sent at a time", '|', false);
    }

    @Test
    void aTagNotDefinedForThatMessageTypeIsRefused() {
        // Price(44) is a field of the dictionary, but not one an Email(35=C) carries
        assertThatThrownBy(() -> transformer.transform(EMAIL_MESSAGE.replace("94=0|", "94=0|44=1.25|"), '|', false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tag 44", '|', false);
    }

    @Test
    void aTagTheDictionaryDoesNotDefineIsRefused() {
        assertThatThrownBy(() -> transformer.transform(EMAIL_MESSAGE.replace("94=0|", "94=0|9999=x|"), '|', false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("9999", '|', false);
    }

    @Test
    void aMissingRequiredFieldIsRefused() {
        assertThatThrownBy(() -> transformer.transform(EMAIL_MESSAGE.replace("147=test subject|", ""), '|', false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tag 147", '|', false);
    }

    @Test
    void aFieldWithoutValueIsRefused() {
        assertThatThrownBy(() -> transformer.transform(EMAIL_MESSAGE.replace("147=test subject", "147="), '|', false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("147", '|', false);
    }

    @Test
    void aTagAppearingTwiceIsRefused() {
        assertThatThrownBy(() -> transformer.transform(EMAIL_MESSAGE.replace("147=test subject|", "147=test subject|147=again|"), '|', false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("147", '|', false);
    }

    /**
     * The same message going out again: the time it was first sent moves to OrigSendingTime(122) and PossDupFlag(43)=Y
     * says so, which is what a retransmission of a stored message does. The SendingTime(52) of the send itself is the
     * session's own, stamped by the encoder, so it is not among the fields handed over here.
     */
    @Test
    void aPossDupMessageCarriesItsOriginalSendingTimeAndSaysSo() {
        DecodedFixMessage body = transformer.transform(EMAIL_MESSAGE, '|', true);

        assertThat(body.containsField(fieldsRegistry.find(CoreFields.ORIG_SENDING_TIME))).as("OrigSendingTime(122)").isTrue();
        assertThat(body.containsField(fieldsRegistry.find(CoreFields.POSS_DUP_FLAG))).as("PossDupFlag(43)").isTrue();
        assertThat(body.containsField(fieldsRegistry.find(CoreFields.SENDING_TIME))).as("SendingTime(52)").isFalse();
        assertThat(body.getString(fieldsRegistry.find(CoreFields.ORIG_SENDING_TIME), ""))
                .as("the SendingTime the message was written with")
                .startsWith("20241027-21:48:15");
        assertThat(body.getString(fieldsRegistry.find(CoreFields.POSS_DUP_FLAG), "")).isEqualTo("Y");
    }

    @Test
    void aMessageWithoutSendingTimeCannotBeMarkedPossDup() {
        assertThatThrownBy(() -> transformer.transform("35=C|94=0|164=thread|147=subject|33=1|58=text|", '|', true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OrigSendingTime(122)");
    }

    @Test
    void theSeparatorIsTheOneTheCallerNames() {
        String caretSeparated = EMAIL_MESSAGE.replace('|', '^');

        assertThat(transformer.transform(caretSeparated, '^', false).containsField(fieldsRegistry.find(164))).isTrue();
    }

    @Test
    void aMessageReadWithTheWrongSeparatorIsRefused() {
        // pipe separated, read as if it were SOH separated: one enormous field, and no message in it
        assertThatThrownBy(() -> transformer.transform(EMAIL_MESSAGE, CoreFields.FIELD_SEPARATOR, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nothingToSendIsRefused() {
        assertThatThrownBy(() -> transformer.transform("  ", '|', false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No FIX message", '|', false);
    }
}
