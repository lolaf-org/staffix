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
package org.lolaf.staffix.impl;

import lombok.Setter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lolaf.staffix.api.codec.*;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.codec.encoders.FixMessageEncoderImpl;
import org.lolaf.staffix.fix44.encoders.EmailEncoder;
import org.lolaf.staffix.fix44.encoders.MarketDataRequestRejectEncoder;
import org.lolaf.staffix.fix44.encoders.NewOrderSingleEncoder;
import org.lolaf.staffix.fix44.encoders.QuoteEncoder;
import org.lolaf.staffix.fix44.encoders.group.NoLinesOfTextEncoder;
import org.lolaf.staffix.fix44.fields.EffectiveTime;
import org.lolaf.staffix.fix44.fields.EmailType;
import org.lolaf.staffix.fix44.fields.MDReqRejReason;
import org.lolaf.staffix.fix44.fields.Spread;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TestFixMessagesValidation extends AbstractFixTests {

    @Override
    void setupMessageDecoders(ConnectorType connectorType) {
        super.setupMessageDecoders(connectorType);
        Map<MessageType, FixMessageDecoder> targetMap = connectorType.select(initiatorMessageDecoders, acceptorMessageDecoders);
        FixMessageDecoder nos = spy(new TestNewOrderSingleDecoder());
        FixMessageDecoder mdr = spy(new TestMarketDataRequestRejectDecoder());
        targetMap.put(nos.getMessageType(), nos);
        targetMap.put(mdr.getMessageType(), mdr);

        when(getFixApplication(connectorType).setup(any(), any(), any()))
                .thenReturn(List.of(nos, mdr, getDecoder(testMessageType, connectorType)));
    }

    @Test
    void testUnsupportedMessageByApplicationReceived() {
        when(fixInitiatorApplication.onNoDecoderSetupForMessage(any(), eq(MessageTypes.Quote))).thenReturn(true);

        logonClient();

        fixAcceptorSession.send(fixAcceptorSession.newEncoder(QuoteEncoder.class).begin(), null);

        await().untilAsserted(() -> verify(fixAcceptorApplication).onBusinessMessageReject(any(),
                Mockito.contains("Message type 'S' is not supported by application"),
                eq(BusinessRejectReasonCodes.UNSUPPORTED_MESSAGE_TYPE.getCode()), anyLong(), isNull(String.class), eq(MessageTypes.Quote.code())));
    }


    @Test
    void testUnsupportedMessageInDictionaryReceived() {
        logonClient();

        fixAcceptorSession.send(new UnknownMessageEncoder().begin(), null);

        await().untilAsserted(() -> verify(fixAcceptorApplication).onBusinessMessageReject(any(),
                Mockito.contains("Message type not supported"),
                eq(BusinessRejectReasonCodes.UNSUPPORTED_MESSAGE_TYPE.getCode()), anyLong(), anyString(), eq("UNKNOWN")));
    }

    @Test
    void testValidateRequiredFieldsForAdminMessage() {
        setupInitiatorSessionSettings(s ->
                s.validationSettings(FixSessionSettings.ValidationSettings.builder().validateRequiredFields(true).build()).build());

        logonClient();

        fixAcceptorSession.send(new TestRequestEncoder().begin(), null);

        // admin message should generate a reject and not a business message reject
        await().untilAsserted(() -> verify(fixAcceptorApplication).onMessageReject(any(),
                Mockito.contains("Field not found in message"),
                eq(SessionRejectReasonCodes.REQUIRED_TAG_MISSING.getCode()), anyLong(), eq(112), eq(CoreMessageType.TEST_REQUEST)));
    }

    @Test
    void testValidateBodyRequiredFields() {
        setupInitiatorSessionSettings(s ->
                s.validationSettings(FixSessionSettings.ValidationSettings.builder().validateRequiredFields(true).build()).build());

        logonClient();

        fixAcceptorSession.send(fixAcceptorSession.newEncoder(EmailEncoder.class).begin(), null);

        await().untilAsserted(() -> verify(fixAcceptorApplication, times(4)).onBusinessMessageReject(any(),
                Mockito.contains("Field not found in message"),
                eq(BusinessRejectReasonCodes.CONDITIONALLY_REQUIRED_FIELD_MISSING.getCode()), anyLong(), anyString(), eq(MessageTypes.Email.code())));
        verify(getDecoder(testMessageType, ConnectorType.INITIATOR)).onDecodingFailed(any(), any(RequiredFieldNotFoundException.class));
    }

    @Test
    void testValidateGroupRequiredFields() {
        setupInitiatorSessionSettings(s ->
                s.validationSettings(FixSessionSettings.ValidationSettings.builder().validateRequiredFields(true).build()).build());

        logonClient();

        EmailEncoder emailEncoder = fixAcceptorSession.newEncoder(EmailEncoder.class);
        emailEncoder.begin().setEmailType(EmailType.EmailTypeValues.NEW).setEmailThreadID("test thread id").setSubject("test subject");
        NoLinesOfTextEncoder lotEncoder = emailEncoder.addNoLinesOfText(2);
        for (int j = 0; j < 2; j++) {
            lotEncoder.setEncodedTextLen("encoded text".length());
            lotEncoder.setEncodedText("encoded text");
        }

        fixAcceptorSession.send(emailEncoder, null);

        await().untilAsserted(() -> verify(fixAcceptorApplication, times(2)).onBusinessMessageReject(any(),
                Mockito.contains("Group field not found in message"),
                eq(BusinessRejectReasonCodes.CONDITIONALLY_REQUIRED_FIELD_MISSING.getCode()), anyLong(), anyString(), eq(MessageTypes.Email.code())));
        verify(getDecoder(testMessageType, ConnectorType.INITIATOR)).onDecodingFailed(any(), any(RequiredGroupFieldNotFoundException.class));

    }

    @Test
    void testMaxSendingTime() {
        setupInitiatorSessionSettings(s -> s.validationSettings(FixSessionSettings.ValidationSettings.builder().maxSendingTime(Duration.ofMillis(50)).build()).build());

        logonClient();

        fixAcceptorSession.send(encodeTestMessage(), UTCTime.of(Instant.now().minusMillis(100)));

        await().untilAsserted(() -> verify(fixAcceptorApplication).onMessageReject(any(),
                Mockito.contains("Sending time accuracy problem, received message is"),
                eq(SessionRejectReasonCodes.SENDING_TIME_ACCURACY_PROBLEM.getCode()), anyLong(), anyInt(), any()));

        verify(getDecoder(testMessageType, ConnectorType.INITIATOR)).onDecodingFailed(any(), any(RejectedMessageException.class));

        await().untilAsserted(() -> {
            verify(fixInitiatorApplication).onLogout(any(), Mockito.eq("Logout due to sending accuracy problem"), any());
            verify(fixInitiatorApplication).onDisconnected(any());
            verify(fixAcceptorApplication).onLogout(any(), Mockito.eq("Logout due to sending accuracy problem"), any());
            verify(fixAcceptorApplication).onDisconnected(any());
        });
    }

    /**
     * The other side of the SendingTimeThreshold of section 4.2.3 of the FIX Session Layer specification: the
     * tolerance is on how much SendingTime(52) <i>differs</i> from the receiver's clock, so a message dated in the
     * future is as much of an accuracy problem as a stale one — it is how a peer whose clock runs ahead shows up.
     * Same remedy as the stale case: Reject(35=3) with SessionRejectReason(373) 10, then Logout(35=5).
     */
    @Test
    void testMaxSendingTimeInTheFuture() {
        setupInitiatorSessionSettings(s -> s.validationSettings(FixSessionSettings.ValidationSettings.builder().maxSendingTime(Duration.ofMillis(50)).build()).build());

        logonClient();

        // a full second ahead against a 50ms tolerance, so the time the message takes to arrive cannot eat the margin
        fixAcceptorSession.send(encodeTestMessage(), UTCTime.of(Instant.now().plusSeconds(1)));

        await().untilAsserted(() -> verify(fixAcceptorApplication).onMessageReject(any(),
                Mockito.contains("Sending time accuracy problem, received message is dated"),
                eq(SessionRejectReasonCodes.SENDING_TIME_ACCURACY_PROBLEM.getCode()), anyLong(), anyInt(), any()));

        verify(getDecoder(testMessageType, ConnectorType.INITIATOR)).onDecodingFailed(any(), any(RejectedMessageException.class));

        await().untilAsserted(() -> {
            verify(fixInitiatorApplication).onLogout(any(), Mockito.eq("Logout due to sending accuracy problem"), any());
            verify(fixInitiatorApplication).onDisconnected(any());
            verify(fixAcceptorApplication).onLogout(any(), Mockito.eq("Logout due to sending accuracy problem"), any());
            verify(fixAcceptorApplication).onDisconnected(any());
        });
    }

    @Test
    void testUnparseableDoubleFieldSent() {

        logonClient();

        NewOrderSingleEncoder nos = fixAcceptorSession.newEncoder(NewOrderSingleEncoder.class)
                .begin()
                .addString(Spread.get(), "wrong data type");

        fixInitiatorSession.send(nos, null);

        await().untilAsserted(() -> verify(fixInitiatorApplication).onMessageReject(any(),
                eq("Unable to parse number: wrong data type"), eq(SessionRejectReasonCodes.INCORRECT_DATA_FORMAT_FOR_VALUE.getCode()), anyLong(),
                eq(Spread.get().getCode()), eq(nos.getMessageType().code())));

        verify(getDecoder(MessageTypes.NewOrderSingle, ConnectorType.ACCEPTOR)).onDecodingFailed(any(), any(RejectedMessageException.class));

    }

    @Test
    void testUnparseableDateFieldSent() {

        logonClient();

        NewOrderSingleEncoder nos = fixAcceptorSession.newEncoder(NewOrderSingleEncoder.class)
                .begin()
                .addString(EffectiveTime.get(), "wrong date");

        fixInitiatorSession.send(nos, null);

        await().untilAsserted(() -> verify(fixInitiatorApplication).onMessageReject(any(),
                eq("Invalid UTC datetime: wrong date"), eq(SessionRejectReasonCodes.INCORRECT_DATA_FORMAT_FOR_VALUE.getCode()), anyLong(),
                eq(EffectiveTime.get().getCode()), eq(nos.getMessageType().code())));

        verify(getDecoder(MessageTypes.NewOrderSingle, ConnectorType.ACCEPTOR)).onDecodingFailed(any(), any(RejectedMessageException.class));

    }

    @Test
    void testMultipleRejectsShouldTriggerDecodingFailureOnlyOnce() {

        logonClient();

        MarketDataRequestRejectEncoder reject = fixAcceptorSession.newEncoder(MarketDataRequestRejectEncoder.class)
                .begin()
                .addString(FixField.of(1234, FieldType.STRING, FieldLocation.BODY), "test-error-field")
                .addString(FixField.of(4321, FieldType.STRING, FieldLocation.BODY), "test-error-field");

        fixInitiatorSession.send(reject, null);

        await().untilAsserted(() -> verify(fixInitiatorApplication).onMessageReject(any(),
                eq("Invalid tag number"), eq(SessionRejectReasonCodes.INVALID_TAG_NUMBER.getCode()), anyLong(),
                eq(1234), eq(reject.getMessageType().code())));

        await().untilAsserted(() -> verify(fixInitiatorApplication).onMessageReject(any(),
                eq("Invalid tag number"), eq(SessionRejectReasonCodes.INVALID_TAG_NUMBER.getCode()), anyLong(),
                eq(4321), eq(reject.getMessageType().code())));

        verify(getDecoder(MessageTypes.MarketDataRequestReject, ConnectorType.ACCEPTOR)).onDecodingFailed(any(), any(RejectedMessageException.class));

    }

    @Test
    void testUnknownFieldInDictionarySent() {

        logonClient();

        MarketDataRequestRejectEncoder reject = fixAcceptorSession.newEncoder(MarketDataRequestRejectEncoder.class)
                .begin()
                .addString(FixField.of(1234, FieldType.STRING, FieldLocation.BODY), "test-error-field");

        fixInitiatorSession.send(reject, null);

        await().untilAsserted(() -> verify(fixInitiatorApplication).onMessageReject(any(),
                eq("Invalid tag number"), eq(SessionRejectReasonCodes.INVALID_TAG_NUMBER.getCode()), anyLong(),
                eq(1234), eq(reject.getMessageType().code())));

        verify(getDecoder(MessageTypes.MarketDataRequestReject, ConnectorType.ACCEPTOR)).onDecodingFailed(any(), any(RejectedMessageException.class));

    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testAllowUserDefinedFieldInDictionarySent(boolean allowed) {
        setupInitiatorSessionSettings(s -> s.validationSettings(
                FixSessionSettings.ValidationSettings.builder().allowUserDefinedFields(allowed).build()).build());

        logonClient();

        // the allowed test will add a field to the fields registry
        // so we must use a different tag number for each test
        int userDefinedField = allowed ? 5201 : 5202;
        EmailEncoder e = fixAcceptorSession.newEncoder(EmailEncoder.class).begin()
                .addChar(FixField.of(userDefinedField, FieldType.CHAR, FieldLocation.TRAILER), 't');

        fixAcceptorSession.send(e, null);

        if (allowed) {
            await().untilAsserted(() -> verify(getDecoder(testMessageType, ConnectorType.INITIATOR)).onDecoded(any(), eq(false), eq(false)));
            await().untilAsserted(() -> verify(getDecoder(testMessageType, ConnectorType.INITIATOR))
                    .onUnknownField(Mockito.assertArg((Consumer<FixField>) field -> {
                        assertThat(field.getCode()).isEqualTo(userDefinedField);
                        assertThat(field.getType()).isEqualTo(FieldType.UNKNOWN);
                        assertThat(field.getLocation()).isEqualTo(FieldLocation.BODY);
                    }), any(SerDe.DeserializationContext.class)));
            verify(fixInitiatorApplication, never()).onMessageReject(any(), any(), anyInt(), anyLong(), anyInt(), any());
        } else {
            await().untilAsserted(() -> verify(fixAcceptorApplication).onMessageReject(any(),
                    eq("Undefined tag"), eq(SessionRejectReasonCodes.UNDEFINED_TAG.getCode()), anyLong(),
                    eq(userDefinedField), eq(testMessageType.code())));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testAllowUnknownFieldInDictionarySent(boolean allowed) {
        setupInitiatorSessionSettings(s -> s.validationSettings(
                FixSessionSettings.ValidationSettings.builder().allowUnknownFields(allowed).build()).build());

        logonClient();

        EmailEncoder e = fixAcceptorSession.newEncoder(EmailEncoder.class).begin()
                .addChar(FixField.of(1234, FieldType.STRING, FieldLocation.TRAILER), 't');

        fixAcceptorSession.send(e, null);

        if (allowed) {
            await().untilAsserted(() -> verify(getDecoder(testMessageType, ConnectorType.INITIATOR)).onDecoded(any(), eq(false), eq(false)));
            await().untilAsserted(() -> verify(getDecoder(testMessageType, ConnectorType.INITIATOR))
                    .onUnknownField(Mockito.assertArg((Consumer<FixField>) field -> {
                        assertThat(field.getCode()).isEqualTo(1234);
                        assertThat(field.getType()).isEqualTo(FieldType.UNKNOWN);
                        assertThat(field.getLocation()).isEqualTo(FieldLocation.BODY);
                    }), any(SerDe.DeserializationContext.class)));
            verify(fixInitiatorApplication, never()).onMessageReject(any(), any(), anyInt(), anyLong(), anyInt(), any());
        } else {
            await().untilAsserted(() -> verify(fixAcceptorApplication).onMessageReject(any(),
                    eq("Invalid tag number"), eq(SessionRejectReasonCodes.INVALID_TAG_NUMBER.getCode()), anyLong(),
                    eq(1234), eq(testMessageType.code())));
        }
    }

    @Test
    void testRejectSentByApplicationWhenWrongEnumValueSent() {

        logonClient();

        MarketDataRequestRejectEncoder reject = fixAcceptorSession.newEncoder(MarketDataRequestRejectEncoder.class)
                .begin()
                .addChar(MDReqRejReason.get(), 'E');

        fixInitiatorSession.send(reject, null);

        await().untilAsserted(() -> verify(fixInitiatorApplication).onMessageReject(any(),
                eq("Unable to find a MDReqRejReason enum value mapping for: E"),
                eq(SessionRejectReasonCodes.VALUE_IS_INCORRECT.getCode()), anyLong(),
                eq(MDReqRejReason.get().getCode()), eq(reject.getMessageType().code())));

        verify(getDecoder(MessageTypes.MarketDataRequestReject, ConnectorType.ACCEPTOR)).onDecodingFailed(any(), any(RejectedMessageException.class));

    }

    @Setter
    private static class TestMarketDataRequestRejectDecoder implements FixMessageDecoder {

        org.lolaf.staffix.fix44.fields.MDReqRejReason.MDReqRejReasonValues rejectVal;

        @Override
        public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
            fixFieldsDecoderMapper.mapCharValuesEnumField(MDReqRejReason.get(), this::setRejectVal, null);
        }

        @Override
        public MessageType getMessageType() {
            return MessageTypes.MarketDataRequestReject;
        }

    }

    @Setter
    private static class TestNewOrderSingleDecoder implements FixMessageDecoder {

        double spread;
        UTCTime effectiveTime;

        @Override
        public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
            fixFieldsDecoderMapper.mapDoubleField(Spread.get(), this::setSpread, 0d)
                    .mapUtcDateTimeField(EffectiveTime.get(), this::setEffectiveTime, null);
        }

        @Override
        public MessageType getMessageType() {
            return MessageTypes.NewOrderSingle;
        }

    }

    private static class TestRequestEncoder extends FixMessageEncoderImpl<TestRequestEncoder> {

        public TestRequestEncoder() {
            super(null, MessageType.of(CoreMessageType.TEST_REQUEST, true), null, null, null);
        }
    }

    private static class UnknownMessageEncoder extends FixMessageEncoderImpl<UnknownMessageEncoder> {

        public UnknownMessageEncoder() {
            super(null, MessageType.of("UNKNOWN", false), null, null, null);
        }
    }
}