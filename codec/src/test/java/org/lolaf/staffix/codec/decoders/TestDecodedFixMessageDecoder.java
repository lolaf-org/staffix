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

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.TestingClock;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.DecodingException;
import org.lolaf.staffix.api.codec.FixMessageEncoderFactory;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.FixFieldMap;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.tests.fix44.encoders.QuoteCancelEncoder;
import org.lolaf.staffix.tests.fix44.encoders.group.AdvertisementNoUnderlyingsEncoder;
import org.lolaf.staffix.tests.fix44.encoders.group.NoEventsEncoder;
import org.lolaf.staffix.tests.fix44.encoders.group.NoUnderlyingSecurityAltIDEncoder;
import org.lolaf.staffix.tests.fix44.encoders.group.QuoteCancelNoQuoteEntriesEncoder;
import org.lolaf.staffix.tests.fix44.fields.*;
import org.mockito.Mockito;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;

class TestDecodedFixMessageDecoder {

    FixDictionaryId fixDictionaryId;
    FixMessageParser fixMessageParser;
    FixSessionId fixSessionId;

    @BeforeEach
    void before() {
        fixSessionId = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER_TEST", "TARGET_TEST");
        fixDictionaryId = FixDictionaryId.of("tests", FixRegularVersion.VERSION_44);
        FixSession fixSession = mock(FixSession.class);
        Mockito.when(fixSession.getFixSessionId()).thenReturn(fixSessionId);
        MessageTypeRegistry messageTypeRegistry = MessageTypeRegistry.Registry.getInstance(fixDictionaryId);
        FieldsRegistry fieldsRegistry = FieldsRegistry.Registry.getInstance(fixDictionaryId);

        FixSessionSettings.ValidationSettings validationSettings = FixSessionSettings.ValidationSettings.builder()
                .validateChecksum(true)
                .validateFieldsHaveValues(true)
                .validateCompId(false)
                .maxSendingTime(null)
                .build();

        fixMessageParser = new FixMessageParser(fixSessionId, messageTypeRegistry, fieldsRegistry,
                new TestingLogger().getLogger("test", fixSessionId, messageTypeRegistry), validationSettings,
                TestingClock.get(), mock(FixMessageParserEventsListener.class));
    }

    @Test
    void testMultipleGroupEntriesAtSameLevel() throws DecodingException {
        String msg = "8=FIX.4.4\u00019=192\u000135=C\u000134=6\u000149=SENDER44_TEST\u000156=TARGET44_TEST\u000152=20241027-21:48:15.516767\u000194=0\u0001" +
                "164=test thread id\u0001147=test subject\u000133=2\u000158=test text\u0001354=12\u0001355=encoded text\u000158=test text\u0001354=12\u0001355=encoded text\u000110=020\u0001";

        ByteBuffer encoded = ByteBuffer.wrap(msg.getBytes());

        DecodedFixMessageDecoder decoder = new DecodedFixMessageDecoder(MessageTypeRegistry.Registry.getInstance(fixDictionaryId).find("C"));

        fixMessageParser.parseMessages(encoded, msgType -> decoder);

        String expected = "35=C\u000134=6\u000149=SENDER44_TEST\u000156=TARGET44_TEST\u000152=20241027-21:48:15.516767\u000194=0\u0001164=test thread id" +
                "\u0001147=test subject\u000133=2\u000158=test text\u0001354=12\u0001355=encoded text\u000158=test text\u0001354=12\u0001355=encoded text\u0001";

        Assertions.assertThat(decoder.getDecodedFixMessage()).hasToString(expected);
    }

    @Test
    void testDecoderWithMultipleNestedGroups() throws DecodingException {
        QuoteCancelEncoder encoder = FixMessageEncoderFactory.Registry.getInstance(fixDictionaryId)
                .newInstance(QuoteCancelEncoder.class, null, null, null, null);
        encoder.begin();
        encoder.setQuoteID("testQuoteId");
        encoder.setQuoteCancelType(QuoteCancelType.QuoteCancelTypeValues.CANCEL_ALL_QUOTES);
        QuoteCancelNoQuoteEntriesEncoder noQuoteEntriesEncoder = encoder.addNoQuoteEntries(2);
        for (int i = 0; i < 2; i++) {
            noQuoteEntriesEncoder.setSymbol("test symbol " + i);
            noQuoteEntriesEncoder.setAgreementCurrency("test currency " + i);
            AdvertisementNoUnderlyingsEncoder noUnderLying = encoder.addNoUnderlyings(2);
            for (int j = 0; j < 2; j++) {
                noUnderLying.setUnderlyingSymbol("test usymbol " + j);
                noUnderLying.setEncodedUnderlyingIssuerLen(("test uissuer " + j).length());
                noUnderLying.setEncodedUnderlyingIssuer("test uissuer " + j);
                NoUnderlyingSecurityAltIDEncoder altIdEncoder = encoder.addNoUnderlyingSecurityAltID(3);
                for (int k = 0; k < 3; k++) {
                    altIdEncoder.setUnderlyingSecurityAltID("test AltId " + k);
                    altIdEncoder.setUnderlyingSecurityAltIDSource("test AltId Source" + k);
                }
                noUnderLying.setUnderlyingCFICode("testCfi code " + j);
            }
        }
        encoder.setAcctIDSource(AcctIDSource.AcctIDSourceValues.BIC);
        NoEventsEncoder noEventsEncoder = encoder.addNoEvents(2);
        for (int i = 0; i < 2; i++) {
            noEventsEncoder.setEventPx(1.1d);
            noEventsEncoder.setEventType(EventType.EventTypeValues.PUT);
            noEventsEncoder.setEventText("event ext");
        }
        encoder.setQuoteReqID("testQuoteRequestId");

        ByteBuffer encoded = encoder.encode(ByteBuffer::allocate, 1, fixSessionId, mock(FixApplication.class), TimeUnit.MICROSECONDS, TestingClock.get().now(), null);

        DecodedFixMessageDecoder decoder = new DecodedFixMessageDecoder(encoder.getMessageType());

        fixMessageParser.parseMessages(encoded.flip(), msgType -> decoder);

        DecodedFixMessage decodedFixMessage = decoder.getDecodedFixMessage();
        FixFieldMap.GroupFixFieldMap noQuoteEntries = decodedFixMessage.getGroup(NoQuoteEntries.get());
        Assertions.assertThat(noQuoteEntries).isNotNull();
        Assertions.assertThat(noQuoteEntries.getEntries()).hasSize(2);
        noQuoteEntries.getEntries().forEach(e -> {
            Assertions.assertThat(e.getString(AgreementCurrency.get(), null)).startsWith("test currency ");

            FixFieldMap.GroupFixFieldMap noUnderlyings = e.getGroup(NoUnderlyings.get());
            Assertions.assertThat(noUnderlyings).isNotNull();
            Assertions.assertThat(noUnderlyings.getEntries()).hasSize(2);
            noUnderlyings.getEntries().forEach(e2 -> {
                Assertions.assertThat(e2.getString(UnderlyingSymbol.get(), null)).startsWith("test usymbol ");
                FixFieldMap.GroupFixFieldMap noUnderlyingSecurityAltID = e2.getGroup(NoUnderlyingSecurityAltID.get());
                Assertions.assertThat(noUnderlyingSecurityAltID).isNotNull();
                Assertions.assertThat(noUnderlyingSecurityAltID.getEntries()).hasSize(3);
            });
        });

        FixFieldMap.GroupFixFieldMap noEvents = decodedFixMessage.getGroup(NoEvents.get());
        Assertions.assertThat(noEvents).isNotNull();
        Assertions.assertThat(noEvents.getEntries()).hasSize(2);
        Assertions.assertThat(decodedFixMessage.getString(QuoteReqID.get(), null)).isEqualTo("testQuoteRequestId");

        String expectedForEachOutput = "35=Z\u000134=1\u000149=SENDER_TEST\u000156=TARGET_TEST\u000152=19700101-00:00:00.000000\u0001117=testQuoteId\u0001298=4" +
                "\u0001295=2\u000155=test symbol 0\u0001918=test currency 0\u0001711=2\u0001311=test usymbol 0\u0001362=14\u0001363=test uissuer 0\u0001457=3" +
                "\u0001458=test AltId 0\u0001459=test AltId Source0\u0001458=test AltId 1\u0001459=test AltId Source1\u0001458=test AltId 2\u0001459=test AltId Source2" +
                "\u0001311=test usymbol 1\u0001362=14\u0001363=test uissuer 1\u0001457=3\u0001458=test AltId 0\u0001459=test AltId Source0\u0001458=test AltId 1" +
                "\u0001459=test AltId Source1\u0001458=test AltId 2\u0001459=test AltId Source2\u0001463=testCfi code 1\u000155=test symbol 1\u0001918=test currency 1" +
                "\u0001711=2\u0001311=test usymbol 0\u0001362=14\u0001363=test uissuer 0\u0001457=3\u0001458=test AltId 0\u0001459=test AltId Source0\u0001458=test AltId 1" +
                "\u0001459=test AltId Source1\u0001458=test AltId 2\u0001459=test AltId Source2\u0001311=test usymbol 1\u0001362=14\u0001363=test uissuer 1\u0001457=3" +
                "\u0001458=test AltId 0\u0001459=test AltId Source0\u0001458=test AltId 1\u0001459=test AltId Source1\u0001458=test AltId 2\u0001459=test AltId Source2" +
                "\u0001463=testCfi code 1\u0001660=1\u0001864=2\u0001867=1.1\u0001865=1\u0001868=event ext\u0001867=1.1\u0001865=1\u0001868=event ext\u0001131=testQuoteRequestId\u0001";

        Assertions.assertThat(decoder.getDecodedFixMessage()).hasToString(expectedForEachOutput);
    }
}