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
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.codec.*;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.MessageFieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.tests.fix44.fields.*;
import org.lolaf.staffix.tests.fix44.msg.MessageTypes;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TestFixMessageDecoderImpl {

    MessageType messageType;
    FixDictionaryId fixDictionaryId;
    FixMessageDecoderImpl fixMessageDecoderImpl;
    FixMessageDecoder fixMessageDecoder;
    SerDe.DeserializationContext deserializationContext;

    @BeforeEach
    void setup() {
        fixDictionaryId = FixDictionaryId.of("tests", FixRegularVersion.VERSION_44);
        fixMessageDecoder = mock(FixMessageDecoder.class);
        deserializationContext = mock(SerDe.DeserializationContext.class);
        messageType = MessageTypes.MarketDataSnapshotFullRefresh;
        when(fixMessageDecoder.getMessageType()).thenReturn(messageType);
        fixMessageDecoderImpl = new FixMessageDecoderImpl(mock(FieldsRegistry.class), fixMessageDecoder, fixDictionaryId,
                FixSessionSettings.ValidationSettings.builder().build(), mock(FixSessionId.class));
    }

    @Test
    void testMissingFieldsAreValidated() {
        try {
            fixMessageDecoderImpl.validate();
        } catch (ValidationException ex) {
            fail();
        }
        fixMessageDecoderImpl = new FixMessageDecoderImpl(mock(FieldsRegistry.class), fixMessageDecoder, fixDictionaryId,
                FixSessionSettings.ValidationSettings.builder().validateRequiredFields(true).build(), mock(FixSessionId.class));
        fixMessageDecoderImpl.onBegin(0, null);
        fixMessageDecoderImpl.onGroupStart(null, NoMDEntries.get(), 1);
        fixMessageDecoderImpl.onGroupEntryStart(null, NoMDEntries.get(), MDEntryPx.get());
        fixMessageDecoderImpl.onField(MDEntryPx.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupEntryEnd(null, NoMDEntries.get(), MDEntryPx.get());
        fixMessageDecoderImpl.onGroupEnd(null, NoMDEntries.get());
        try {
            fixMessageDecoderImpl.validate();
            fail();
        } catch (ValidationException ex) {
            assertThat(ex.getValidationExceptions()).hasSize(2);
            assertThat(ex.getValidationExceptions().get(0)).isInstanceOf(RequiredGroupFieldNotFoundException.class);
            assertThat(ex.getValidationExceptions().get(0)).extracting(Throwable::getMessage).isEqualTo("Group field not found in message");
            assertThat(ex.getValidationExceptions().get(0))
                    .extracting(e -> RequiredGroupFieldNotFoundException.class.cast(e).getField())
                    .isEqualTo(MDEntryType.get());
            assertThat(ex.getValidationExceptions().get(1)).isInstanceOf(RequiredFieldNotFoundException.class);
            assertThat(ex.getValidationExceptions().get(1)).extracting(Throwable::getMessage).isEqualTo("Field not found in message");
            assertThat(ex.getValidationExceptions().get(1))
                    .extracting(e -> RequiredFieldNotFoundException.class.cast(e).getField())
                    .isEqualTo(Symbol.get());
        }
    }

    @Test
    void testUnAllowedFixTagsForMessagesAreRejected() {
        fixMessageDecoderImpl = new FixMessageDecoderImpl(mock(FieldsRegistry.class), fixMessageDecoder, fixDictionaryId,
                FixSessionSettings.ValidationSettings.builder()
                        .allowUndefinedTagsForMessage(false)
                        .build(), mock(FixSessionId.class));

        fixMessageDecoderImpl.onBegin(0, null);
        fixMessageDecoderImpl.onField(MarketDepth.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupStart(null, NoMDEntries.get(), 1);
        fixMessageDecoderImpl.onField(EmailThreadID.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupEnd(null, NoMDEntries.get());
        try {
            fixMessageDecoderImpl.validate();
            fail();
        } catch (ValidationException ex) {
            assertThat(ex.getValidationExceptions()).hasSize(2);
            assertThat(ex.getValidationExceptions().get(0)).isInstanceOf(ValidationException.class);
            assertThat(ex.getValidationExceptions().get(0))
                    .extracting(e -> ValidationException.class.cast(e).getField())
                    .isEqualTo(MarketDepth.get());
            assertThat(ex.getValidationExceptions().get(0)).extracting(Throwable::getMessage).isEqualTo("Received tag not defined for message");
            assertThat(ex.getValidationExceptions().get(1)).isInstanceOf(ValidationException.class);
            assertThat(ex.getValidationExceptions().get(1)).extracting(Throwable::getMessage).isEqualTo("Received group tag not defined for message");
            assertThat(ex.getValidationExceptions().get(1))
                    .extracting(e -> ValidationException.class.cast(e).getField())
                    .isEqualTo(EmailThreadID.get());
        }
    }

    /**
     * A group's own NumInGroup field, delivered the way the parser delivers it: it enters the group first and hands the
     * counter over afterwards, so the counter arrives while the scope is already the group's members - and a group is
     * never a member of itself. Checked against the message it is declared in instead, or every message carrying a
     * repeating group is rejected as soon as undefined tags are refused.
     */
    @Test
    void testGroupCounterFieldIsCheckedAgainstTheMessageRatherThanItsOwnGroup() throws ValidationException {
        fixMessageDecoderImpl = new FixMessageDecoderImpl(mock(FieldsRegistry.class), fixMessageDecoder, fixDictionaryId,
                FixSessionSettings.ValidationSettings.builder()
                        .allowUndefinedTagsForMessage(false)
                        .build(), mock(FixSessionId.class));

        fixMessageDecoderImpl.onBegin(0, null);
        fixMessageDecoderImpl.onField(Symbol.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupStart(null, NoMDEntries.get(), 1);
        fixMessageDecoderImpl.onField(NoMDEntries.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupEntryStart(null, NoMDEntries.get(), MDEntryType.get());
        fixMessageDecoderImpl.onField(MDEntryType.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupEntryEnd(null, NoMDEntries.get(), MDEntryType.get());
        fixMessageDecoderImpl.onGroupEnd(null, NoMDEntries.get());

        fixMessageDecoderImpl.validate();
    }

    @Test
    void testValidationPassesWhenAllRequiredFieldsArePresent() {
        fixMessageDecoderImpl = new FixMessageDecoderImpl(mock(FieldsRegistry.class), fixMessageDecoder, fixDictionaryId,
                FixSessionSettings.ValidationSettings.builder()
                        .validateRequiredFields(true)
                        .allowUndefinedTagsForMessage(false)
                        .build(), mock(FixSessionId.class));

        fixMessageDecoderImpl.onBegin(0, null);
        fixMessageDecoderImpl.onField(Symbol.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupStart(null, NoMDEntries.get(), 1);
        fixMessageDecoderImpl.onGroupEntryStart(null, NoMDEntries.get(), MDEntryType.get());
        fixMessageDecoderImpl.onField(MDEntryType.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupEntryEnd(null, NoMDEntries.get(), MDEntryType.get());
        fixMessageDecoderImpl.onGroupEnd(null, NoMDEntries.get());

        assertThatCode(() -> fixMessageDecoderImpl.validate()).doesNotThrowAnyException();
    }

    @Test
    void testValidateDelegatesToWrappedDecoder() throws ValidationException {
        fixMessageDecoderImpl.validate();
        verify(fixMessageDecoder).validate();
    }

    @Test
    void testWrappedDecoderValidationExceptionPropagates() throws ValidationException {
        ValidationException expected = new ValidationException(Symbol.get(), "wrapped failure");
        doThrow(expected).when(fixMessageDecoder).validate();

        assertThatThrownBy(() -> fixMessageDecoderImpl.validate()).isSameAs(expected);
    }

    @Test
    void testMapFieldsForDecodingThrows() {
        assertThatThrownBy(() -> fixMessageDecoderImpl.mapFieldsForDecoding(mock(FixFieldsDecoderMapper.class), mock(FieldsRegistry.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Should not have been called");
    }

    @Test
    void testGetMessageTypeReturnsWrappedDecoderType() {
        assertThat(fixMessageDecoderImpl.getMessageType()).isEqualTo(messageType);
    }

    @Test
    void testConstructorThrowsWhenMessageTypeNotInRegistry() {
        MessageFieldsRegistry registry = mock(MessageFieldsRegistry.class);
        when(registry.getFixMessageFields(messageType)).thenReturn(null);
        when(registry.getTargetDictionary()).thenReturn(fixDictionaryId);

        assertThatThrownBy(() -> new FixMessageDecoderImpl(mock(FieldsRegistry.class), fixMessageDecoder, registry,
                FixSessionSettings.ValidationSettings.builder().build(), mock(FixSessionId.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to find any FIX fields in registry for message type");
    }

    @Test
    void testEventsArePropagatedToWrappedDecoder() {
        FixSession fixSession = mock(FixSession.class);
        DecodingException decodingException = new DecodingException("testing exception");

        fixMessageDecoderImpl.onBegin(42L, null);
        fixMessageDecoderImpl.onField(Symbol.get(), deserializationContext);
        fixMessageDecoderImpl.onUnknownField(Symbol.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupStart(null, NoMDEntries.get(), 1);
        fixMessageDecoderImpl.onGroupEntryStart(null, NoMDEntries.get(), MDEntryType.get());
        fixMessageDecoderImpl.onField(MDEntryType.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupEntryEnd(null, NoMDEntries.get(), MDEntryType.get());
        fixMessageDecoderImpl.onGroupEnd(null, NoMDEntries.get());
        fixMessageDecoderImpl.onDecoded(fixSession, true, true);
        fixMessageDecoderImpl.onDecodingFailed(fixSession, decodingException);

        verify(fixMessageDecoder).onBegin(42L, null);
        verify(fixMessageDecoder).onField(eq(Symbol.get()), any());
        verify(fixMessageDecoder).onUnknownField(eq(Symbol.get()), any());
        verify(fixMessageDecoder).onGroupStart(null, NoMDEntries.get(), 1);
        verify(fixMessageDecoder).onGroupEntryStart(null, NoMDEntries.get(), MDEntryType.get());
        verify(fixMessageDecoder).onField(eq(MDEntryType.get()), any());
        verify(fixMessageDecoder).onGroupEntryEnd(null, NoMDEntries.get(), MDEntryType.get());
        verify(fixMessageDecoder).onGroupEnd(null, NoMDEntries.get());
        verify(fixMessageDecoder).onDecoded(fixSession, true, true);
        verify(fixMessageDecoder).onDecodingFailed(fixSession, decodingException);
    }

    @Test
    void testNoTrackingWhenAllValidationDisabled() throws ValidationException {
        fixMessageDecoderImpl = new FixMessageDecoderImpl(mock(FieldsRegistry.class), fixMessageDecoder, fixDictionaryId,
                FixSessionSettings.ValidationSettings.builder()
                        .validateRequiredFields(false)
                        .allowUndefinedTagsForMessage(true)
                        .build(), mock(FixSessionId.class));

        fixMessageDecoderImpl.onBegin(0, null);
        fixMessageDecoderImpl.onField(MarketDepth.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupStart(null, NoMDEntries.get(), 1);
        fixMessageDecoderImpl.onField(EmailThreadID.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupEnd(null, NoMDEntries.get());

        assertThatCode(() -> fixMessageDecoderImpl.validate()).doesNotThrowAnyException();
        verify(fixMessageDecoder).validate();
    }

    @Test
    void testAllowedFieldsForMessageDoNotTriggerExceptions() {
        fixMessageDecoderImpl = new FixMessageDecoderImpl(mock(FieldsRegistry.class), fixMessageDecoder, fixDictionaryId,
                FixSessionSettings.ValidationSettings.builder()
                        .allowUndefinedTagsForMessage(false)
                        .build(), mock(FixSessionId.class));

        fixMessageDecoderImpl.onBegin(0, null);
        fixMessageDecoderImpl.onField(MDReqID.get(), deserializationContext);
        fixMessageDecoderImpl.onField(Symbol.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupStart(null, NoMDEntries.get(), 1);
        fixMessageDecoderImpl.onGroupEntryStart(null, NoMDEntries.get(), MDEntryType.get());
        fixMessageDecoderImpl.onField(MDEntryType.get(), deserializationContext);
        fixMessageDecoderImpl.onField(MDEntryPx.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupEntryEnd(null, NoMDEntries.get(), MDEntryType.get());
        fixMessageDecoderImpl.onGroupEnd(null, NoMDEntries.get());

        assertThatCode(() -> fixMessageDecoderImpl.validate()).doesNotThrowAnyException();
    }

    @Test
    void testValidateClearsStateAfterException() {
        fixMessageDecoderImpl = new FixMessageDecoderImpl(mock(FieldsRegistry.class), fixMessageDecoder, fixDictionaryId,
                FixSessionSettings.ValidationSettings.builder()
                        .validateRequiredFields(true)
                        .allowUndefinedTagsForMessage(false)
                        .build(), mock(FixSessionId.class));

        fixMessageDecoderImpl.onBegin(0, null);
        try {
            fixMessageDecoderImpl.validate();
            fail();
        } catch (ValidationException ex) {
            assertThat(ex.getValidationExceptions()).isNotEmpty();
        }

        fixMessageDecoderImpl.onBegin(0, null);
        fixMessageDecoderImpl.onField(Symbol.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupStart(null, NoMDEntries.get(), 1);
        fixMessageDecoderImpl.onGroupEntryStart(null, NoMDEntries.get(), MDEntryType.get());
        fixMessageDecoderImpl.onField(MDEntryType.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupEntryEnd(null, NoMDEntries.get(), MDEntryType.get());
        fixMessageDecoderImpl.onGroupEnd(null, NoMDEntries.get());

        assertThatCode(() -> fixMessageDecoderImpl.validate()).doesNotThrowAnyException();
    }

    @Test
    void testValidateDoesNotInvokeWrappedDecoderWhenValidationFails() throws ValidationException {
        fixMessageDecoderImpl = new FixMessageDecoderImpl(mock(FieldsRegistry.class), fixMessageDecoder, fixDictionaryId,
                FixSessionSettings.ValidationSettings.builder()
                        .validateRequiredFields(true)
                        .build(), mock(FixSessionId.class));

        fixMessageDecoderImpl.onBegin(0, null);

        assertThatThrownBy(() -> fixMessageDecoderImpl.validate()).isInstanceOf(ValidationException.class);
        verify(fixMessageDecoder, never()).validate();
    }

    @Test
    void testDuplicateBodyTagRejected() {
        fixMessageDecoderImpl = new FixMessageDecoderImpl(mock(FieldsRegistry.class), fixMessageDecoder, fixDictionaryId,
                FixSessionSettings.ValidationSettings.builder()
                        .validateDuplicateTags(true)
                        .build(), mock(FixSessionId.class));

        fixMessageDecoderImpl.onBegin(0, null);
        fixMessageDecoderImpl.onField(Symbol.get(), deserializationContext);
        fixMessageDecoderImpl.onField(Symbol.get(), deserializationContext);
        try {
            fixMessageDecoderImpl.validate();
            fail();
        } catch (ValidationException ex) {
            assertThat(ex.getValidationExceptions()).hasSize(1);
            assertThat(ex.getValidationExceptions().get(0)).extracting(Throwable::getMessage).isEqualTo("Tag appears more than once");
            assertThat(ex.getValidationExceptions().get(0))
                    .extracting(ValidationException::getSessionRejectReasonCode)
                    .isEqualTo(SessionRejectReasonCodes.TAG_APPEARS_MORE_THAN_ONCE);
            assertThat(ex.getValidationExceptions().get(0))
                    .extracting(ValidationException::getField)
                    .isEqualTo(Symbol.get());
        }
    }

    @Test
    void testDuplicateGroupTagWithinEntryRejected() {
        fixMessageDecoderImpl = new FixMessageDecoderImpl(mock(FieldsRegistry.class), fixMessageDecoder, fixDictionaryId,
                FixSessionSettings.ValidationSettings.builder()
                        .validateDuplicateTags(true)
                        .build(), mock(FixSessionId.class));

        fixMessageDecoderImpl.onBegin(0, null);
        fixMessageDecoderImpl.onGroupStart(null, NoMDEntries.get(), 1);
        fixMessageDecoderImpl.onGroupEntryStart(null, NoMDEntries.get(), MDEntryType.get());
        fixMessageDecoderImpl.onField(MDEntryType.get(), deserializationContext);
        fixMessageDecoderImpl.onField(MDEntryType.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupEntryEnd(null, NoMDEntries.get(), MDEntryType.get());
        fixMessageDecoderImpl.onGroupEnd(null, NoMDEntries.get());
        try {
            fixMessageDecoderImpl.validate();
            fail();
        } catch (ValidationException ex) {
            assertThat(ex.getValidationExceptions()).hasSize(1);
            assertThat(ex.getValidationExceptions().get(0))
                    .extracting(ValidationException::getSessionRejectReasonCode)
                    .isEqualTo(SessionRejectReasonCodes.TAG_APPEARS_MORE_THAN_ONCE);
            assertThat(ex.getValidationExceptions().get(0))
                    .extracting(ValidationException::getField)
                    .isEqualTo(MDEntryType.get());
        }
    }

    @Test
    void testRepeatedGroupTagAcrossEntriesNotRejected() {
        fixMessageDecoderImpl = new FixMessageDecoderImpl(mock(FieldsRegistry.class), fixMessageDecoder, fixDictionaryId,
                FixSessionSettings.ValidationSettings.builder()
                        .validateDuplicateTags(true)
                        .build(), mock(FixSessionId.class));

        fixMessageDecoderImpl.onBegin(0, null);
        fixMessageDecoderImpl.onGroupStart(null, NoMDEntries.get(), 2);
        fixMessageDecoderImpl.onGroupEntryStart(null, NoMDEntries.get(), MDEntryType.get());
        fixMessageDecoderImpl.onField(MDEntryType.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupEntryEnd(null, NoMDEntries.get(), MDEntryType.get());
        fixMessageDecoderImpl.onGroupEntryStart(null, NoMDEntries.get(), MDEntryType.get());
        fixMessageDecoderImpl.onField(MDEntryType.get(), deserializationContext);
        fixMessageDecoderImpl.onGroupEntryEnd(null, NoMDEntries.get(), MDEntryType.get());
        fixMessageDecoderImpl.onGroupEnd(null, NoMDEntries.get());

        assertThatCode(() -> fixMessageDecoderImpl.validate()).doesNotThrowAnyException();
    }

    @Test
    void testDuplicateTagsToleratedWhenDisabled() {
        fixMessageDecoderImpl = new FixMessageDecoderImpl(mock(FieldsRegistry.class), fixMessageDecoder, fixDictionaryId,
                FixSessionSettings.ValidationSettings.builder()
                        .validateDuplicateTags(false)
                        .build(), mock(FixSessionId.class));

        fixMessageDecoderImpl.onBegin(0, null);
        fixMessageDecoderImpl.onField(Symbol.get(), deserializationContext);
        fixMessageDecoderImpl.onField(Symbol.get(), deserializationContext);

        assertThatCode(() -> fixMessageDecoderImpl.validate()).doesNotThrowAnyException();
    }
}
