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
package org.lolaf.staffix.codec.decoders.mappers;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper.ObjectInstanceStrategy;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.MessageFieldsRegistry;
import org.lolaf.staffix.api.serde.DecimalFloat;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.codec.decoders.DeserializationContextImpl;
import org.lolaf.staffix.tests.fix44.fields.*;
import org.lolaf.staffix.tests.fix44.msg.MessageTypes;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;

import static org.mockito.Mockito.*;

class TestFixFieldsDecoderMapperImpl {

    FixFieldsDecoderMapperImpl fixFieldsDecoderMapperImpl;
    BiConsumer<FixField, SerDe.DeserializationContext> unknownFieldConsumer;

    @BeforeEach
    void setup() {
        FixDictionaryId fixDictionaryId = FixDictionaryId.of("tests", FixRegularVersion.VERSION_44);
        MessageFieldsRegistry.FixMessageFields fixMessageFields = MessageFieldsRegistry.Registry.getInstance(fixDictionaryId)
                .getFixMessageFields(MessageTypes.NewOrderSingle);
        unknownFieldConsumer = mock(BiConsumer.class);
        fixFieldsDecoderMapperImpl = new FixFieldsDecoderMapperImpl(fixMessageFields, mock(FixSessionId.class), unknownFieldConsumer);
        fixFieldsDecoderMapperImpl.onBegin();
    }

    @Test
    void testUnknownFieldProcessed() {
        AtomicReference<SettlDeliveryType.SettlDeliveryTypeValues> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapIntValuesEnumField(SettlDeliveryType.get(), setter::set, null);

        FixField unknowField = FixField.of(1, FieldType.UNKNOWN, FieldLocation.BODY);
        SerDe.DeserializationContext ctx = getDeserializationCtx("1");

        fixFieldsDecoderMapperImpl.onField(unknowField, ctx);

        verify(unknownFieldConsumer).accept(unknowField, ctx);
    }


    @Test
    void testDecodeIntValuesEnum() {
        AtomicReference<SettlDeliveryType.SettlDeliveryTypeValues> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapIntValuesEnumField(SettlDeliveryType.get(), setter::set, null);

        fixFieldsDecoderMapperImpl.onField(SettlDeliveryType.get(), getDeserializationCtx("1"));
        Assertions.assertThat(setter.get()).isEqualTo(SettlDeliveryType.SettlDeliveryTypeValues.FREE);
    }

    @Test
    void testDecodeIntValuesEnumWithAnotherEnumThanTheOneRegistered() {
        AtomicReference<SettlDeliveryType.SettlDeliveryTypeValues> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapIntValuesEnumField(SettlDeliveryType.get(), setter::set, null);

        fixFieldsDecoderMapperImpl.onField(AccountType.get(), getDeserializationCtx("1"));
        Assertions.assertThat(setter.get()).isNull();
    }

    @Test
    void testDecodeIntValuesEnumWithWrongValue() {
        AtomicReference<SettlDeliveryType.SettlDeliveryTypeValues> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapIntValuesEnumField(SettlDeliveryType.get(), setter::set, null);
        try {
            fixFieldsDecoderMapperImpl.onField(SettlDeliveryType.get(), getDeserializationCtx("999"));
            Assertions.fail("should have failed");
        } catch (IllegalFieldValueException ex) {
            Assertions.assertThat(ex.getMessage()).isEqualTo("Unable to find a SettlDeliveryType enum value mapping for: 999");
        }
    }

    @Test
    void testDecodeIntValuesEnumWithMapperFunction() {
        AtomicReference<String> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapIntValuesEnumField(SettlDeliveryType.get(), setter::set,
                (SettlDeliveryType.SettlDeliveryTypeValues v) -> v == null ? null : v.name(), null);

        fixFieldsDecoderMapperImpl.onField(SettlDeliveryType.get(), getDeserializationCtx("1"));
        Assertions.assertThat(setter.get()).isEqualTo(SettlDeliveryType.SettlDeliveryTypeValues.FREE.name());
    }

    @Test
    void testDecodeStringValuesEnum() {
        AtomicReference<SecurityType.SecurityTypeValues> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapStringValuesEnumField(SecurityType.get(), setter::set, null);

        fixFieldsDecoderMapperImpl.onField(SecurityType.get(), getDeserializationCtx("ABS"));
        Assertions.assertThat(setter.get()).isEqualTo(SecurityType.SecurityTypeValues.ASSET_BACKED_SECURITIES);
    }

    @Test
    void testDecodeStringValuesEnumWithAnotherEnumThanTheOneRegistered() {
        AtomicReference<SecurityType.SecurityTypeValues> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapStringValuesEnumField(SecurityType.get(), setter::set, null);

        fixFieldsDecoderMapperImpl.onField(AdvTransType.get(), getDeserializationCtx("N"));
        Assertions.assertThat(setter.get()).isNull();
    }

    @Test
    void testDecodeStringValuesEnumWithWrongValue() {
        AtomicReference<SecurityType.SecurityTypeValues> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapStringValuesEnumField(SecurityType.get(), setter::set, null);
        try {
            fixFieldsDecoderMapperImpl.onField(SecurityType.get(), getDeserializationCtx("error"));
            Assertions.fail("should have failed");
        } catch (IllegalFieldValueException ex) {
            Assertions.assertThat(ex.getMessage()).isEqualTo("Unable to find a SecurityType enum value mapping for: error");
        }
    }

    @Test
    void testDecodeStringValuesEnumWithMapperFunction() {
        AtomicReference<String> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapStringValuesEnumField(SecurityType.get(), setter::set,
                (SecurityType.SecurityTypeValues v) -> v == null ? null : v.code(), null);

        fixFieldsDecoderMapperImpl.onField(SecurityType.get(), getDeserializationCtx("ABS"));
        Assertions.assertThat(setter.get()).isEqualTo("ABS");
    }

    @Test
    void testDecodeCharValuesEnum() {
        AtomicReference<SettlInstTransType.SettlInstTransTypeValues> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapCharValuesEnumField(SettlInstTransType.get(), setter::set, null);

        fixFieldsDecoderMapperImpl.onField(SettlInstTransType.get(), getDeserializationCtx("N"));
        Assertions.assertThat(setter.get()).isEqualTo(SettlInstTransType.SettlInstTransTypeValues.NEW);
    }

    @Test
    void testDecodeCharValuesEnumWithAnotherEnumThanTheOneRegistered() {
        AtomicReference<SettlInstTransType.SettlInstTransTypeValues> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapCharValuesEnumField(SettlInstTransType.get(), setter::set, null);

        fixFieldsDecoderMapperImpl.onField(AdvSide.get(), getDeserializationCtx("B"));
        Assertions.assertThat(setter.get()).isNull();
    }

    @Test
    void testDecodeCharValuesEnumWithWrongValue() {
        AtomicReference<SettlInstTransType.SettlInstTransTypeValues> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapCharValuesEnumField(SettlInstTransType.get(), setter::set, null);
        try {
            fixFieldsDecoderMapperImpl.onField(SettlInstTransType.get(), getDeserializationCtx("Z"));
            Assertions.fail("should have failed");
        } catch (IllegalFieldValueException ex) {
            Assertions.assertThat(ex.getMessage()).isEqualTo("Unable to find a SettlInstTransType enum value mapping for: Z");
        }
    }

    @Test
    void testDecodeCharValuesEnumWithMapperFunction() {
        AtomicReference<Character> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapCharValuesEnumField(SettlInstTransType.get(), setter::set,
                (SettlInstTransType.SettlInstTransTypeValues v) -> v == null ? null : v.code(), null);

        fixFieldsDecoderMapperImpl.onField(SettlInstTransType.get(), getDeserializationCtx("N"));
        Assertions.assertThat(setter.get()).isEqualTo('N');
    }

    @Test
    void testDecodeStringField() {
        AtomicReference<String> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapStringField(Account.get(), setter::set, null);

        fixFieldsDecoderMapperImpl.onField(Account.get(), getDeserializationCtx("FOO-1"));
        Assertions.assertThat(setter.get()).isEqualTo("FOO-1");
    }

    @Test
    void testDecodeStringFieldWithThreadLocalStrategy() {
        AtomicReference<String> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapStringField(Account.get(), setter::set, null, ObjectInstanceStrategy.THREAD_LOCAL);

        fixFieldsDecoderMapperImpl.onField(Account.get(), getDeserializationCtx("FOO-2"));
        Assertions.assertThat(setter.get()).isEqualTo("FOO-2");
    }

    @Test
    void testDecodeStringFieldWithCachedStrategy() {
        AtomicReference<String> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapStringField(Account.get(), setter::set, null, ObjectInstanceStrategy.CACHED);

        fixFieldsDecoderMapperImpl.onField(Account.get(), getDeserializationCtx("FOO-3"));
        Assertions.assertThat(setter.get()).isEqualTo("FOO-3");
    }

    @Test
    void testDecodeStringFieldVarHandle() {
        TargetHolder target = new TargetHolder();
        fixFieldsDecoderMapperImpl.mapStringField(Account.get(), "stringField", () -> target, null);

        fixFieldsDecoderMapperImpl.onField(Account.get(), getDeserializationCtx("FOO-VH"));
        Assertions.assertThat(target.stringField).isEqualTo("FOO-VH");
    }

    @Test
    void testDecodeIntField() {
        AtomicInteger setter = new AtomicInteger(-1);
        fixFieldsDecoderMapperImpl.mapIntField(AvgPxPrecision.get(), setter::set, 0);

        fixFieldsDecoderMapperImpl.onField(AvgPxPrecision.get(), getDeserializationCtx("42"));
        Assertions.assertThat(setter.get()).isEqualTo(42);
    }

    @Test
    void testDecodeIntFieldVarHandle() {
        TargetHolder target = new TargetHolder();
        fixFieldsDecoderMapperImpl.mapIntField(AvgPxPrecision.get(), "intField", () -> target, 0);

        fixFieldsDecoderMapperImpl.onField(AvgPxPrecision.get(), getDeserializationCtx("123"));
        Assertions.assertThat(target.intField).isEqualTo(123);
    }

    @Test
    void testDecodeLongField() {
        AtomicLong setter = new AtomicLong(-1);
        fixFieldsDecoderMapperImpl.mapLongField(AvgPxPrecision.get(), setter::set, 0L);

        fixFieldsDecoderMapperImpl.onField(AvgPxPrecision.get(), getDeserializationCtx("9876543210"));
        Assertions.assertThat(setter.get()).isEqualTo(9876543210L);
    }

    @Test
    void testDecodeLongFieldVarHandle() {
        TargetHolder target = new TargetHolder();
        fixFieldsDecoderMapperImpl.mapLongField(AvgPxPrecision.get(), "longField", () -> target, 0L);

        fixFieldsDecoderMapperImpl.onField(AvgPxPrecision.get(), getDeserializationCtx("1234567890"));
        Assertions.assertThat(target.longField).isEqualTo(1234567890L);
    }

    @Test
    void testDecodeDoubleField() {
        AtomicReference<Double> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapDoubleField(AvgPx.get(), setter::set, 0d);

        fixFieldsDecoderMapperImpl.onField(AvgPx.get(), getDeserializationCtx("12.5"));
        Assertions.assertThat(setter.get()).isEqualTo(12.5d);
    }

    @Test
    void testDecodeDoubleFieldVarHandle() {
        TargetHolder target = new TargetHolder();
        fixFieldsDecoderMapperImpl.mapDoubleField(AvgPx.get(), "doubleField", () -> target, 0d);

        fixFieldsDecoderMapperImpl.onField(AvgPx.get(), getDeserializationCtx("3.14"));
        Assertions.assertThat(target.doubleField).isEqualTo(3.14d);
    }

    @Test
    void testDecodeDecimalFloatField() {
        AtomicReference<DecimalFloat> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapDecimalFloatField(AvgPx.get(), setter::set, null);

        fixFieldsDecoderMapperImpl.onField(AvgPx.get(), getDeserializationCtx("99.5"));
        Assertions.assertThat(setter.get()).isNotNull();
        Assertions.assertThat(setter.get().getUnscaledValue()).isEqualTo(995L);
        Assertions.assertThat(setter.get().getScale()).isEqualTo((byte) 1);
    }

    @Test
    void testDecodeDecimalFloatFieldThreadLocal() {
        AtomicReference<DecimalFloat> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapDecimalFloatField(AvgPx.get(), setter::set, null, ObjectInstanceStrategy.THREAD_LOCAL);

        fixFieldsDecoderMapperImpl.onField(AvgPx.get(), getDeserializationCtx("7.25"));
        Assertions.assertThat(setter.get().getUnscaledValue()).isEqualTo(725L);
        Assertions.assertThat(setter.get().getScale()).isEqualTo((byte) 2);
    }

    @Test
    void testDecodeDecimalFloatFieldCached() {
        AtomicReference<DecimalFloat> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapDecimalFloatField(AvgPx.get(), setter::set, null, ObjectInstanceStrategy.CACHED);

        fixFieldsDecoderMapperImpl.onField(AvgPx.get(), getDeserializationCtx("8.25"));
        Assertions.assertThat(setter.get().getUnscaledValue()).isEqualTo(825L);
        Assertions.assertThat(setter.get().getScale()).isEqualTo((byte) 2);
    }

    @Test
    void testDecodeDecimalFloatFieldVarHandle() {
        TargetHolder target = new TargetHolder();
        fixFieldsDecoderMapperImpl.mapDecimalFloatField(AvgPx.get(), "decimalFloatField", () -> target, null);

        fixFieldsDecoderMapperImpl.onField(AvgPx.get(), getDeserializationCtx("4.25"));
        Assertions.assertThat(target.decimalFloatField.getUnscaledValue()).isEqualTo(425L);
        Assertions.assertThat(target.decimalFloatField.getScale()).isEqualTo((byte) 2);
    }

    @Test
    void testDecodeBigDecimalField() {
        AtomicReference<BigDecimal> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapBigDecimalField(AvgPx.get(), setter::set, null);

        fixFieldsDecoderMapperImpl.onField(AvgPx.get(), getDeserializationCtx("123.456"));
        Assertions.assertThat(setter.get()).isEqualByComparingTo(new BigDecimal("123.456"));
    }

    @Test
    void testDecodeBigDecimalFieldVarHandle() {
        TargetHolder target = new TargetHolder();
        fixFieldsDecoderMapperImpl.mapBigDecimalField(AvgPx.get(), "bigDecimalField", () -> target, null);

        fixFieldsDecoderMapperImpl.onField(AvgPx.get(), getDeserializationCtx("0.5"));
        Assertions.assertThat(target.bigDecimalField).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void testDecodeBooleanField() {
        AtomicBoolean setter = new AtomicBoolean(false);
        fixFieldsDecoderMapperImpl.mapBooleanField(AggregatedBook.get(), setter::set, false);

        fixFieldsDecoderMapperImpl.onField(AggregatedBook.get(), getDeserializationCtx("Y"));
        Assertions.assertThat(setter.get()).isTrue();
    }

    @Test
    void testDecodeBooleanFieldVarHandle() {
        TargetHolder target = new TargetHolder();
        fixFieldsDecoderMapperImpl.mapBooleanField(AggregatedBook.get(), "booleanField", () -> target, false);

        fixFieldsDecoderMapperImpl.onField(AggregatedBook.get(), getDeserializationCtx("Y"));
        Assertions.assertThat(target.booleanField).isTrue();
    }

    @Test
    void testDecodeBooleanObjectField() {
        AtomicReference<Boolean> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapBooleanObjectField(AggregatedBook.get(), setter::set, null);

        fixFieldsDecoderMapperImpl.onField(AggregatedBook.get(), getDeserializationCtx("N"));
        Assertions.assertThat(setter.get()).isFalse();
    }

    @Test
    void testDecodeBooleanObjectFieldVarHandle() {
        TargetHolder target = new TargetHolder();
        fixFieldsDecoderMapperImpl.mapBooleanObjectField(AggregatedBook.get(), "booleanField", () -> target, null);

        fixFieldsDecoderMapperImpl.onField(AggregatedBook.get(), getDeserializationCtx("N"));
        Assertions.assertThat(target.booleanField).isFalse();
    }

    @Test
    void testDecodeCharField() {
        AtomicReference<Character> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapCharField(LegOptAttribute.get(), setter::set, ' ');

        fixFieldsDecoderMapperImpl.onField(LegOptAttribute.get(), getDeserializationCtx("X"));
        Assertions.assertThat(setter.get()).isEqualTo('X');
    }

    @Test
    void testDecodeCharFieldVarHandle() {
        TargetHolder target = new TargetHolder();
        fixFieldsDecoderMapperImpl.mapCharField(LegOptAttribute.get(), "charField", () -> target, ' ');

        fixFieldsDecoderMapperImpl.onField(LegOptAttribute.get(), getDeserializationCtx("Z"));
        Assertions.assertThat(target.charField).isEqualTo('Z');
    }

    @Test
    void testDecodeUtcDateTimeField() {
        AtomicReference<UTCTime> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapUtcDateTimeField(EffectiveTime.get(), setter::set, null);

        fixFieldsDecoderMapperImpl.onField(EffectiveTime.get(), getDeserializationCtx("20241224-00:01:01"));
        Assertions.assertThat(setter.get()).isNotNull();
    }

    @Test
    void testDecodeUtcDateTimeFieldVarHandle() {
        TargetHolder target = new TargetHolder();
        fixFieldsDecoderMapperImpl.mapUtcDateTimeField(EffectiveTime.get(), "utcTimeField", () -> target, null);

        fixFieldsDecoderMapperImpl.onField(EffectiveTime.get(), getDeserializationCtx("20241224-00:01:01"));
        Assertions.assertThat(target.utcTimeField).isNotNull();
    }

    @Test
    void testDecodeUtcDateOnlyField() {
        AtomicInteger setter = new AtomicInteger(-1);
        fixFieldsDecoderMapperImpl.mapUtcDateOnlyField(AgreementDate.get(), setter::set, 0);

        fixFieldsDecoderMapperImpl.onField(AgreementDate.get(), getDeserializationCtx("20241224"));
        Assertions.assertThat(setter.get()).isPositive();
    }

    @Test
    void testDecodeUtcDateOnlyFieldVarHandle() {
        TargetHolder target = new TargetHolder();
        fixFieldsDecoderMapperImpl.mapUtcDateOnlyField(AgreementDate.get(), "intField", () -> target, 0);

        fixFieldsDecoderMapperImpl.onField(AgreementDate.get(), getDeserializationCtx("20241224"));
        Assertions.assertThat(target.intField).isPositive();
    }

    @Test
    void testDecodeUtcTimeOnlyField() {
        AtomicLong setter = new AtomicLong(-1);
        fixFieldsDecoderMapperImpl.mapUtcTimeOnlyField(MDEntryTime.get(), setter::set, 0L);

        fixFieldsDecoderMapperImpl.onField(MDEntryTime.get(), getDeserializationCtx("12:30:45"));
        Assertions.assertThat(setter.get()).isPositive();
    }

    @Test
    void testDecodeUtcTimeOnlyFieldVarHandle() {
        TargetHolder target = new TargetHolder();
        fixFieldsDecoderMapperImpl.mapUtcTimeOnlyField(MDEntryTime.get(), "longField", () -> target, 0L);

        fixFieldsDecoderMapperImpl.onField(MDEntryTime.get(), getDeserializationCtx("12:30:45"));
        Assertions.assertThat(target.longField).isPositive();
    }

    @Test
    void testDecodeLocalMktTimeField() {
        FixField field = FixField.of(99001, FieldType.LOCALMKTTIME, FieldLocation.BODY);
        AtomicReference<LocalTime> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapLocalMktTimeField(field, setter::set, null);

        fixFieldsDecoderMapperImpl.onField(field, getDeserializationCtx("12:30:45"));
        Assertions.assertThat(setter.get()).isEqualTo(LocalTime.of(12, 30, 45));
    }

    @Test
    void testDecodeLocalMktTimeFieldVarHandle() {
        FixField field = FixField.of(99002, FieldType.LOCALMKTTIME, FieldLocation.BODY);
        TargetHolder target = new TargetHolder();
        fixFieldsDecoderMapperImpl.mapLocalMktTimeField(field, "localTimeField", () -> target, null);

        fixFieldsDecoderMapperImpl.onField(field, getDeserializationCtx("09:15:00"));
        Assertions.assertThat(target.localTimeField).isEqualTo(LocalTime.of(9, 15, 0));
    }

    @Test
    void testDecodeTzDateTimeField() {
        FixField field = FixField.of(99003, FieldType.TZTIMESTAMP, FieldLocation.BODY);
        AtomicReference<OffsetDateTime> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapTzDateTimeField(field, setter::set, null);

        fixFieldsDecoderMapperImpl.onField(field, getDeserializationCtx("20241224-12:30:45+02:00"));
        Assertions.assertThat(setter.get()).isNotNull();
        Assertions.assertThat(setter.get().getOffset()).isEqualTo(ZoneOffset.ofHours(2));
    }

    @Test
    void testDecodeTzDateTimeFieldVarHandle() {
        FixField field = FixField.of(99004, FieldType.TZTIMESTAMP, FieldLocation.BODY);
        TargetHolder target = new TargetHolder();
        fixFieldsDecoderMapperImpl.mapTzDateTimeField(field, "offsetDateTimeField", () -> target, null);

        fixFieldsDecoderMapperImpl.onField(field, getDeserializationCtx("20241224-12:30:45+02:00"));
        Assertions.assertThat(target.offsetDateTimeField).isNotNull();
    }

    @Test
    void testDecodeTzTimeField() {
        FixField field = FixField.of(99005, FieldType.TZTIMEONLY, FieldLocation.BODY);
        AtomicReference<OffsetTime> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapTzTimeField(field, setter::set, null);

        fixFieldsDecoderMapperImpl.onField(field, getDeserializationCtx("12:30:45+02:00"));
        Assertions.assertThat(setter.get()).isNotNull();
        Assertions.assertThat(setter.get().getOffset()).isEqualTo(ZoneOffset.ofHours(2));
    }

    @Test
    void testDecodeTzTimeFieldVarHandle() {
        FixField field = FixField.of(99006, FieldType.TZTIMEONLY, FieldLocation.BODY);
        TargetHolder target = new TargetHolder();
        fixFieldsDecoderMapperImpl.mapTzTimeField(field, "offsetTimeField", () -> target, null);

        fixFieldsDecoderMapperImpl.onField(field, getDeserializationCtx("09:15:00+02:00"));
        Assertions.assertThat(target.offsetTimeField).isNotNull();
    }

    @Test
    void testMapSerdeCtxField() {
        AtomicReference<SerDe.DeserializationContext> captured = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapSerdeCtxField(Account.get(), captured::set);

        SerDe.DeserializationContext ctx = getDeserializationCtx("RAW");
        fixFieldsDecoderMapperImpl.onField(Account.get(), ctx);
        Assertions.assertThat(captured.get()).isSameAs(ctx);
    }

    @Test
    void testMappingNullFieldThrows() {
        Assertions.assertThatThrownBy(() ->
                        fixFieldsDecoderMapperImpl.mapStringField(null, s -> {
                        }, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Field cannot be null");
    }

    @Test
    void testMappingFieldWithIncompatibleTypeThrows() {
        // Account is STRING, mapping it as int must fail
        Assertions.assertThatThrownBy(() ->
                        fixFieldsDecoderMapperImpl.mapDoubleField(Account.get(), v -> {
                        }, 0d))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Incompatible field");
    }

    @Test
    void testMappingEnumOnFieldWithoutValuesThrows() {
        // Account is STRING but has no enum values
        Assertions.assertThatThrownBy(() ->
                        fixFieldsDecoderMapperImpl.mapStringValuesEnumField(Account.get(), v -> {
                        }, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no values enum defined");
    }

    @Test
    void testMappingEnumWithMismatchedEnumKindThrows() {
        // SecurityType has STRING enum values, mapping it as int enum must fail
        Assertions.assertThatThrownBy(() ->
                        fixFieldsDecoderMapperImpl.mapIntValuesEnumField(SecurityType.get(),
                                (FixField.IntValuesEnum v) -> {
                                }, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Incompatible field");
    }

    @Test
    void testFieldAutoResetTriggeredOnDecoded() {
        AtomicReference<String> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapStringField(Account.get(), setter::set, "RESET");

        fixFieldsDecoderMapperImpl.onField(Account.get(), getDeserializationCtx("VALUE"));
        Assertions.assertThat(setter.get()).isEqualTo("VALUE");

        fixFieldsDecoderMapperImpl.onDecoded();
        Assertions.assertThat(setter.get()).isEqualTo("RESET");
    }

    @Test
    void testFieldAutoResetTriggeredOnDecodingFailed() {
        AtomicReference<String> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapStringField(Account.get(), setter::set, "RESET");

        fixFieldsDecoderMapperImpl.onField(Account.get(), getDeserializationCtx("VALUE"));
        fixFieldsDecoderMapperImpl.onDecodingFailed();
        Assertions.assertThat(setter.get()).isEqualTo("RESET");
    }

    @Test
    void testFieldAutoResetDisabledKeepsValue() {
        AtomicReference<String> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.withFieldsAutoResetDisabled()
                .mapStringField(Account.get(), setter::set, "RESET");

        fixFieldsDecoderMapperImpl.onField(Account.get(), getDeserializationCtx("VALUE"));
        fixFieldsDecoderMapperImpl.onDecoded();
        Assertions.assertThat(setter.get()).isEqualTo("VALUE");
    }

    @Test
    void testFieldAutoResetCanBeReEnabled() {
        AtomicReference<String> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl
                .withFieldsAutoResetDisabled()
                .withFieldsAutoResetEnabled()
                .mapStringField(Account.get(), setter::set, "RESET");

        fixFieldsDecoderMapperImpl.onField(Account.get(), getDeserializationCtx("VALUE"));
        fixFieldsDecoderMapperImpl.onDecoded();
        Assertions.assertThat(setter.get()).isEqualTo("RESET");
    }

    @Test
    void testForGroupRoutesFieldsToGroupMapper() {
        AtomicReference<String> bodySetter = new AtomicReference<>();
        AtomicReference<String> groupSetter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapStringField(Account.get(), bodySetter::set, null);
        fixFieldsDecoderMapperImpl.forGroup(NoAllocs.get())
                .mapStringField(AllocAccount.get(), groupSetter::set, null);

        // body field still goes to body
        fixFieldsDecoderMapperImpl.onField(Account.get(), getDeserializationCtx("BODY"));
        Assertions.assertThat(bodySetter.get()).isEqualTo("BODY");

        // switch into group, group fields are routed there
        fixFieldsDecoderMapperImpl.onGroupStart(NoAllocs.get());
        fixFieldsDecoderMapperImpl.onField(AllocAccount.get(), getDeserializationCtx("ALLOC1"));
        Assertions.assertThat(groupSetter.get()).isEqualTo("ALLOC1");
    }

    @Test
    void testForGroupOnUnknownGroupThrows() {
        // Account is a real registered field but NOT a group of NewOrderSingle
        Assertions.assertThatThrownBy(() -> fixFieldsDecoderMapperImpl.forGroup(Account.get()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknow group");
    }

    @Test
    void testOnGroupStartWithUnknownGroupThrows() {
        Assertions.assertThatThrownBy(() -> fixFieldsDecoderMapperImpl.onGroupStart(Account.get()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not defined in the session dictionary");
    }

    @Test
    void testOnGroupEndSwitchesBackToBodyWhenParentNull() {
        AtomicReference<String> bodySetter = new AtomicReference<>();
        AtomicReference<String> groupSetter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapStringField(Account.get(), bodySetter::set, null);
        fixFieldsDecoderMapperImpl.forGroup(NoAllocs.get())
                .mapStringField(AllocAccount.get(), groupSetter::set, null);

        fixFieldsDecoderMapperImpl.onGroupStart(NoAllocs.get());
        fixFieldsDecoderMapperImpl.onGroupEnd(null);

        fixFieldsDecoderMapperImpl.onField(Account.get(), getDeserializationCtx("BACK_TO_BODY"));
        Assertions.assertThat(bodySetter.get()).isEqualTo("BACK_TO_BODY");
    }

    @Test
    void testOnGroupEntryEndResetsGroupFields() {
        AtomicReference<String> groupSetter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.forGroup(NoAllocs.get())
                .mapStringField(AllocAccount.get(), groupSetter::set, "ENTRY_RESET");

        fixFieldsDecoderMapperImpl.onGroupStart(NoAllocs.get());
        fixFieldsDecoderMapperImpl.onField(AllocAccount.get(), getDeserializationCtx("ENTRY1"));
        Assertions.assertThat(groupSetter.get()).isEqualTo("ENTRY1");

        fixFieldsDecoderMapperImpl.onGroupEntryEnd();
        Assertions.assertThat(groupSetter.get()).isEqualTo("ENTRY_RESET");
    }

    @Test
    void testIndexerAssignsStableIndexPerValue() {
        AtomicReference<IntSupplier> captured = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.indexer(Account.get(), captured::set);

        fixFieldsDecoderMapperImpl.onField(Account.get(), getDeserializationCtx("FOO"));
        IntSupplier first = captured.get();
        Assertions.assertThat(first.getAsInt()).isZero();

        fixFieldsDecoderMapperImpl.onField(Account.get(), getDeserializationCtx("BAR"));
        Assertions.assertThat(captured.get().getAsInt()).isEqualTo(1);

        // same value -> same index supplier instance and same index
        fixFieldsDecoderMapperImpl.onField(Account.get(), getDeserializationCtx("FOO"));
        Assertions.assertThat(captured.get().getAsInt()).isZero();
    }

    @Test
    void testIndexerCanOnlyBeRegisteredOnce() {
        fixFieldsDecoderMapperImpl.indexer(Account.get(), s -> {
        });
        Assertions.assertThatThrownBy(() ->
                        fixFieldsDecoderMapperImpl.indexer(Symbol.get(), s -> {
                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Indexer can only be set for one field");
    }

    @Test
    void testOnFieldWithoutRegisteredSetterIsIgnored() {
        // Account has no setter registered; calling onField should be a no-op (and not throw)
        fixFieldsDecoderMapperImpl.onField(Account.get(), getDeserializationCtx("ANY"));
        verifyNoInteractions(unknownFieldConsumer);
    }

    @Test
    void testDecodeUUIDField() {
        UUID uuid = UUID.fromString("0190b3c0-9c7a-7b3e-8f21-123456789abc");
        AtomicReference<UUID> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapUUIDField(Account.get(), setter::set, null);

        fixFieldsDecoderMapperImpl.onField(Account.get(), getDeserializationCtx(uuid.toString()));
        Assertions.assertThat(setter.get()).isEqualTo(uuid);
    }

    @Test
    void testDecodeUUIDFieldVarHandle() {
        UUID uuid = UUID.fromString("0190b3c0-9c7a-7b3e-8f21-123456789abc");
        TargetHolder target = new TargetHolder();
        fixFieldsDecoderMapperImpl.mapUUIDField(Account.get(), "uuidField", () -> target, null);

        fixFieldsDecoderMapperImpl.onField(Account.get(), getDeserializationCtx(uuid.toString()));
        Assertions.assertThat(target.uuidField).isEqualTo(uuid);
    }

    @Test
    void testDecodeUUIDFieldWithThreadLocalStrategy() {
        UUID uuid = UUID.fromString("0190b3c0-9c7a-7b3e-8f21-123456789abc");
        AtomicReference<UUID> setter = new AtomicReference<>();
        fixFieldsDecoderMapperImpl.mapUUIDField(Account.get(), setter::set, null, ObjectInstanceStrategy.THREAD_LOCAL);

        fixFieldsDecoderMapperImpl.onField(Account.get(), getDeserializationCtx(uuid.toString()));
        Assertions.assertThat(setter.get()).isEqualTo(uuid);
    }

    @Test
    void testDecodeUUIDFieldWithCachedStrategyThrows() {
        AtomicReference<UUID> setter = new AtomicReference<>();
        Assertions.assertThatThrownBy(() ->
                        fixFieldsDecoderMapperImpl.mapUUIDField(Account.get(), setter::set, null, ObjectInstanceStrategy.CACHED))
                .isInstanceOf(IllegalStateException.class);
    }

    SerDe.DeserializationContext getDeserializationCtx(String content) {
        return new DeserializationContextImpl().setup(content.getBytes());
    }

    static final class TargetHolder {
        String stringField;
        int intField;
        long longField;
        double doubleField;
        DecimalFloat decimalFloatField;
        BigDecimal bigDecimalField;
        boolean booleanField;
        char charField;
        UTCTime utcTimeField;
        LocalTime localTimeField;
        OffsetDateTime offsetDateTimeField;
        OffsetTime offsetTimeField;
        UUID uuidField;
    }
}
