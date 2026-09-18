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

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lolaf.staffix.TestingClock;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.*;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.msg.MessageFieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.serde.UtcDateTimeSerde;
import org.lolaf.staffix.tests.fix44.encoders.NewOrderSingleEncoder;
import org.lolaf.staffix.tests.fix44.encoders.QuoteCancelEncoder;
import org.lolaf.staffix.tests.fix44.encoders.group.AdvertisementNoUnderlyingsEncoder;
import org.lolaf.staffix.tests.fix44.encoders.group.NoUnderlyingSecurityAltIDEncoder;
import org.lolaf.staffix.tests.fix44.encoders.group.QuoteCancelNoQuoteEntriesEncoder;
import org.lolaf.staffix.tests.fix44.fields.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.*;

class TestFixMessageParser {
    FixDictionaryId fixDictionaryId;
    FixMessageParser fixMessageParser;
    MessageType logon;
    FixSessionId fixSessionId;
    List<String> loggedMessages;
    List<UTCTime> loggedMessagesTimeStamps;
    List<String> loggedEvents;
    FixMessagesLogger fixMessagesLogger;
    List<WrongSeqNumException> wrongSequenceExceptions;
    List<MessageReject> messageRejects;
    FixSession fixSession;
    MessageTypeRegistry messageTypeRegistry;
    FieldsRegistry fieldsRegistry;
    FixMessageParserEventsListener fixMessageParserEventsListener;
    FixSessionSettings.ValidationSettings validationSettings;

    private static Function<MessageType, FixMessageDecoder> getFixMessageDecoderFunction(FixMessageDecoder decoder) {
        return msgType -> {
            when(decoder.getMessageType()).thenReturn(msgType);
            return decoder;
        };
    }

    private static byte[] toBytes(ByteBuffer encoded) {
        encoded.flip();
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private static byte[] moveFieldAfter(byte[] message, int tagToMove, int afterTag) {
        List<String> fields = new ArrayList<>(Arrays.asList(new String(message).split("")));
        String moved = fields.remove(indexOfTag(fields, tagToMove));
        fields.add(indexOfTag(fields, afterTag) + 1, moved);
        return (String.join("", fields) + "").getBytes();
    }

    private static int indexOfTag(List<String> fields, int tag) {
        String prefix = tag + "=";
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).startsWith(prefix)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Tag not found in message: " + tag);
    }

    /**
     * Builds a well framed FIX 4.4 message out of its body fields, computing BodyLength(9) and CheckSum(10), which the
     * parser validates. The neighbouring tests carry both hand computed, which does not scale to the several variants
     * the PossResend tests below need.
     */
    private static String fixMessage(String... bodyFields) {
        StringBuilder body = new StringBuilder(128);
        for (String field : bodyFields) {
            body.append(field).append('');
        }
        String head = "8=FIX.4.49=" + body.length() + '' + body;
        int checksum = 0;
        for (int i = 0; i < head.length(); i++) {
            checksum += head.charAt(i);
        }
        return head + "10=" + String.format("%03d", checksum & 0xFF) + '';
    }

    /**
     * A Logon carrying the third party routing fields of section 6.2 of the FIX Session Layer specification, with the
     * standard header otherwise matching what the session expects.
     */
    private static String thirdPartyRoutedMessage(String onBehalfOfCompId, String deliverToCompId) {
        return fixMessage("35=A", "34=1", "49=SENDER_TEST", "115=" + onBehalfOfCompId, "128=" + deliverToCompId,
                "52=20241013-19:07:17.861", "56=TARGET_TEST", "98=0", "108=30", "141=Y");
    }

    /**
     * The message the out of sequence report at {@code index} held on to, exactly as it arrived on the wire.
     */
    private String rawMessageOf(int index) {
        return new String(wrongSequenceExceptions.get(index).getRawMessage(), StandardCharsets.US_ASCII);
    }

    @BeforeEach
    void before() {
        wrongSequenceExceptions = new ArrayList<>();
        messageRejects = new ArrayList<>();
        fixDictionaryId = FixDictionaryId.of("tests", FixRegularVersion.VERSION_44);
        loggedMessages = new ArrayList<>();
        loggedMessagesTimeStamps = new ArrayList<>();
        loggedEvents = new ArrayList<>();
        fixMessagesLogger = new TestingLogger(loggedMessages, loggedEvents, loggedMessagesTimeStamps);
        fixSessionId = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER_TEST", "TARGET_TEST");
        fixSession = mock(FixSession.class);
        when(fixSession.getFixSessionId()).thenReturn(fixSessionId);
        messageTypeRegistry = MessageTypeRegistry.Registry.getInstance(fixDictionaryId);
        fieldsRegistry = FieldsRegistry.Registry.getInstance(fixDictionaryId);
        fixMessageParserEventsListener = spy(new FixMessageParserEventsListener() {
            @Override
            public void onMessageDecoded(MessageType messageType, FixMessageDecoder fixMessageDecoder, long incomingSeqNum, boolean possibleDuplicate, boolean possResend, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
                fixMessageDecoder.onDecoded(fixSession, possibleDuplicate, possResend);
            }

            @Override
            public void onMessageDecodingFailed(MessageType messageType, FixMessageDecoder fixMessageDecoder, long incomingSeqNum, DecodingException decodingFailureCause, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
                fixMessageDecoder.onDecodingFailed(fixSession, decodingFailureCause);
                if (decodingFailureCause instanceof WrongSeqNumException) {
                    wrongSequenceExceptions.add((WrongSeqNumException) decodingFailureCause);
                }
            }
        });

        doAnswer(invocationOnMock -> {
            List<MessageReject> rejects = invocationOnMock.getArgument(3, List.class);
            messageRejects.addAll(rejects);
            return null;
        }).when(fixMessageParserEventsListener).onMessageRejects(any(), any(), anyLong(), any());

        validationSettings = FixSessionSettings.ValidationSettings.builder()
                .validateChecksum(true)
                .validateFieldsHaveValues(true)
                .validateCompId(false)
                .maxSendingTime(null)
                .build();
        fixMessageParser = new FixMessageParser(fixSessionId, messageTypeRegistry, fieldsRegistry,
                fixMessagesLogger.getLogger("test", fixSessionId, messageTypeRegistry), validationSettings, TestingClock.get(), fixMessageParserEventsListener);
        logon = MessageTypeRegistry.Registry.getInstance(fixDictionaryId).find("A");
    }

    @Test
    void testMessageWithNonFixIsQuicklyTerminated() {
        String message = "Hello i'm not talking FIX protocol how are you doing ?";
        try {
            fixMessageParser.parseMessages((ByteBuffer.wrap((message).getBytes())), getFixMessageDecoderFunction(mock(FixMessageDecoder.class)));
            fail("Should have failed");
        } catch (DecodingException ex) {
            assertThat(ex.getMessage()).isEqualTo("Unable to find FIX BeginString field in network payload: Hello i'm not talking FIX protocol how are you doing ?");
        }
    }

    @Test
    void testMessageWithHackedDataIsQuicklyTerminated() {
        String message = "Hello=i'm hacking youwith some=fake stuff?";
        try {
            fixMessageParser.parseMessages((ByteBuffer.wrap((message).getBytes())), getFixMessageDecoderFunction(mock(FixMessageDecoder.class)));
            fail("Should have failed");
        } catch (DecodingException ex) {
            assertThat(ex.getMessage()).isEqualTo("Decoding exception on message: Hello=i'm hacking you\u0001with some=fake stuff\u0001?");
            assertThat(ex.getCause().getMessage()).isEqualTo("H is not a number");
        }
    }

    @Test
    void testBufferOverflowAttackIsDetected() {
        StringBuilder message = new StringBuilder("8=FIX.4.49=8335=A34=149=");
        message.append("a".repeat(FixMessageParser.MAX_FIELD_SIZE));
        try {
            fixMessageParser.parseMessages((ByteBuffer.wrap(message.toString().getBytes())), getFixMessageDecoderFunction(mock(FixMessageDecoder.class)));
            fail("Should have failed");
        } catch (DecodingException ex) {
            assertThat(ex.getMessage()).isEqualTo("Unable to find field value delimiter within "
                    + FixMessageParser.MAX_FIELD_SIZE + " bytes: 49=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        }
    }

    @Test
    void testTooBigMessageIsRejected() {
        validationSettings = validationSettings.toBuilder()
                .maxMessageSize(32).build();

        fixMessageParser = new FixMessageParser(fixSessionId, messageTypeRegistry, fieldsRegistry,
                fixMessagesLogger.getLogger("test", fixSessionId, messageTypeRegistry), validationSettings,
                TestingClock.get(), fixMessageParserEventsListener);

        StringBuilder message = new StringBuilder("8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249");
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        try {
            fixMessageParser.parseMessages(ByteBuffer.wrap(message.toString().getBytes()), getFixMessageDecoderFunction(decoder));
        } catch (DecodingException ex) {
            fail("Should not have thrown", ex);
        }

        verify(decoder).onDecodingFailed(any(FixSession.class), any(RejectedMessageException.class));

        assertThat(messageRejects).hasSize(1);
        MessageReject reject = messageRejects.get(0);
        assertThat(reject.getMessage()).isEqualTo("max message size reached");
        assertThat(reject.getRefTagId()).isEqualTo(52);
        assertThat(reject.getSessionRejectReasonCode()).isEqualTo(SessionRejectReasonCodes.OTHER);
        assertThat(reject.getBusinessRejectReasonCode()).isNull();
    }

    @Test
    void testMessageWithBogusFieldTagIsGarbled() throws DecodingException {
        // A tag that is not a number means the field framing cannot be trusted from there on, which is what receiving
        // a non "data" field carrying an embedded SOH looks like to the parser. FIX Session Layer 4.5.2 allows either
        // rejecting or disregarding such a message; it is disregarded as garbled for the same reason a wrong
        // BodyLength(9) is, so the session is kept and NextNumIn is left untouched rather than the connection dropped.
        String message = "8=FIX.4.49=8335=A34=14a=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249";
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages((ByteBuffer.wrap((message).getBytes())), getFixMessageDecoderFunction(decoder));

        verify(decoder, never()).onDecoded(any(FixSession.class), anyBoolean(), anyBoolean());
        verify(decoder).onDecodingFailed(any(FixSession.class), assertArg(ex -> assertThat(ex)
                .isInstanceOf(GarbledMessageException.class)
                .hasMessage("Garbled message received, ignoring it: tag is not a number: a is not a number")));
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testBogusFieldTagBeforeBodyLengthIsStillTerminal() {
        // Recovering from a bogus tag needs a BodyLength(9) to say where the message ends. One breaking the standard
        // header before that point leaves nothing to resynchronize on, so it stays a terminal parsing exception.
        String message = "8=FIX.4.49a=8335=A34=149=TARGET_TEST10=249";
        try {
            fixMessageParser.parseMessages((ByteBuffer.wrap((message).getBytes())), getFixMessageDecoderFunction(mock(FixMessageDecoder.class)));
            fail("Should have failed");
        } catch (DecodingException ex) {
            assertThat(ex.getCause().getMessage()).isEqualTo("a is not a number");
        }
    }

    @Test
    void testMessageWithProcessingFailureIsSkippedButStillLogged() throws DecodingException {
        String message1 = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249";
        String message2 = "8=FIX.4.49=8235=A34=249=TARGET_TEST52=failure-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=084";
        String message3 = "8=FIX.4.49=8335=A34=349=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=251";

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        doThrow(new IllegalStateException("test failure")).when(decoder).onField(argThat(fixField -> fixField.getCode() == 52),
                argThat(c -> c.contentToString().contains("failure")));

        fixMessageParser.parseMessages((ByteBuffer.wrap((message1 + message2 + message3).getBytes())), getFixMessageDecoderFunction(decoder));

        verify(decoder, times(3)).onBegin(anyLong(), any());
        verify(decoder, times(1)).onDecodingFailed(any(FixSession.class),
                assertArg(e -> assertThat(e.getMessage()).isEqualTo("Application level decoding exception")));
        verify(decoder, times(1)).onDecodingFailed(any(FixSession.class),
                assertArg(e -> assertThat(e.getCause().getMessage()).isEqualTo("test failure")));
        verify(decoder, times(2)).onDecoded(any(FixSession.class), eq(false), eq(false));

        verify(fixMessageParserEventsListener).onMessageDecodingFailed(any(), eq(decoder), eq(2L), any(DecodingException.class), anyLong(), any(UTCTime.class));

        assertThat(messageRejects).hasSize(1);
        assertThat(loggedMessages.get(0)).isEqualTo(message1);
        assertThat(loggedMessages.get(1)).isEqualTo(message2);
        assertThat(loggedMessages.get(2)).isEqualTo(message3);

    }

    @Test
    void testHalfMessageInBufferWithLastFieldBeingADataField() throws DecodingException {
        String messagePart1 = "8=FIX.4.4\u00019=161\u000135=C\u000134=1\u000149=TARGET44_TEST\u000152=20241113-20:52:16.571351" +
                "\u000156=SENDER44_TEST\u000194=0\u0001147=test subject 13\u0001164=test thread id 13\u000133=1\u000158=test text\u0001354=12\u0001355=encoded text";
        String messagePart2 = "355=encoded text\u000110=224";

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        ByteBuffer bb = ByteBuffer.wrap(messagePart1.getBytes());

        fixMessageParser.parseMessages(bb, getFixMessageDecoderFunction(decoder));

        assertThat(bb.position()).isEqualTo(160);
        assertThat(bb.limit()).isEqualTo(176);
        assertThat(loggedMessages).isEmpty();

        fixMessageParser.parseMessages(ByteBuffer.wrap(messagePart2.getBytes()), msgType -> decoder);

        verify(decoder).onBegin(anyLong(), any());
        verify(decoder).onDecoded(any(FixSession.class), eq(false), eq(false));
        verify(fixMessageParserEventsListener).onMessageDecoded(any(), eq(decoder), eq(1L), eq(false), eq(false), anyLong(), any(UTCTime.class));

        assertThat(messageRejects).isEmpty();
        assertThat(loggedMessages).hasSize(1)
                .contains("8=FIX.4.4\u00019=161\u000135=C\u000134=1\u000149=TARGET44_TEST\u000152=20241113-20:52:16.571351" +
                        "\u000156=SENDER44_TEST\u000194=0\u0001147=test subject 13\u0001164=test thread id 13" +
                        "\u000133=1\u000158=test text\u0001354=12\u0001355=encoded text\u000110=224\u0001");
    }

    @Test
    void test2MessagesInBufferWithDelimiterSplitted() throws DecodingException {
        String messagePart1 = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y";
        String messagePart2 = "10=2498=FIX.4.49=8335=A34=249=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249";

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        ByteBuffer bb = ByteBuffer.allocate(1024);

        fixMessageParser.parseMessages(bb.put(messagePart1.getBytes()).flip(), getFixMessageDecoderFunction(decoder));
        fixMessageParser.parseMessages(bb.compact().put(messagePart2.getBytes()).flip(), getFixMessageDecoderFunction(decoder));

        verify(decoder, times(2)).onBegin(anyLong(), any());

        assertThat(loggedMessages).hasSize(2);
        assertThat(loggedMessages.get(0)).isEqualTo("8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249");
        assertThat(loggedMessages.get(1)).isEqualTo("8=FIX.4.49=8335=A34=249=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249");
    }

    @Test
    void test2MessagesInBufferWithBeginStringSplitted() throws DecodingException {
        String messagePart1 = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=2498=FI";
        String messagePart2 = "X.4.49=8335=A34=249=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249";

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        ByteBuffer bb = ByteBuffer.allocate(1024);

        fixMessageParser.parseMessages(bb.put(messagePart1.getBytes()).flip(), getFixMessageDecoderFunction(decoder));
        fixMessageParser.parseMessages(bb.compact().put(messagePart2.getBytes()).flip(), getFixMessageDecoderFunction(decoder));

        assertThat(loggedMessages).hasSize(2);
        assertThat(loggedMessages.get(0)).isEqualTo("8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249");
        assertThat(loggedMessages.get(1)).isEqualTo("8=FIX.4.49=8335=A34=249=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249");
    }

    @Test
    void testHalfMessageInBuffer() throws DecodingException {
        String messagePart1 = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=";
        String messagePart2 = "20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249";

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        ByteBuffer bb = ByteBuffer.allocate(1024);

        fixMessageParser.parseMessages(bb.put(messagePart1.getBytes()).flip(), getFixMessageDecoderFunction(decoder), () -> 1L, 0);

        assertThat(loggedMessages).isEmpty();
        assertThat(bb.position()).isEqualTo(40);
        assertThat(bb.limit()).isEqualTo(43);
        bb.compact().put(messagePart2.getBytes()).flip();

        fixMessageParser.parseMessages(bb, msgType -> decoder, () -> 1L, 0);

        verify(decoder).onBegin(anyLong(), any());
        verify(decoder).onDecoded(any(FixSession.class), eq(false), eq(false));
        verify(fixMessageParserEventsListener).onMessageDecoded(any(), eq(decoder), eq(1L), eq(false), eq(false), anyLong(), any(UTCTime.class));

        assertThat(messageRejects).isEmpty();
        assertThat(loggedMessages).hasSize(1);
        assertThat(loggedMessages.get(0)).isEqualTo(messagePart1 + messagePart2);
    }

    @Test
    void testParseEmptyRepeatingGroup() throws DecodingException {
        QuoteCancelEncoder encoder = getQuoteCancelEncoder();
        encoder.addNoQuoteEntries(0);

        ByteBuffer encoded = encoder.encode(ByteBuffer::allocate, 1, fixSessionId, mock(FixApplication.class), TimeUnit.MICROSECONDS, TestingClock.get().now(), null);

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        fixMessageParser.parseMessages(encoded.flip(), getFixMessageDecoderFunction(decoder));

        verify(decoder, never()).onGroupStart(any(FixField.class), any(FixField.class), anyInt());
        verify(decoder, never()).onGroupEnd(any(FixField.class), any(FixField.class));

        verify(decoder).onDecodingFailed(any(FixSession.class), any(RejectedMessageException.class));
        assertThat(messageRejects).hasSize(1);
        assertThat(messageRejects.get(0).getMessage()).isEqualTo("NumInGroup value must be greater than zero");
        assertThat(messageRejects.get(0).getSessionRejectReasonCode()).isEqualTo(SessionRejectReasonCodes.INCORRECT_NUM_IN_GROUP_COUNT_FOR_REPEATING_GROUP);
    }

    /**
     * A MsgType(35) the dictionary does not define has no field layout, so its groups are not followed: it used to
     * fail the parser with a NullPointerException, adding a second reject.
     */
    @ParameterizedTest
    @CsvSource({"ZZ, Invalid MsgType", "U1, Message type not supported"})
    void testGroupInAMessageTypeTheDictionaryDoesNotDefineIsNotFollowed(String msgType, String expectedReject) throws DecodingException {
        String message = fixMessage("35=" + msgType, "34=1", "49=TARGET_TEST", "52=20241013-19:07:17.861",
                "56=SENDER_TEST", "33=2", "58=first line", "58=second line");

        fixMessageParser.parseMessages(ByteBuffer.wrap(message.getBytes()), getFixMessageDecoderFunction(mock(FixMessageDecoder.class)));

        assertThat(messageRejects).singleElement().satisfies(reject -> {
            assertThat(reject.getMessage()).isEqualTo(expectedReject);
            assertThat(reject.getRefTagId()).isEqualTo(CoreFields.MESSAGE_TYPE);
            assertThat(reject.getSessionRejectReasonCode()).isEqualTo(SessionRejectReasonCodes.INVALID_MSGTYPE);
        });
    }

    @Test
    void testParseRepeatingGroupWithNestedGroupAsNonLastTagInGroup() throws DecodingException {
        QuoteCancelEncoder encoder = getQuoteCancelEncoder();
        QuoteCancelNoQuoteEntriesEncoder noQuoteEntriesEncoder = encoder.addNoQuoteEntries(2);
        for (int i = 0; i < 2; i++) {
            noQuoteEntriesEncoder.setSymbol("test symbol " + i);
            noQuoteEntriesEncoder.setAgreementCurrency("test currency " + i);
            AdvertisementNoUnderlyingsEncoder noUnderLying = encoder.addNoUnderlyings(2);
            for (int j = 0; j < 2; j++) {
                noUnderLying.setUnderlyingSymbol("test symbol " + j);
                noUnderLying.setEncodedUnderlyingIssuerLen(("test issuer " + j).length());
                noUnderLying.setEncodedUnderlyingIssuer("test issuer " + j);
                NoUnderlyingSecurityAltIDEncoder altIdEncoder = encoder.addNoUnderlyingSecurityAltID(2);
                for (int k = 0; k < 2; k++) {
                    altIdEncoder.setUnderlyingSecurityAltID("test AltId " + k);
                    altIdEncoder.setUnderlyingSecurityAltIDSource("test AltId Source" + k);
                }
                noUnderLying.setUnderlyingCFICode("test cfi");
            }
            noQuoteEntriesEncoder.setCFICode("test cfi");
        }

        ByteBuffer encoded = encoder.encode(ByteBuffer::allocate, 1, fixSessionId, mock(FixApplication.class), TimeUnit.MICROSECONDS, TestingClock.get().now(), null);

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        fixMessageParser.parseMessages(encoded.flip(), getFixMessageDecoderFunction(decoder));

        verify(decoder).onGroupStart(null, NoQuoteEntries.get(), 2);
        verify(decoder).onGroupEnd(null, NoQuoteEntries.get());
        verify(decoder, times(2)).onGroupEntryStart(null, NoQuoteEntries.get(), Symbol.get());
        verify(decoder, times(2)).onGroupEntryEnd(null, NoQuoteEntries.get(), CFICode.get());

        verify(decoder, times(2)).onGroupStart(NoQuoteEntries.get(), NoUnderlyings.get(), 2);
        verify(decoder, times(2)).onGroupEnd(NoQuoteEntries.get(), NoUnderlyings.get());
        verify(decoder, times(4)).onGroupEntryStart(NoQuoteEntries.get(), NoUnderlyings.get(), UnderlyingSymbol.get());
        verify(decoder, times(4)).onGroupEntryEnd(NoQuoteEntries.get(), NoUnderlyings.get(), UnderlyingCFICode.get());

        verify(decoder, times(4)).onGroupStart(NoUnderlyings.get(), NoUnderlyingSecurityAltID.get(), 2);
        verify(decoder, times(4)).onGroupEnd(NoUnderlyings.get(), NoUnderlyingSecurityAltID.get());
        verify(decoder, times(8)).onGroupEntryStart(NoUnderlyings.get(), NoUnderlyingSecurityAltID.get(), UnderlyingSecurityAltID.get());
        verify(decoder, times(8)).onGroupEntryEnd(NoUnderlyings.get(), NoUnderlyingSecurityAltID.get(), UnderlyingSecurityAltIDSource.get());

        verify(decoder, never()).onDecodingFailed(any(FixSession.class), any(DecodingException.class));

        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testParseRepeatingGroupWithNestedGroupAsLastTagInGroup() throws DecodingException {
        QuoteCancelEncoder encoder = getQuoteCancelEncoder();
        QuoteCancelNoQuoteEntriesEncoder noQuoteEntriesEncoder = encoder.addNoQuoteEntries(2);
        for (int i = 0; i < 2; i++) {
            noQuoteEntriesEncoder.setSymbol("test symbol " + i);
            noQuoteEntriesEncoder.setAgreementCurrency("test currency " + i);
            AdvertisementNoUnderlyingsEncoder noUnderLying = encoder.addNoUnderlyings(2);
            for (int j = 0; j < 2; j++) {
                noUnderLying.setUnderlyingSymbol("test symbol " + j);
                noUnderLying.setEncodedUnderlyingIssuerLen(("test issuer " + j).length());
                noUnderLying.setEncodedUnderlyingIssuer("test issuer " + j);
                NoUnderlyingSecurityAltIDEncoder altIdEncoder = encoder.addNoUnderlyingSecurityAltID(2);
                for (int k = 0; k < 2; k++) {
                    altIdEncoder.setUnderlyingSecurityAltID("test AltId " + k);
                    altIdEncoder.setUnderlyingSecurityAltIDSource("test AltId Source" + k);
                }
            }
        }

        ByteBuffer encoded = encoder.encode(ByteBuffer::allocate, 1, fixSessionId, mock(FixApplication.class), TimeUnit.MICROSECONDS, TestingClock.get().now(), null);

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        fixMessageParser.parseMessages(encoded.flip(), getFixMessageDecoderFunction(decoder));

        verify(decoder).onGroupStart(null, NoQuoteEntries.get(), 2);
        verify(decoder).onGroupEnd(null, NoQuoteEntries.get());
        verify(decoder, times(2)).onGroupEntryStart(null, NoQuoteEntries.get(), Symbol.get());
        verify(decoder, times(2)).onGroupEntryEnd(null, NoQuoteEntries.get(), NoUnderlyings.get());

        verify(decoder, times(2)).onGroupStart(NoQuoteEntries.get(), NoUnderlyings.get(), 2);
        verify(decoder, times(2)).onGroupEnd(NoQuoteEntries.get(), NoUnderlyings.get());
        verify(decoder, times(4)).onGroupEntryStart(NoQuoteEntries.get(), NoUnderlyings.get(), UnderlyingSymbol.get());
        verify(decoder, times(4)).onGroupEntryEnd(NoQuoteEntries.get(), NoUnderlyings.get(), NoUnderlyingSecurityAltID.get());

        verify(decoder, times(4)).onGroupStart(NoUnderlyings.get(), NoUnderlyingSecurityAltID.get(), 2);
        verify(decoder, times(4)).onGroupEnd(NoUnderlyings.get(), NoUnderlyingSecurityAltID.get());
        verify(decoder, times(8)).onGroupEntryStart(NoUnderlyings.get(), NoUnderlyingSecurityAltID.get(), UnderlyingSecurityAltID.get());
        verify(decoder, times(8)).onGroupEntryEnd(NoUnderlyings.get(), NoUnderlyingSecurityAltID.get(), UnderlyingSecurityAltIDSource.get());
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testParseRepeatingGroupWithOneNestedGroup() throws DecodingException {
        QuoteCancelEncoder encoder = getQuoteCancelEncoder();
        QuoteCancelNoQuoteEntriesEncoder noQuoteEntriesEncoder = encoder.addNoQuoteEntries(2);
        for (int i = 0; i < 2; i++) {
            noQuoteEntriesEncoder.setSymbol("test symbol " + i);
            noQuoteEntriesEncoder.setAgreementCurrency("test currency " + i);
            AdvertisementNoUnderlyingsEncoder noUnderLying = encoder.addNoUnderlyings(2);
            for (int j = 0; j < 2; j++) {
                noUnderLying.setUnderlyingSymbol("test symbol " + j);
                noUnderLying.setEncodedUnderlyingIssuerLen(("test issuer " + j).length());
                noUnderLying.setEncodedUnderlyingIssuer("test issuer " + j);
            }
        }

        ByteBuffer encoded = encoder.encode(ByteBuffer::allocate, 1, fixSessionId, mock(FixApplication.class), TimeUnit.MICROSECONDS, TestingClock.get().now(), null);

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        fixMessageParser.parseMessages(encoded.flip(), getFixMessageDecoderFunction(decoder));

        verify(decoder).onGroupStart(null, NoQuoteEntries.get(), 2);
        verify(decoder).onGroupEnd(null, NoQuoteEntries.get());
        verify(decoder, times(2)).onGroupEntryStart(null, NoQuoteEntries.get(), Symbol.get());
        verify(decoder, times(2)).onGroupEntryEnd(null, NoQuoteEntries.get(), NoUnderlyings.get());

        verify(decoder, times(2)).onGroupStart(NoQuoteEntries.get(), NoUnderlyings.get(), 2);
        verify(decoder, times(2)).onGroupEnd(NoQuoteEntries.get(), NoUnderlyings.get());
        verify(decoder, times(4)).onGroupEntryStart(NoQuoteEntries.get(), NoUnderlyings.get(), UnderlyingSymbol.get());
        verify(decoder, times(4)).onGroupEntryEnd(NoQuoteEntries.get(), NoUnderlyings.get(), EncodedUnderlyingIssuer.get());

        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testParseRepeatingGroupWithOneNestedGroupAndDifferentEndTags() throws DecodingException {
        QuoteCancelEncoder encoder = getQuoteCancelEncoder();
        QuoteCancelNoQuoteEntriesEncoder noQuoteEntriesEncoder = encoder.addNoQuoteEntries(2);
        for (int i = 0; i < 2; i++) {
            noQuoteEntriesEncoder.setSymbol("test symbol " + i);
            noQuoteEntriesEncoder.setAgreementCurrency("test currency " + i);
            AdvertisementNoUnderlyingsEncoder noUnderLying = encoder.addNoUnderlyings(2);
            for (int j = 0; j < 2; j++) {
                noUnderLying.setUnderlyingSymbol("test symbol " + j);
                noUnderLying.setEncodedUnderlyingIssuerLen(("test issuer " + j).length());
                noUnderLying.setEncodedUnderlyingIssuer("test issuer " + j);
                if (i % 2 == 0) {
                    noUnderLying.setEncodedUnderlyingSecurityDesc("test securityDes");
                }
            }
            if (i % 2 == 0) {
                noQuoteEntriesEncoder.setCFICode("test cfi");
            }
        }

        ByteBuffer encoded = encoder.encode(ByteBuffer::allocate, 1, fixSessionId, mock(FixApplication.class), TimeUnit.MICROSECONDS, TestingClock.get().now(), null);

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        fixMessageParser.parseMessages(encoded.flip(), getFixMessageDecoderFunction(decoder));

        verify(decoder).onGroupStart(null, NoQuoteEntries.get(), 2);
        verify(decoder).onGroupEnd(null, NoQuoteEntries.get());
        verify(decoder, times(2)).onGroupEntryStart(null, NoQuoteEntries.get(), Symbol.get());
        verify(decoder, times(1)).onGroupEntryEnd(null, NoQuoteEntries.get(), NoUnderlyings.get());
        verify(decoder, times(1)).onGroupEntryEnd(null, NoQuoteEntries.get(), CFICode.get());

        verify(decoder, times(2)).onGroupStart(NoQuoteEntries.get(), NoUnderlyings.get(), 2);
        verify(decoder, times(2)).onGroupEnd(NoQuoteEntries.get(), NoUnderlyings.get());
        verify(decoder, times(4)).onGroupEntryStart(NoQuoteEntries.get(), NoUnderlyings.get(), UnderlyingSymbol.get());
        verify(decoder, times(2)).onGroupEntryEnd(NoQuoteEntries.get(), NoUnderlyings.get(), EncodedUnderlyingIssuer.get());
        verify(decoder, times(2)).onGroupEntryEnd(NoQuoteEntries.get(), NoUnderlyings.get(), EncodedUnderlyingSecurityDesc.get());

        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testParseSimpleRepeatingGroupWithNonMatchingEntriesCount() throws DecodingException {
        QuoteCancelEncoder encoder = getQuoteCancelEncoder();
        QuoteCancelNoQuoteEntriesEncoder noQuoteEntriesEncoder = encoder.addNoQuoteEntries(2);
        for (int i = 0; i < 1; i++) {
            noQuoteEntriesEncoder.setSymbol("test symbol " + i);
            noQuoteEntriesEncoder.setAgreementCurrency("test currency " + i);
        }

        ByteBuffer encoded = encoder.encode(ByteBuffer::allocate, 1, fixSessionId, mock(FixApplication.class), TimeUnit.MICROSECONDS, TestingClock.get().now(), null);

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        fixMessageParser.parseMessages(encoded.flip(), getFixMessageDecoderFunction(decoder));

        // declared count (2) is larger than the single entry actually present; the message is rejected and
        // rolled back, so the trailing onGroupEnd is emitted on the void decoder rather than the mock
        verify(decoder).onGroupStart(null, NoQuoteEntries.get(), 2);

        assertThat(messageRejects).isNotEmpty();
        assertThat(messageRejects.get(0).getSessionRejectReasonCode())
                .isEqualTo(SessionRejectReasonCodes.INCORRECT_NUM_IN_GROUP_COUNT_FOR_REPEATING_GROUP);
        assertThat(messageRejects.get(0).getRefTagId()).isEqualTo(NoQuoteEntries.get().getCode());
    }

    @Test
    void testParseSimpleRepeatingGroupWithProvidedCountSmallerThanActualEntries() throws DecodingException {
        QuoteCancelEncoder encoder = getQuoteCancelEncoder();
        QuoteCancelNoQuoteEntriesEncoder noQuoteEntriesEncoder = encoder.addNoQuoteEntries(1);
        for (int i = 0; i < 2; i++) {
            noQuoteEntriesEncoder.setSymbol("test symbol " + i);
            noQuoteEntriesEncoder.setAgreementCurrency("test currency " + i);
        }

        ByteBuffer encoded = encoder.encode(ByteBuffer::allocate, 1, fixSessionId, mock(FixApplication.class), TimeUnit.MICROSECONDS, TestingClock.get().now(), null);

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        fixMessageParser.parseMessages(encoded.flip(), getFixMessageDecoderFunction(decoder));

        // declared count (1) is smaller than the two entries actually present; the second entry start triggers
        // the reject and the message is rolled back, so onGroupEnd is emitted on the void decoder
        verify(decoder).onGroupStart(null, NoQuoteEntries.get(), 1);

        assertThat(messageRejects).isNotEmpty();
        assertThat(messageRejects.get(0).getSessionRejectReasonCode())
                .isEqualTo(SessionRejectReasonCodes.INCORRECT_NUM_IN_GROUP_COUNT_FOR_REPEATING_GROUP);
        assertThat(messageRejects.get(0).getRefTagId()).isEqualTo(NoQuoteEntries.get().getCode());
    }

    @Test
    void testParseSimpleRepeatingGroupWithTwoEntries() throws DecodingException {
        QuoteCancelEncoder encoder = getQuoteCancelEncoder();
        QuoteCancelNoQuoteEntriesEncoder noQuoteEntriesEncoder = encoder.addNoQuoteEntries(2);
        for (int i = 0; i < 2; i++) {
            noQuoteEntriesEncoder.setSymbol("test symbol " + i);
            noQuoteEntriesEncoder.setAgreementCurrency("test currency " + i);
        }

        ByteBuffer encoded = encoder.encode(ByteBuffer::allocate, 1, fixSessionId, mock(FixApplication.class), TimeUnit.MICROSECONDS, TestingClock.get().now(), null);

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        fixMessageParser.parseMessages(encoded.flip(), getFixMessageDecoderFunction(decoder));

        verify(decoder).onGroupStart(null, NoQuoteEntries.get(), 2);
        verify(decoder).onGroupEnd(null, NoQuoteEntries.get());
        verify(decoder, times(2)).onGroupEntryStart(null, NoQuoteEntries.get(), Symbol.get());
        verify(decoder, times(2)).onGroupEntryEnd(null, NoQuoteEntries.get(), AgreementCurrency.get());
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testParseSimpleRepeatingGroupWithTwoEntriesOutputCorrectDecodingOrder() throws DecodingException {
        QuoteCancelEncoder encoder = getQuoteCancelEncoder();
        QuoteCancelNoQuoteEntriesEncoder noQuoteEntriesEncoder = encoder.addNoQuoteEntries(2);
        for (int i = 0; i < 2; i++) {
            noQuoteEntriesEncoder.setSymbol("test symbol " + i);
            noQuoteEntriesEncoder.setAgreementCurrency("test currency " + i);
        }

        ByteBuffer encoded = encoder.encode(ByteBuffer::allocate, 1, fixSessionId, mock(FixApplication.class), TimeUnit.MICROSECONDS,
                TestingClock.get().now(), null);

        DecodedFixMessageDecoder decoder = new DecodedFixMessageDecoder(encoder.getMessageType());

        fixMessageParser.parseMessages(encoded.flip(), msg -> decoder);

        assertThat(decoder.getDecodedFixMessage()).hasToString("35=Z\u000134=1\u000149=SENDER_TEST\u000156=TARGET_TEST" +
                "\u000152=19700101-00:00:00.000000\u0001117=testQuoteId\u0001298=4\u0001295=2\u000155=test symbol 0\u0001918=test currency 0\u000155=test symbol 1\u0001918=test currency 1\u0001");
    }

    @Test
    void testParseSimpleRepeatingGroupWithOneEntry() throws DecodingException {
        QuoteCancelEncoder encoder = getQuoteCancelEncoder();
        QuoteCancelNoQuoteEntriesEncoder noQuoteEntriesEncoder = encoder.addNoQuoteEntries(1);
        for (int i = 0; i < 1; i++) {
            noQuoteEntriesEncoder.setSymbol("test symbol " + i);
            noQuoteEntriesEncoder.setAgreementCurrency("test currency " + i);
        }

        ByteBuffer encoded = encoder.encode(ByteBuffer::allocate, 1, fixSessionId, mock(FixApplication.class), TimeUnit.MICROSECONDS, TestingClock.get().now(), null);

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        fixMessageParser.parseMessages(encoded.flip(), getFixMessageDecoderFunction(decoder));

        verify(decoder).onGroupStart(null, NoQuoteEntries.get(), 1);
        verify(decoder).onGroupEnd(null, NoQuoteEntries.get());
        verify(decoder, times(1)).onGroupEntryStart(null, NoQuoteEntries.get(), Symbol.get());
        verify(decoder, times(1)).onGroupEntryEnd(null, NoQuoteEntries.get(), AgreementCurrency.get());
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testParseSimpleRepeatingGroupWithDifferentEndTagFinishesParsing() throws DecodingException {
        QuoteCancelEncoder encoder = getQuoteCancelEncoder();
        QuoteCancelNoQuoteEntriesEncoder noQuoteEntriesEncoder = encoder.addNoQuoteEntries(2);
        for (int i = 0; i < 2; i++) {
            noQuoteEntriesEncoder.setSymbol("test symbol " + i);
            noQuoteEntriesEncoder.setAgreementCurrency("test currency " + i);
            if (i % 2 == 0) {
                noQuoteEntriesEncoder.setCFICode("test cfi");
            }
        }

        ByteBuffer encoded = encoder.encode(ByteBuffer::allocate, 1, fixSessionId, mock(FixApplication.class), TimeUnit.MICROSECONDS, TestingClock.get().now(), null);

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        fixMessageParser.parseMessages(encoded.flip(), getFixMessageDecoderFunction(decoder));

        verify(decoder).onGroupStart(null, NoQuoteEntries.get(), 2);
        verify(decoder).onGroupEnd(null, NoQuoteEntries.get());
        verify(decoder, times(2)).onGroupEntryStart(null, NoQuoteEntries.get(), Symbol.get());
        verify(decoder, times(1)).onGroupEntryEnd(null, NoQuoteEntries.get(), CFICode.get());
        verify(decoder, times(1)).onGroupEntryEnd(null, NoQuoteEntries.get(), AgreementCurrency.get());
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testOutOfOrderHeaderFieldRejectedWhenValidationEnabled() throws DecodingException {
        // relocate MsgSeqNum (34, header) after QuoteID (117, body); reordering preserves the byte multiset so
        // the checksum and body length stay valid and only the section ordering is breached
        byte[] message = moveFieldAfter(toBytes(encodeQuoteCancel(getQuoteCancelEncoder())), 34, 117);

        fixMessageParser = outOfOrderValidatingParser();
        fixMessageParser.parseMessages(ByteBuffer.wrap(message), getFixMessageDecoderFunction(mock(FixMessageDecoder.class)));

        assertThat(messageRejects).hasSize(1);
        assertThat(messageRejects.get(0).getSessionRejectReasonCode()).isEqualTo(SessionRejectReasonCodes.TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER);
        assertThat(messageRejects.get(0).getRefTagId()).isEqualTo(34);
    }

    @Test
    void testOutOfOrderHeaderFieldToleratedWhenValidationDisabled() throws DecodingException {
        byte[] message = moveFieldAfter(toBytes(encodeQuoteCancel(getQuoteCancelEncoder())), 34, 117);

        fixMessageParser.parseMessages(ByteBuffer.wrap(message), getFixMessageDecoderFunction(mock(FixMessageDecoder.class)));

        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testOutOfOrderRepeatingGroupFieldRejectedWhenValidationEnabled() throws DecodingException {
        QuoteCancelEncoder encoder = getQuoteCancelEncoder();
        QuoteCancelNoQuoteEntriesEncoder entry = encoder.addNoQuoteEntries(1);
        entry.setSymbol("test symbol");
        entry.setAgreementCurrency("test currency");
        entry.setCFICode("test cfi");
        byte[] inOrder = toBytes(encodeQuoteCancel(encoder));

        // the encoder always emits members in declared order, so swap the two members on the wire to put the
        // lower-order one after the higher-order one within the entry, whatever their declared positions are
        MessageFieldsRegistry.FixMessageFields quoteCancelFields = MessageFieldsRegistry.Registry.getInstance(fixDictionaryId)
                .getFixMessageFields(encoder.getMessageType());
        boolean currencyBeforeCfi = quoteCancelFields.getGroupFieldOrder(NoQuoteEntries.get(), AgreementCurrency.get())
                < quoteCancelFields.getGroupFieldOrder(NoQuoteEntries.get(), CFICode.get());
        int lowerOrderTag = (currencyBeforeCfi ? AgreementCurrency.get() : CFICode.get()).getCode();
        int higherOrderTag = (currencyBeforeCfi ? CFICode.get() : AgreementCurrency.get()).getCode();
        byte[] message = moveFieldAfter(inOrder, lowerOrderTag, higherOrderTag);

        fixMessageParser = outOfOrderValidatingParser();
        fixMessageParser.parseMessages(ByteBuffer.wrap(message), getFixMessageDecoderFunction(mock(FixMessageDecoder.class)));

        assertThat(messageRejects).hasSize(1);
        assertThat(messageRejects.get(0).getSessionRejectReasonCode()).isEqualTo(SessionRejectReasonCodes.REPEATING_GROUP_FIELDS_OUT_OF_ORDER);
        assertThat(messageRejects.get(0).getRefTagId()).isEqualTo(lowerOrderTag);
    }

    @Test
    void testOutOfOrderRepeatingGroupFieldToleratedWhenValidationDisabled() throws DecodingException {
        QuoteCancelEncoder encoder = getQuoteCancelEncoder();
        QuoteCancelNoQuoteEntriesEncoder entry = encoder.addNoQuoteEntries(1);
        entry.setSymbol("test symbol");
        entry.setAgreementCurrency("test currency");
        entry.setCFICode("test cfi");
        byte[] message = moveFieldAfter(toBytes(encodeQuoteCancel(encoder)), AgreementCurrency.get().getCode(), CFICode.get().getCode());

        fixMessageParser.parseMessages(ByteBuffer.wrap(message), getFixMessageDecoderFunction(mock(FixMessageDecoder.class)));

        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testInOrderRepeatingGroupNotRejectedWhenValidationEnabled() throws DecodingException {
        QuoteCancelEncoder encoder = getQuoteCancelEncoder();
        QuoteCancelNoQuoteEntriesEncoder entry = encoder.addNoQuoteEntries(2);
        for (int i = 0; i < 2; i++) {
            entry.setSymbol("test symbol " + i);
            entry.setAgreementCurrency("test currency " + i);
        }
        ByteBuffer encoded = encodeQuoteCancel(encoder);

        fixMessageParser = outOfOrderValidatingParser();
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        fixMessageParser.parseMessages(encoded.flip(), getFixMessageDecoderFunction(decoder));

        verify(decoder).onGroupStart(null, NoQuoteEntries.get(), 2);
        assertThat(messageRejects).isEmpty();
    }

    private FixMessageParser outOfOrderValidatingParser() {
        FixSessionSettings.ValidationSettings settings = validationSettings.toBuilder().validateFieldsOutOfOrder(true).build();
        return new FixMessageParser(fixSessionId, messageTypeRegistry, fieldsRegistry,
                fixMessagesLogger.getLogger("test", fixSessionId, messageTypeRegistry), settings, TestingClock.get(), fixMessageParserEventsListener);
    }

    private ByteBuffer encodeQuoteCancel(QuoteCancelEncoder encoder) {
        return encoder.encode(ByteBuffer::allocate, 1, fixSessionId, mock(FixApplication.class), TimeUnit.MICROSECONDS, TestingClock.get().now(), null);
    }

    @Test
    void testPossibleDuplicateFlag() throws DecodingException {
        String message = "8=FIX.4.49=11435=A34=143=Y49=TARGET_TEST52=20241013-19:07:17.861122=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=041";

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(decoder));

        assertThat(messageRejects).isEmpty();
        verify(decoder).onDecoded(any(FixSession.class), eq(true), eq(false));

        message = "8=FIX.4.49=8835=A34=143=N49=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=241";

        reset(decoder);
        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(decoder));

        assertThat(messageRejects).isEmpty();
        verify(decoder).onDecoded(any(FixSession.class), eq(false), eq(false));

        message = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249";

        reset(decoder);
        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(decoder));

        assertThat(messageRejects).isEmpty();
        verify(decoder).onDecoded(any(FixSession.class), eq(false), eq(false));
    }

    @Test
    void testPossibleResendFlag() throws DecodingException {
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        String message = fixMessage("35=A", "34=1", "49=TARGET_TEST", "97=Y", "52=20241013-19:07:17.861",
                "56=SENDER_TEST", "98=0", "108=30", "141=Y");
        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(decoder));

        assertThat(messageRejects).isEmpty();
        verify(fixMessageParserEventsListener).onMessageDecoded(any(), eq(decoder), eq(1L), eq(false), eq(true), anyLong(), any(UTCTime.class));
        // captured by the header switch, yet still handed to the decoder like any other field
        verify(decoder).onField(argThat(field -> field != null && field.getCode() == CoreFields.POSS_RESEND), any());

        message = fixMessage("35=A", "34=1", "49=TARGET_TEST", "97=N", "52=20241013-19:07:17.861",
                "56=SENDER_TEST", "98=0", "108=30", "141=Y");

        reset(decoder, fixMessageParserEventsListener);
        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(decoder));

        assertThat(messageRejects).isEmpty();
        verify(fixMessageParserEventsListener).onMessageDecoded(any(), eq(decoder), eq(1L), eq(false), eq(false), anyLong(), any(UTCTime.class));

        message = fixMessage("35=A", "34=1", "49=TARGET_TEST", "52=20241013-19:07:17.861",
                "56=SENDER_TEST", "98=0", "108=30", "141=Y");

        reset(decoder, fixMessageParserEventsListener);
        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(decoder));

        assertThat(messageRejects).isEmpty();
        verify(fixMessageParserEventsListener).onMessageDecoded(any(), eq(decoder), eq(1L), eq(false), eq(false), anyLong(), any(UTCTime.class));
    }

    @Test
    void testPossibleResendFlagIsResetBetweenMessages() throws DecodingException {
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        // three messages in a single buffer: the flag must follow each message and never stick from the previous one
        String messages = fixMessage("35=A", "34=1", "49=TARGET_TEST", "97=Y", "52=20241013-19:07:17.861",
                "56=SENDER_TEST", "98=0", "108=30", "141=Y")
                + fixMessage("35=A", "34=2", "49=TARGET_TEST", "52=20241013-19:07:17.861",
                "56=SENDER_TEST", "98=0", "108=30", "141=Y")
                + fixMessage("35=A", "34=3", "49=TARGET_TEST", "97=N", "52=20241013-19:07:17.861",
                "56=SENDER_TEST", "98=0", "108=30", "141=Y");

        fixMessageParser.parseMessages((ByteBuffer.wrap(messages.getBytes())), getFixMessageDecoderFunction(decoder));

        assertThat(messageRejects).isEmpty();
        verify(fixMessageParserEventsListener).onMessageDecoded(any(), eq(decoder), eq(1L), eq(false), eq(true), anyLong(), any(UTCTime.class));
        verify(fixMessageParserEventsListener).onMessageDecoded(any(), eq(decoder), eq(2L), eq(false), eq(false), anyLong(), any(UTCTime.class));
        verify(fixMessageParserEventsListener).onMessageDecoded(any(), eq(decoder), eq(3L), eq(false), eq(false), anyLong(), any(UTCTime.class));
    }

    @Test
    void testPossibleResendAndPossibleDuplicateAreNotCrossed() throws DecodingException {
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        String message = fixMessage("35=A", "34=1", "43=Y", "49=TARGET_TEST", "97=Y", "52=20241013-19:07:17.861",
                "122=20241013-19:07:17.861", "56=SENDER_TEST", "98=0", "108=30", "141=Y");
        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(decoder));

        assertThat(messageRejects).isEmpty();
        verify(fixMessageParserEventsListener).onMessageDecoded(any(), eq(decoder), eq(1L), eq(true), eq(true), anyLong(), any(UTCTime.class));
    }

    @Test
    void testMalformedPossibleResendIsNotTerminal() throws DecodingException {
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        // PossResend drives nothing in the session layer, so an unparseable value must not abort the parsing of the
        // message the way a malformed PossDupFlag does: the message is decoded with the flag simply not set
        String message = fixMessage("35=A", "34=1", "49=TARGET_TEST", "97=X", "52=20241013-19:07:17.861",
                "56=SENDER_TEST", "98=0", "108=30", "141=Y");
        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(decoder));

        verify(fixMessageParserEventsListener).onMessageDecoded(any(), eq(decoder), eq(1L), eq(false), eq(false), anyLong(), any(UTCTime.class));
    }

    @Test
    void testPossDupWithoutOrigSendingTimeRejected() throws DecodingException {
        String message = "8=FIX.4.4\u00019=88\u000135=A\u000134=1\u000143=Y\u000149=TARGET_TEST\u000152=20241013-19:07:17.861\u000156=SENDER_TEST\u000198=0\u0001108=30\u0001141=Y\u000110=252\u0001";

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(decoder));

        assertThat(messageRejects).hasSize(1);
        assertThat(messageRejects.get(0).getSessionRejectReasonCode()).isEqualTo(SessionRejectReasonCodes.REQUIRED_TAG_MISSING);
        assertThat(messageRejects.get(0).getRefTagId()).isEqualTo(122);
        verify(decoder, never()).onDecoded(any(FixSession.class), anyBoolean(), anyBoolean());
    }

    @Test
    void testInvalidReceivedField() throws DecodingException {
        String message = "8=FIX.4.49=8535=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST1888=0108=30141=Y10=099";
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(decoder), () -> 1, 0);

        verify(decoder).onDecodingFailed(any(FixSession.class), any(RejectedMessageException.class));
        assertThat(messageRejects).hasSize(1);
        assertThat(messageRejects.get(0).getMessage()).isEqualTo("Invalid tag number");
        assertThat(messageRejects.get(0).getSessionRejectReasonCode()).isEqualTo(SessionRejectReasonCodes.INVALID_TAG_NUMBER);
        assertThat(messageRejects.get(0).getRefTagId()).isEqualTo(1888);
    }

    @Test
    void testUndefinedTag() throws DecodingException {
        String message = "8=FIX.4.49=8535=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST5000=0108=30141=Y10=079";
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(decoder), () -> 1, 0);

        verify(decoder).onDecodingFailed(any(FixSession.class), any(RejectedMessageException.class));
        assertThat(messageRejects).hasSize(1);
        assertThat(messageRejects.get(0).getMessage()).isEqualTo("Undefined tag");
        assertThat(messageRejects.get(0).getSessionRejectReasonCode()).isEqualTo(SessionRejectReasonCodes.UNDEFINED_TAG);
        assertThat(messageRejects.get(0).getRefTagId()).isEqualTo(5000);
    }

    @Test
    void testMaxSendingTime() throws DecodingException {
        String message = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249";
        UTCTime messageTime = UtcDateTimeSerde.deserialize("20241013-19:07:17.861".getBytes());
        TestingClock testClock = TestingClock.get();
        testClock.setNowEpochNanos(messageTime.toEpochNanos());

        Duration maxAge = Duration.ofMillis(100);
        validationSettings = validationSettings.toBuilder().maxSendingTime(maxAge).build();
        fixMessageParser = new FixMessageParser(fixSessionId, messageTypeRegistry, fieldsRegistry, fixMessagesLogger.getLogger("test", fixSessionId, mock(MessageTypeRegistry.class)),
                validationSettings, testClock, fixMessageParserEventsListener);

        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(mock(FixMessageDecoder.class)), () -> 1, 0);
        assertThat(messageRejects).isEmpty();

        testClock.setNowEpochNanos(messageTime.toEpochNanos() + maxAge.toNanos() + TimeUnit.MILLISECONDS.toNanos(1));

        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(mock(FixMessageDecoder.class)));
        assertThat(messageRejects).hasSize(1);
        assertThat(messageRejects.get(0).getMessage()).isEqualTo("Sending time accuracy problem, received message is 101 ms old, max allowed sending time age is 100 ms");
        assertThat(messageRejects.get(0).getSessionRejectReasonCode()).isEqualTo(SessionRejectReasonCodes.SENDING_TIME_ACCURACY_PROBLEM);
    }

    @Test
    void testMaxSendingTimeInTheFuture() throws DecodingException {
        // Section 4.2.3 makes the threshold two-sided: a SendingTime(52) dated ahead of the receiver's clock is the
        // same accuracy problem as a stale one, and is what a peer whose clock runs fast looks like.
        String message = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249";
        UTCTime messageTime = UtcDateTimeSerde.deserialize("20241013-19:07:17.861".getBytes());
        TestingClock testClock = TestingClock.get();

        Duration maxDeviation = Duration.ofMillis(100);
        validationSettings = validationSettings.toBuilder().maxSendingTime(maxDeviation).build();
        fixMessageParser = new FixMessageParser(fixSessionId, messageTypeRegistry, fieldsRegistry, fixMessagesLogger.getLogger("test", fixSessionId, mock(MessageTypeRegistry.class)),
                validationSettings, testClock, fixMessageParserEventsListener);

        // the message is dated 101ms ahead of the local clock, one millisecond past the tolerance
        testClock.setNowEpochNanos(messageTime.toEpochNanos() - maxDeviation.toNanos() - TimeUnit.MILLISECONDS.toNanos(1));

        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(mock(FixMessageDecoder.class)), () -> 1, 0);
        assertThat(messageRejects).hasSize(1);
        assertThat(messageRejects.get(0).getMessage()).isEqualTo("Sending time accuracy problem, received message is dated 101 ms in the future, max allowed sending time deviation is 100 ms");
        assertThat(messageRejects.get(0).getSessionRejectReasonCode()).isEqualTo(SessionRejectReasonCodes.SENDING_TIME_ACCURACY_PROBLEM);
    }

    @Test
    void testWrongChecksum() throws DecodingException {
        String message = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=248";
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(decoder));

        // FIX Session Layer 4.5.2: an incorrect CheckSum(10) makes the message garbled, so it is disregarded as a
        // whole instead of being rejected
        verify(decoder, never()).onDecoded(any(FixSession.class), anyBoolean(), anyBoolean());
        verify(decoder).onDecodingFailed(any(FixSession.class), assertArg(ex -> assertThat(ex)
                .isInstanceOf(GarbledMessageException.class)
                .hasMessage("Garbled message received, ignoring it: CheckSum(10) is 248 but computed 249")));
        assertThat(messageRejects).isEmpty();

        // with the checksum validation disabled the message is not checked at all, so it is processed normally
        FixMessageDecoder notValidatedDecoder = mock(FixMessageDecoder.class);
        fixMessageParser = parserWith(validationSettings.toBuilder().validateChecksum(false).build());

        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(notValidatedDecoder));

        verify(notValidatedDecoder).onDecoded(any(FixSession.class), eq(false), eq(false));
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testChecksumValueNotThreeCharactersIsGarbled() throws DecodingException {
        // FIX Session Layer 4.5.2 requires the CheckSum(10) to be exactly three characters, zero padded. This
        // message computes to a checksum of 000 and sends it as "0": the value itself is right, so only its length
        // makes the message garbled.
        String message = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.49956=SENDER_TEST98=0108=30141=Y10=0";
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages(ByteBuffer.wrap(message.getBytes()), getFixMessageDecoderFunction(decoder));

        verify(decoder, never()).onDecoded(any(FixSession.class), anyBoolean(), anyBoolean());
        verify(decoder).onDecodingFailed(any(FixSession.class), assertArg(ex -> assertThat(ex)
                .isInstanceOf(GarbledMessageException.class)
                .hasMessage("Garbled message received, ignoring it: CheckSum(10) value must be 3 characters long but got 0")));
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testWrongBodyLength() throws DecodingException {
        String message = "8=FIX.4.49=8235=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=248";
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(decoder));

        // FIX Session Layer 4.5.2: an incorrect BodyLength(9) byte count makes the message garbled, so it is
        // disregarded as a whole instead of being rejected. Detected whatever the detectGarbledMessages setting.
        verify(decoder, never()).onDecoded(any(FixSession.class), anyBoolean(), anyBoolean());
        verify(decoder).onDecodingFailed(any(FixSession.class), assertArg(ex -> assertThat(ex)
                .isInstanceOf(GarbledMessageException.class)
                .hasMessage("Garbled message received, ignoring it: BodyLength(9) is 82 but computed 83")));
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testWrongBodyLengthAndChecksum() throws DecodingException {
        String message = "8=FIX.4.49=8235=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249";
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages((ByteBuffer.wrap(message.getBytes())), getFixMessageDecoderFunction(decoder));

        // the message is garbled (wrong BodyLength) and additionally carries a wrong checksum: being garbled it is
        // disregarded as a whole, so the checksum problem is not reported back to the peer either
        verify(decoder, never()).onDecoded(any(FixSession.class), anyBoolean(), anyBoolean());
        verify(decoder).onDecodingFailed(any(FixSession.class), assertArg(ex -> assertThat(ex)
                .isInstanceOf(GarbledMessageException.class)
                .hasMessage("Garbled message received, ignoring it: BodyLength(9) is 82 but computed 83")));
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testUndefinedBeginStringIsGarbled() throws DecodingException {
        // FIX Session Layer 4.5.2: a BeginString(8) that is not a defined FIX session profile identifier makes the
        // message garbled. This differs from a well-formed but unexpected version, which is a WrongBeginStringException
        // (see testWrongBeginStringRolledBackWhenValidationEnabled). BodyLength(9) does not cover BeginString(8), so
        // only the checksum had to be adjusted.
        String message = "8=BANANA.19=8335=A34=149=TARGET_TEST52=20241013-19:07:17.861"
                + "56=SENDER_TEST98=0108=30141=Y10=078";
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser = garbledDetectingParser();
        fixMessageParser.parseMessages(ByteBuffer.wrap(message.getBytes()), getFixMessageDecoderFunction(decoder));

        verify(decoder, never()).onDecoded(any(FixSession.class), anyBoolean(), anyBoolean());
        verify(decoder).onDecodingFailed(any(FixSession.class), assertArg(ex -> assertThat(ex)
                .isInstanceOf(GarbledMessageException.class)
                .hasMessage("Garbled message received, ignoring it: BeginString(8) BANANA.1 is not a defined FIX session profile identifier")));
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testMsgTypeOutOfMandatedPositionIsGarbled() throws DecodingException {
        // FIX Session Layer 4.5.2: BeginString(8), BodyLength(9) and MsgType(35) must be the first three fields, in
        // that order. Swapping BodyLength(9) and MsgType(35) preserves the byte multiset, so the checksum stays valid
        // and only the ordering is breached.
        String message = "8=FIX.4.435=A9=8334=149=TARGET_TEST52=20241013-19:07:17.861"
                + "56=SENDER_TEST98=0108=30141=Y10=249";
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser = garbledDetectingParser();
        fixMessageParser.parseMessages(ByteBuffer.wrap(message.getBytes()), getFixMessageDecoderFunction(decoder));

        verify(decoder, never()).onDecoded(any(FixSession.class), anyBoolean(), anyBoolean());
        verify(decoder).onDecodingFailed(any(FixSession.class), assertArg(ex -> assertThat(ex)
                .isInstanceOf(GarbledMessageException.class)
                .hasMessage("Garbled message received, ignoring it: expecting tag 9 as field 2 of the message but got tag 35")));
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testGarbledMessageDoesNotDesynchronizeParsingOfTheNextMessage() throws DecodingException {
        // a garbled message must not desynchronize the parser: the next well-formed message of the same buffer is
        // parsed and decoded normally
        String garbled = "8=FIX.4.49=8235=A34=149=TARGET_TEST52=20241013-19:07:17.861"
                + "56=SENDER_TEST98=0108=30141=Y10=248";
        String valid = "8=FIX.4.49=8335=A34=249=TARGET_TEST52=20241013-19:07:17.861"
                + "56=SENDER_TEST98=0108=30141=Y10=250";
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages(ByteBuffer.wrap((garbled + valid).getBytes()), getFixMessageDecoderFunction(decoder));

        // the wrong BodyLength(9) of the first message makes it garbled instead of rejected
        verify(decoder).onDecodingFailed(any(FixSession.class), assertArg(ex -> assertThat(ex)
                .isInstanceOf(GarbledMessageException.class)
                .hasMessage("Garbled message received, ignoring it: BodyLength(9) is 82 but computed 83")));
        verify(decoder).onDecoded(any(FixSession.class), eq(false), eq(false));
        verify(fixMessageParserEventsListener).onMessageDecoded(any(), eq(decoder), eq(2L), eq(false), eq(false), anyLong(), any(UTCTime.class));
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testWrongSequenceNumberDetectedWithHalfProvidedBuffer() throws DecodingException {
        String messagePart1 = "8=FIX.4.49=8335=A49=TARGET_TEST52=20241013-";
        String messagePart2 = "19:07:17.86156=SENDER_TEST34=198=0108=30141=Y10=249";

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        ByteBuffer bb = ByteBuffer.allocate(1024);

        fixMessageParser.parseMessages(bb.put(messagePart1.getBytes()).flip(), getFixMessageDecoderFunction(decoder), () -> 100, 0);

        verify(decoder).onBegin(anyLong(), any());
        verify(decoder).onDecodingFailed(any(FixSession.class), any(NotEnoughDataException.class));
        assertThat(loggedMessages).isEmpty();
        assertThat(bb.position()).isZero();
        assertThat(bb.limit()).isEqualTo(47);

        bb.compact().put(messagePart2.getBytes()).flip();

        decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages(bb, getFixMessageDecoderFunction(decoder), () -> 100, 0);

        verify(decoder).onBegin(anyLong(), any());
        verify(decoder).onDecodingFailed(any(FixSession.class), any(WrongSeqNumException.class));
        assertThat(wrongSequenceExceptions).hasSize(1);
        assertThat(wrongSequenceExceptions.get(0).getMsgSeqNum()).isEqualTo(1);
        assertThat(wrongSequenceExceptions.get(0).getExpectedMsgSeqNum()).isEqualTo(100);
        // the message arrived in two reads, and what is kept for the eventual replay is still the whole of
        // it, BeginString(8) and CheckSum(10) included
        assertThat(rawMessageOf(0)).isEqualTo(messagePart1 + messagePart2);

        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testWrongSequenceNumberDetected() throws DecodingException {
        String firstMessage = "8=FIX.4.49=8335=A49=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST34=198=0108=30141=Y10=249";
        String secondMessage = "8=FIX.4.49=8335=A34=249=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=250";
        String message = firstMessage + secondMessage;
        AtomicInteger seqNum = new AtomicInteger(100);
        ByteBuffer msgBuffer = ByteBuffer.wrap(message.getBytes());

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages(msgBuffer, getFixMessageDecoderFunction(decoder), seqNum::incrementAndGet, 0);

        verify(decoder, times(2)).onBegin(anyLong(), any());
        verify(decoder, times(2)).onDecodingFailed(any(FixSession.class), any(WrongSeqNumException.class));

        assertThat(wrongSequenceExceptions).hasSize(2);
        assertThat(wrongSequenceExceptions.get(0).getMsgSeqNum()).isEqualTo(1);
        assertThat(wrongSequenceExceptions.get(0).getExpectedMsgSeqNum()).isEqualTo(101);
        // each one is kept whole, ready to be replayed once the gap before it has been filled
        assertThat(rawMessageOf(0)).isEqualTo(firstMessage);

        assertThat(wrongSequenceExceptions.get(1).getMsgSeqNum()).isEqualTo(2);
        assertThat(wrongSequenceExceptions.get(1).getExpectedMsgSeqNum()).isEqualTo(102);
        assertThat(rawMessageOf(1)).isEqualTo(secondMessage);

        assertThat(msgBuffer.position()).isEqualTo(message.length());
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void test2MessagesInBufferParsing() throws DecodingException {
        String message1 = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249";
        String message2 = "8=FIX.4.49=8335=A34=249=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=250";

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages((ByteBuffer.wrap((message1 + message2).getBytes())), getFixMessageDecoderFunction(decoder));

        verify(decoder, times(2)).onBegin(anyLong(), any());
        verify(decoder, times(2)).onDecoded(any(FixSession.class), eq(false), eq(false));

        verify(fixMessageParserEventsListener).onMessageDecoded(any(), eq(decoder), eq(1L), eq(false), eq(false), anyLong(), any(UTCTime.class));
        verify(fixMessageParserEventsListener).onMessageDecoded(any(), eq(decoder), eq(2L), eq(false), eq(false), anyLong(), any(UTCTime.class));

        assertThat(messageRejects).isEmpty();
        assertThat(loggedMessages).hasSize(2);
        assertThat(loggedMessages.get(0)).isEqualTo(message1);
        assertThat(loggedMessages.get(1)).isEqualTo(message2);
        assertThat(loggedMessagesTimeStamps.get(0)).isEqualTo(loggedMessagesTimeStamps.get(1));
    }

    @Test
    void test3MessagesAndAHalfInBufferParsing() throws DecodingException {
        String messagePart1 = "8=FIX.4.49=8335=A34=349=TARGET_TEST52=";
        String messagePart2 = "20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=251";

        String message1 = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249";
        String message2 = "8=FIX.4.49=8335=A34=249=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=250";
        String firstMessage = message1 + message2 + messagePart1;
        String message3 = "8=FIX.4.49=8335=A34=449=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=252";
        String secondMessage = messagePart2 + message3;

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        ByteBuffer bb = ByteBuffer.allocate(1024);

        fixMessageParser.parseMessages(bb.put((firstMessage).getBytes()).flip(), getFixMessageDecoderFunction(decoder));

        verify(decoder, times(3)).onBegin(anyLong(), any());
        verify(decoder, times(2)).onDecoded(any(FixSession.class), eq(false), eq(false));

        assertThat(bb.hasRemaining()).isTrue();
        assertThat(bb.position()).isEqualTo(firstMessage.length() - 3);

        fixMessageParser.parseMessages(bb.compact().put(secondMessage.getBytes()).flip(), msgType -> decoder);

        verify(decoder, times(4)).onDecoded(any(FixSession.class), eq(false), eq(false));

        assertThat(bb.hasRemaining()).isFalse();

        assertThat(messageRejects).isEmpty();
        assertThat(loggedMessages).hasSize(4);
        assertThat(loggedMessages.get(0)).isEqualTo(message1);
        assertThat(loggedMessages.get(1)).isEqualTo(message2);
        assertThat(loggedMessages.get(2)).isEqualTo(messagePart1 + messagePart2);
        assertThat(loggedMessages.get(3)).isEqualTo(message3);
    }

    @Test
    void testParsingHeaderFields() throws DecodingException {
        String message = "8=FIX.4.49=8335=A49=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST34=198=0108=30141=Y10=249";
        ByteBuffer bb = ByteBuffer.wrap(message.getBytes());

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages(bb, getFixMessageDecoderFunction(decoder));

        verify(decoder).onField(eq(SenderCompID.get()), any(SerDe.DeserializationContext.class));
        verify(decoder).onField(eq(TargetCompID.get()), any(SerDe.DeserializationContext.class));
        verify(decoder).onField(eq(EncryptMethod.get()), any(SerDe.DeserializationContext.class));
        verify(decoder).onField(eq(HeartBtInt.get()), any(SerDe.DeserializationContext.class));
        verify(decoder).onBegin(anyLong(), any());

        assertThat(bb.hasRemaining()).isFalse();
        assertThat(messageRejects).isEmpty();
        assertThat(loggedMessages).hasSize(1);
        assertThat(loggedMessages.get(0)).isEqualTo(message);
    }

    @Test
    void testWrongSenderOrTargetCompIdFails() throws DecodingException {
        validationSettings = validationSettings.toBuilder().validateCompId(true).build();

        fixMessageParser = new FixMessageParser(fixSessionId, messageTypeRegistry, fieldsRegistry,
                fixMessagesLogger.getLogger("test", fixSessionId, messageTypeRegistry), validationSettings, TestingClock.get(), fixMessageParserEventsListener);

        String message = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249";
        ByteBuffer bb = ByteBuffer.wrap(message.getBytes());

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages(bb, getFixMessageDecoderFunction(decoder));

        assertThat(messageRejects).hasSize(2);

        assertThat(messageRejects.get(0).getMessage()).isEqualTo("Wrong SENDER_COMP_ID, expecting SENDER_TEST but got TARGET_TEST");
        assertThat(messageRejects.get(0).getRefTagId()).isEqualTo(49);
        assertThat(messageRejects.get(0).getBusinessRejectReasonCode()).isNull();
        assertThat(messageRejects.get(0).getSessionRejectReasonCode()).isEqualTo(SessionRejectReasonCodes.COMPID_PROBLEM);

        assertThat(messageRejects.get(1).getMessage()).isEqualTo("Wrong TARGET_COMP_ID, expecting TARGET_TEST but got SENDER_TEST");
        assertThat(messageRejects.get(1).getRefTagId()).isEqualTo(56);
        assertThat(messageRejects.get(1).getBusinessRejectReasonCode()).isNull();
        assertThat(messageRejects.get(1).getSessionRejectReasonCode()).isEqualTo(SessionRejectReasonCodes.COMPID_PROBLEM);

    }

    @Test
    void testMatchingSenderAndTargetCompIdAccepted() throws DecodingException {
        // swapping the two CompID values keeps the byte multiset intact, so BodyLength and checksum stay valid
        // while 49/56 now carry what the session expects
        String message = "8=FIX.4.49=8335=A34=149=SENDER_TEST52=20241013-19:07:17.86156=TARGET_TEST98=0108=30141=Y10=249";

        fixMessageParser = parserWith(validationSettings.toBuilder().validateCompId(true).build());
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages(ByteBuffer.wrap(message.getBytes()), getFixMessageDecoderFunction(decoder));

        verify(decoder).onDecoded(any(FixSession.class), eq(false), eq(false));
        verify(decoder, never()).onDecodingFailed(any(FixSession.class), any(DecodingException.class));
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testWrongSenderOrTargetCompIdToleratedWhenValidationDisabled() throws DecodingException {
        // 49/56 hold the opposite of what the session expects, but validateCompId is off in the default settings
        String message = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249";

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        fixMessageParser.parseMessages(ByteBuffer.wrap(message.getBytes()), getFixMessageDecoderFunction(decoder));

        verify(decoder).onDecoded(any(FixSession.class), eq(false), eq(false));
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testExpectedOnBehalfOfAndDeliverToCompIdAccepted() throws DecodingException {
        fixMessageParser = parserWith(validationSettings.toBuilder()
                .expectedOnBehalfOfCompIds(Set.of("FIRM_A", "FIRM_B"))
                .expectedDeliverToCompIds(Set.of("FIRM_C"))
                .build());
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages(ByteBuffer.wrap(thirdPartyRoutedMessage("FIRM_B", "FIRM_C").getBytes()),
                getFixMessageDecoderFunction(decoder));

        verify(decoder).onDecoded(any(FixSession.class), eq(false), eq(false));
        verify(decoder, never()).onDecodingFailed(any(FixSession.class), any(DecodingException.class));
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testUnexpectedOnBehalfOfCompIdRejected() throws DecodingException {
        fixMessageParser = parserWith(validationSettings.toBuilder()
                .expectedOnBehalfOfCompIds(Set.of("FIRM_A"))
                .build());
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages(ByteBuffer.wrap(thirdPartyRoutedMessage("FIRM_X", "ANYTHING").getBytes()),
                getFixMessageDecoderFunction(decoder));

        assertThat(messageRejects).hasSize(1);
        assertThat(messageRejects.get(0).getRefTagId()).isEqualTo(CoreFields.ON_BEHALF_OF_COMP_ID);
        assertThat(messageRejects.get(0).getSessionRejectReasonCode()).isEqualTo(SessionRejectReasonCodes.COMPID_PROBLEM);
        assertThat(messageRejects.get(0).getBusinessRejectReasonCode()).isNull();
        assertThat(messageRejects.get(0).getMessage()).contains("ON_BEHALF_OF_COMP_ID", "FIRM_X");
        // DeliverToCompID is not configured here, so it is not checked even though the message carries one
    }

    @Test
    void testUnexpectedDeliverToCompIdRejected() throws DecodingException {
        fixMessageParser = parserWith(validationSettings.toBuilder()
                .expectedDeliverToCompIds(Set.of("FIRM_C"))
                .build());
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages(ByteBuffer.wrap(thirdPartyRoutedMessage("ANYTHING", "FIRM_X").getBytes()),
                getFixMessageDecoderFunction(decoder));

        assertThat(messageRejects).hasSize(1);
        assertThat(messageRejects.get(0).getRefTagId()).isEqualTo(CoreFields.DELIVER_TO_COMP_ID);
        assertThat(messageRejects.get(0).getSessionRejectReasonCode()).isEqualTo(SessionRejectReasonCodes.COMPID_PROBLEM);
        assertThat(messageRejects.get(0).getMessage()).contains("DELIVER_TO_COMP_ID", "FIRM_X");
    }

    @Test
    void testThirdPartyCompIdsToleratedWhenNotConfigured() throws DecodingException {
        // the default settings configure neither set, so routing fields are accepted whatever they hold
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages(ByteBuffer.wrap(thirdPartyRoutedMessage("FIRM_X", "FIRM_Y").getBytes()),
                getFixMessageDecoderFunction(decoder));

        verify(decoder).onDecoded(any(FixSession.class), eq(false), eq(false));
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testThirdPartyAndSenderTargetCompIdChecksAreIndependent() throws DecodingException {
        // only the third party sets are configured: 49/56 stay unchecked even though they are wrong here, the two
        // checks sharing one validator without sharing their configuration
        fixMessageParser = parserWith(validationSettings.toBuilder()
                .expectedOnBehalfOfCompIds(Set.of("FIRM_A"))
                .build());
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        String swappedSenderAndTarget = fixMessage("35=A", "34=1", "49=TARGET_TEST", "115=FIRM_A",
                "52=20241013-19:07:17.861", "56=SENDER_TEST", "98=0", "108=30", "141=Y");
        fixMessageParser.parseMessages(ByteBuffer.wrap(swappedSenderAndTarget.getBytes()), getFixMessageDecoderFunction(decoder));

        verify(decoder).onDecoded(any(FixSession.class), eq(false), eq(false));
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testWrongBeginStringRolledBackWhenValidationEnabled() throws DecodingException {
        // only the BeginString differs from the session's FIX.4.4: BodyLength does not cover it and the checksum
        // is adjusted, so the message is otherwise perfectly valid
        String message = "8=FIX.4.29=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=247";

        fixMessageParser = parserWith(validationSettings.toBuilder().validateBeginString(true).build());
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages(ByteBuffer.wrap(message.getBytes()), getFixMessageDecoderFunction(decoder));

        verify(decoder, never()).onDecoded(any(FixSession.class), anyBoolean(), anyBoolean());
        verify(decoder).onDecodingFailed(any(FixSession.class), assertArg(ex -> {
            assertThat(ex).isInstanceOf(WrongBeginStringException.class);
            assertThat(((WrongBeginStringException) ex).getExpectedBeginString()).isEqualTo("FIX.4.4");
            assertThat(((WrongBeginStringException) ex).getReceivedBeginString()).isEqualTo("FIX.4.2");
        }));
        // a wrong BeginString is a decoding failure, not a field level reject
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testMatchingBeginStringAcceptedWhenValidationEnabled() throws DecodingException {
        String message = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249";

        fixMessageParser = parserWith(validationSettings.toBuilder().validateBeginString(true).build());
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages(ByteBuffer.wrap(message.getBytes()), getFixMessageDecoderFunction(decoder));

        verify(decoder).onDecoded(any(FixSession.class), eq(false), eq(false));
        verify(decoder, never()).onDecodingFailed(any(FixSession.class), any(DecodingException.class));
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testWrongBeginStringToleratedWhenValidationDisabled() throws DecodingException {
        String message = "8=FIX.4.29=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=247";

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        fixMessageParser.parseMessages(ByteBuffer.wrap(message.getBytes()), getFixMessageDecoderFunction(decoder));

        verify(decoder).onDecoded(any(FixSession.class), eq(false), eq(false));
        verify(decoder, never()).onDecodingFailed(any(FixSession.class), any(DecodingException.class));
        assertThat(messageRejects).isEmpty();
    }

    private FixMessageParser garbledDetectingParser() {
        return parserWith(validationSettings.toBuilder().detectGarbledMessages(true).build());
    }

    private FixMessageParser parserWith(FixSessionSettings.ValidationSettings settings) {
        return new FixMessageParser(fixSessionId, messageTypeRegistry, fieldsRegistry,
                fixMessagesLogger.getLogger("test", fixSessionId, messageTypeRegistry), settings, TestingClock.get(), fixMessageParserEventsListener);
    }

    @Test
    void testSimpleParsing() throws DecodingException {
        String message = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249";
        ByteBuffer bb = ByteBuffer.wrap(message.getBytes());

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages(bb, getFixMessageDecoderFunction(decoder));

        verify(decoder).onField(eq(SenderCompID.get()), any(SerDe.DeserializationContext.class));
        verify(decoder).onField(eq(TargetCompID.get()), any(SerDe.DeserializationContext.class));
        verify(decoder).onField(eq(EncryptMethod.get()), any(SerDe.DeserializationContext.class));
        verify(decoder).onField(eq(HeartBtInt.get()), any(SerDe.DeserializationContext.class));

        assertThat(bb.hasRemaining()).isFalse();
        assertThat(messageRejects).isEmpty();
        assertThat(loggedMessages).hasSize(1);
        assertThat(loggedMessages.get(0)).isEqualTo(message);
    }

    @Test
    void testParsingWithDataField() throws DecodingException {
        String encodedText = "testEncodedtext";
        NewOrderSingleEncoder nosEncoder = FixMessageEncoderFactory.Registry.getInstance(fixDictionaryId).newInstance(NewOrderSingleEncoder.class, null, null, null, null);
        nosEncoder.begin();
        nosEncoder.setEncodedTextLen(encodedText.length())
                .setEncodedText(encodedText);
        nosEncoder.setAccount("testAccount");

        ByteBuffer encoded = nosEncoder.encode(ByteBuffer::allocate, 1, fixSessionId, mock(FixApplication.class), TimeUnit.MICROSECONDS, TestingClock.get().now(), null);

        SetOnlyOnceRef<String> encodedTextRef = new SetOnlyOnceRef<>();
        SetOnlyOnceRef<String> accountRef = new SetOnlyOnceRef<>();
        fixMessageParser.parseMessages(encoded.flip(), msgType -> new FixMessageDecoderImpl(mock(FieldsRegistry.class), new TestParsingWithDataField(msgType, encodedTextRef, accountRef), fixDictionaryId,
                FixSessionSettings.ValidationSettings.builder().build(), mock(FixSessionId.class)));

        assertThat(encodedTextRef.get()).isEqualTo(encodedText);
        assertThat(accountRef.get()).isEqualTo("testAccount");
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testParsingWithFieldContainingEquals() throws DecodingException {
        String message = "8=FIX.4.49=8335=A34=149=SENDER=TEST52=20241013-19:07:17.86156=TARGET=TEST98=0108=30141=Y10=181";

        SetOnlyOnceRef<String> senderCompId = new SetOnlyOnceRef<>();
        SetOnlyOnceRef<String> targetCompId = new SetOnlyOnceRef<>();

        fixMessageParser.parseMessages(ByteBuffer.wrap(message.getBytes()),
                msgType -> new FixMessageDecoderImpl(mock(FieldsRegistry.class), new TestParsingWithFieldContainingEquals(msgType, senderCompId, targetCompId), fixDictionaryId,
                        FixSessionSettings.ValidationSettings.builder().build(), mock(FixSessionId.class)));

        assertThat(senderCompId.get()).isEqualTo("SENDER=TEST");
        assertThat(targetCompId.get()).isEqualTo("TARGET=TEST");
        assertThat(messageRejects).isEmpty();
    }

    @Test
    void testParsingWithFieldHavingNoValue() throws DecodingException {
        String message = "8=FIX.4.49=8235=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=108=30141=Y10=200";
        ByteBuffer bb = ByteBuffer.wrap(message.getBytes());

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);

        fixMessageParser.parseMessages(bb, getFixMessageDecoderFunction(decoder));

        verify(decoder).onField(eq(SenderCompID.get()), any(SerDe.DeserializationContext.class));
        verify(decoder).onField(eq(TargetCompID.get()), any(SerDe.DeserializationContext.class));
        verify(decoder, never()).onField(eq(HeartBtInt.get()), any(SerDe.DeserializationContext.class));
        verify(decoder, never()).onField(eq(EncryptMethod.get()), any(SerDe.DeserializationContext.class));
        verify(decoder).onDecodingFailed(any(FixSession.class), any(RejectedMessageException.class));
        assertThat(messageRejects).hasSize(1);
        assertThat(messageRejects.get(0).getMessage()).isEqualTo("Empty tag value");
        assertThat(messageRejects.get(0).getRefTagId()).isEqualTo(98);
        assertThat(messageRejects.get(0).getBusinessRejectReasonCode()).isNull();
        assertThat(messageRejects.get(0).getSessionRejectReasonCode()).isEqualTo(SessionRejectReasonCodes.TAG_SPECIFIED_WITHOUT_A_VALUE);
        assertThat(bb.hasRemaining()).isFalse();
    }

    @Test
    void testParsingWithFieldHavingNoValueWithCheckDisabled() throws DecodingException {
        String message = "8=FIX.4.49=8235=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=108=30141=Y10=200";
        ByteBuffer bb = ByteBuffer.wrap(message.getBytes());

        validationSettings = FixSessionSettings.ValidationSettings.builder()
                .validateFieldsHaveValues(false)
                .build();

        fixMessageParser = new FixMessageParser(fixSessionId, messageTypeRegistry, fieldsRegistry,
                fixMessagesLogger.getLogger("test", fixSessionId, messageTypeRegistry), validationSettings,
                TestingClock.get(), fixMessageParserEventsListener);

        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        fixMessageParser.parseMessages(bb, getFixMessageDecoderFunction(decoder));

        verify(decoder).onField(eq(SenderCompID.get()), any(SerDe.DeserializationContext.class));
        verify(decoder).onField(eq(TargetCompID.get()), any(SerDe.DeserializationContext.class));
        verify(decoder).onField(eq(HeartBtInt.get()), any(SerDe.DeserializationContext.class));
        verify(decoder, never()).onField(eq(EncryptMethod.get()), any(SerDe.DeserializationContext.class));
        verify(decoder).onDecoded(any(FixSession.class), anyBoolean(), anyBoolean());
        assertThat(messageRejects).isEmpty();
        assertThat(bb.hasRemaining()).isFalse();
    }

    private QuoteCancelEncoder getQuoteCancelEncoder() {
        QuoteCancelEncoder encoder = FixMessageEncoderFactory.Registry.getInstance(fixDictionaryId).newInstance(QuoteCancelEncoder.class, null, null, null, null);
        encoder.begin();
        encoder.setQuoteID("testQuoteId");
        encoder.setQuoteCancelType(QuoteCancelType.QuoteCancelTypeValues.CANCEL_ALL_QUOTES);
        return encoder;
    }

    @Getter
    @AllArgsConstructor
    private static class TestParsingWithFieldContainingEquals implements FixMessageDecoder {

        MessageType messageType;
        SetOnlyOnceRef<String> senderCompId;
        SetOnlyOnceRef<String> targetCompId;

        @Override
        public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
            fixFieldsDecoderMapper.mapStringField(SenderCompID.get(), senderCompId::set, null);
            fixFieldsDecoderMapper.mapStringField(TargetCompID.get(), targetCompId::set, null);
        }
    }

    @Getter
    @AllArgsConstructor
    private static class TestParsingWithDataField implements FixMessageDecoder {

        MessageType messageType;
        SetOnlyOnceRef<String> encodedTextRef;
        SetOnlyOnceRef<String> accountRef;

        @Override
        public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
            fixFieldsDecoderMapper.mapStringField(EncodedText.get(), encodedTextRef::set, null)
                    .mapStringField(Account.get(), accountRef::set, null);
        }
    }

    private static class SetOnlyOnceRef<T> {
        private T value;
        private boolean setOnce;

        T get() {
            return value;
        }

        void set(T value) {
            if (!setOnce) {
                setOnce = true;
                this.value = value;
            }
        }
    }
}