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
package org.lolaf.staffix.api.codec;

import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.serde.DecimalFloat;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.time.UTCTime;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.UUID;
import java.util.function.*;

/**
 * Fluent builder used by a {@link FixMessageDecoder} to declare how every FIX field of an incoming message must be
 * deserialized and dispatched into the decoder's state.
 * <p>
 * An instance is supplied to {@link FixMessageDecoder#mapFieldsForDecoding(FixFieldsDecoderMapper,
 * org.lolaf.staffix.api.fields.FieldsRegistry)} once, at decoder registration time. During message parsing, the FIX
 * engine looks up the registered mapping for each tag and invokes the associated setter with the decoded value,
 * without any additional reflective dispatch or intermediate object allocation on the hot path.
 * <p>
 * Every {@code mapXxxField} method returns {@code this} so declarations can be chained. Two styles are offered for
 * each primitive/object type:
 * <ul>
 *   <li><b>Lambda style</b> &mdash; takes a {@link Consumer} / {@link IntConsumer} / {@link LongConsumer} /
 *       {@link DoubleConsumer} / {@link BooleanConsumer} / {@link CharConsumer} setter. Straightforward and type safe
 *       but allocates a lambda per registration.</li>
 *   <li><b>VarHandle style</b> &mdash; takes a Java field name and a {@link Supplier} of the target instance. The
 *       engine resolves a {@link java.lang.invoke.VarHandle} once at registration and writes the decoded value
 *       directly into the field. Useful when the decoder's state holder is known up front and when lambda allocation
 *       is undesirable.</li>
 * </ul>
 * <p>
 * Repeating groups are handled by switching the builder's current mapping context with {@link #forGroup(FixField)}.
 * Any mapping registered after a {@code forGroup(...)} call applies to fields nested inside that group until another
 * {@code forGroup(...)} call (or another group-scoped chain) is made.
 * <p>
 * The {@code resetValue} argument accepted by most mapping methods is the value passed back to the setter once the
 * FIX engine is done with the current message (or group entry), so that the decoder state is returned to a clean
 * baseline before the next message is processed. This automatic reset can be toggled on a per-mapping-scope basis via
 * {@link #withFieldsAutoResetEnabled()} and {@link #withFieldsAutoResetDisabled()}.
 * <p>
 * Implementations are not thread-safe: a mapper is meant to be configured once from the decoder's
 * {@code mapFieldsForDecoding} callback and then driven exclusively by the FIX engine thread that owns the session.
 *
 * @see FixMessageDecoder#mapFieldsForDecoding(FixFieldsDecoderMapper, org.lolaf.staffix.api.fields.FieldsRegistry)
 */
public interface FixFieldsDecoderMapper {

    /**
     * Switches the mapping context to the given repeating group. Subsequent {@code mapXxxField}}
     * calls on the returned mapper register setters that fire for fields nested inside that group, once per group
     * entry.
     * <p>
     * To map fields at several nesting levels, chain successive {@code forGroup(...)} calls &mdash; each one returns
     * the mapper scoped to the requested group.
     *
     * @param groupField the {@code NumInGroup} FIX field identifying the repeating group (e.g. {@code NoRelatedSym},
     *                   {@code NoMDEntries}) as declared in the message dictionary
     * @return the mapper scoped to the given group, so further mapping calls can be chained against it
     * @throws IllegalStateException if {@code groupField} is not a known group of the message this mapper belongs to
     */
    FixFieldsDecoderMapper forGroup(FixField groupField);

    /**
     * Enables automatic reset on fields registered <b>after</b> this call for the current mapping scope (message body
     * or current group). When enabled, each setter is invoked with its declared {@code resetValue} once the message
     * (or the current group entry) has been fully processed, so the decoder state is clean for the next iteration.
     * <p>
     * Auto-reset is <b>enabled by default</b>; call this method only to re-enable it after a previous
     * {@link #withFieldsAutoResetDisabled()} in the same chain.
     *
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper withFieldsAutoResetEnabled();

    /**
     * Disables automatic reset on fields registered <b>after</b> this call for the current mapping scope. Once
     * disabled, setters are never called back with their {@code resetValue}; the decoder is then responsible for
     * clearing its own state between messages (typically from {@link FixMessageDecoder#onBegin(long, UTCTime)} or
     * {@link FixMessageDecoder#onGroupEntryEnd}).
     * <p>
     * This is the preferred mode when the decoder accumulates group entries into its own collection and needs the
     * last-decoded value to remain valid during {@code onGroupEntryEnd} processing.
     *
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper withFieldsAutoResetDisabled();

    /**
     * Registers a setter for a FIX {@code UTCTimestamp} field.
     *
     * @param field      the {@code UTCTimestamp} FIX field to map; its declared type must be {@code UTCTIMESTAMP}
     * @param setter     consumer receiving the decoded {@link UTCTime}. <b>WARNING:</b> the supplied {@link UTCTime}
     *                   instance is reused across fields and messages and must not be handed off to another thread.
     *                   If the value needs to outlive the setter invocation, copy it with {@link UTCTime#asImmutable()}
     *                   or convert it via {@link UTCTime#asInstant()}.
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled; may be
     *                   {@code null}
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapUtcDateTimeField(FixField field, Consumer<UTCTime> setter, UTCTime resetValue);

    /**
     * VarHandle variant of {@link #mapUtcDateTimeField(FixField, Consumer, UTCTime)}. The decoded {@link UTCTime} is
     * written directly into the named field of the target instance via a {@link java.lang.invoke.VarHandle} resolved
     * once at registration.
     *
     * @param field      the {@code UTCTimestamp} FIX field
     * @param fieldName  Java field name on the target object that receives the decoded value
     * @param target     supplier of the instance holding the field; invoked to resolve the {@link java.lang.invoke.VarHandle}
     *                   type at registration and to locate the instance at write time
     * @param resetValue value written to the field after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapUtcDateTimeField(FixField field, String fieldName, Supplier<Object> target, UTCTime resetValue);

    /**
     * Registers a setter for a FIX {@code TZTimestamp} field (timezone-qualified timestamp).
     *
     * @param field      the {@code TZTimestamp} FIX field; its declared type must be {@code TZTIMESTAMP}
     * @param setter     consumer receiving the decoded {@link OffsetDateTime}
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapTzDateTimeField(FixField field, Consumer<OffsetDateTime> setter, OffsetDateTime resetValue);

    /**
     * VarHandle variant of {@link #mapTzDateTimeField(FixField, Consumer, OffsetDateTime)}.
     *
     * @param field      the {@code TZTimestamp} FIX field
     * @param fieldName  Java field name on the target object
     * @param target     supplier of the instance holding the field
     * @param resetValue value written to the field after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapTzDateTimeField(FixField field, String fieldName, Supplier<Object> target, OffsetDateTime resetValue);

    /**
     * Registers a setter for a FIX {@code TZTimeOnly} field.
     *
     * @param field      the {@code TZTimeOnly} FIX field; its declared type must be {@code TZTIMEONLY}
     * @param setter     consumer receiving the decoded {@link OffsetTime}
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapTzTimeField(FixField field, Consumer<OffsetTime> setter, OffsetTime resetValue);

    /**
     * VarHandle variant of {@link #mapTzTimeField(FixField, Consumer, OffsetTime)}.
     *
     * @param field      the {@code TZTimeOnly} FIX field
     * @param fieldName  Java field name on the target object
     * @param target     supplier of the instance holding the field
     * @param resetValue value written to the field after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapTzTimeField(FixField field, String fieldName, Supplier<Object> target, OffsetTime resetValue);

    /**
     * Registers a setter for a FIX {@code LocalMktTime} field.
     *
     * @param field      the {@code LocalMktTime} FIX field; its declared type must be {@code LOCALMKTTIME}
     * @param setter     consumer receiving the decoded {@link LocalTime}
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapLocalMktTimeField(FixField field, Consumer<LocalTime> setter, LocalTime resetValue);

    /**
     * VarHandle variant of {@link #mapLocalMktTimeField(FixField, Consumer, LocalTime)}.
     *
     * @param field      the {@code LocalMktTime} FIX field
     * @param fieldName  Java field name on the target object
     * @param target     supplier of the instance holding the field
     * @param resetValue value written to the field after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapLocalMktTimeField(FixField field, String fieldName, Supplier<Object> target, LocalTime resetValue);

    /**
     * Registers a setter for a FIX {@code UTCDateOnly}, {@code UTCDate} or {@code LocalMktDate} field. The date is
     * delivered as an {@code int} count of days since the epoch, avoiding any temporal-object allocation on the hot
     * path.
     * <p>
     * Utilities on {@code UtcDateOnlySerde} can be used to turn the int back into a {@link java.time.LocalDate} when
     * needed.
     *
     * @param field      the FIX date field
     * @param setter     int consumer receiving the number of days since the epoch
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapUtcDateOnlyField(FixField field, IntConsumer setter, int resetValue);

    /**
     * VarHandle variant of {@link #mapUtcDateOnlyField(FixField, IntConsumer, int)}.
     *
     * @param field      the FIX date field
     * @param fieldName  Java {@code int} field name on the target object
     * @param target     supplier of the instance holding the field
     * @param resetValue value written to the field after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapUtcDateOnlyField(FixField field, String fieldName, Supplier<Object> target, int resetValue);

    /**
     * Registers a setter for a FIX {@code UTCTimeOnly} field. The time is delivered as a {@code long} count of
     * nanoseconds since the start of the day, avoiding any temporal-object allocation on the hot path.
     * <p>
     * Utilities on {@code UtcTimeOnlySerde} can be used to convert the long back into a {@link LocalTime} or extract
     * hours/minutes/seconds/millis/micros/nanos.
     *
     * @param field      the {@code UTCTimeOnly} FIX field
     * @param setter     long consumer receiving nanoseconds since the start of the day
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapUtcTimeOnlyField(FixField field, LongConsumer setter, long resetValue);

    /**
     * VarHandle variant of {@link #mapUtcTimeOnlyField(FixField, LongConsumer, long)}.
     *
     * @param field      the {@code UTCTimeOnly} FIX field
     * @param fieldName  Java {@code long} field name on the target object
     * @param target     supplier of the instance holding the field
     * @param resetValue value written to the field after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapUtcTimeOnlyField(FixField field, String fieldName, Supplier<Object> target, long resetValue);

    /**
     * Registers a setter for a FIX enumerated string field where each allowed value is declared as a
     * {@link FixField.StringValuesEnum} constant in the generated dictionary code.
     *
     * @param field      the FIX field; must be a {@code STRING}/{@code MULTIPLEVALUESTRING}/{@code MULTIPLESTRINGVALUE}
     *                   field with enum values declared in the dictionary
     * @param setter     consumer receiving the matched enum constant, or {@code null} if the incoming value does not
     *                   match any declared constant
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @param <T>        the generated enum type
     * @return this mapper, for chaining
     */
    <T extends FixField.StringValuesEnum> FixFieldsDecoderMapper mapStringValuesEnumField(FixField field, Consumer<T> setter, T resetValue);

    /**
     * Same as {@link #mapStringValuesEnumField(FixField, Consumer, FixField.StringValuesEnum)} but with a mapping
     * function applied between the decoded enum constant and the value handed to the setter. Useful to adapt the
     * generated enum into a domain-level representation without an extra consumer allocation.
     *
     * @param field      the FIX field
     * @param setter     consumer receiving the mapped value
     * @param mapper     function mapping the matched enum constant to the value to store; called with {@code null} if
     *                   the incoming value does not match any declared constant
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @param <T>        the generated enum type
     * @param <M>        the mapped target type
     * @return this mapper, for chaining
     */
    <T extends FixField.StringValuesEnum, M> FixFieldsDecoderMapper mapStringValuesEnumField(FixField field, Consumer<M> setter, Function<T, M> mapper, M resetValue);

    /**
     * Registers a setter for a FIX enumerated int field where each allowed value is declared as a
     * {@link FixField.IntValuesEnum} constant in the generated dictionary code.
     *
     * @param field      the FIX field; must be an {@code INT}/{@code NUMINGROUP} field with enum values declared in
     *                   the dictionary
     * @param setter     consumer receiving the matched enum constant, or {@code null} if no constant matches
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @param <T>        the generated enum type
     * @return this mapper, for chaining
     */
    <T extends FixField.IntValuesEnum> FixFieldsDecoderMapper mapIntValuesEnumField(FixField field, Consumer<T> setter, T resetValue);

    /**
     * Same as {@link #mapIntValuesEnumField(FixField, Consumer, FixField.IntValuesEnum)} but with a mapping function
     * applied between the decoded enum constant and the value handed to the setter.
     *
     * @param field      the FIX field
     * @param setter     consumer receiving the mapped value
     * @param mapper     function mapping the matched enum constant to the value to store
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @param <T>        the generated enum type
     * @param <M>        the mapped target type
     * @return this mapper, for chaining
     */
    <T extends FixField.IntValuesEnum, M> FixFieldsDecoderMapper mapIntValuesEnumField(FixField field, Consumer<M> setter, Function<T, M> mapper, M resetValue);

    /**
     * Registers a setter for a FIX enumerated char field where each allowed value is declared as a
     * {@link FixField.CharValuesEnum} constant in the generated dictionary code.
     *
     * @param field      the FIX field; must be a {@code CHAR}/{@code BOOLEAN}/{@code MULTIPLECHARVALUE} field with
     *                   enum values declared in the dictionary
     * @param setter     consumer receiving the matched enum constant, or {@code null} if no constant matches
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @param <T>        the generated enum type
     * @return this mapper, for chaining
     */
    <T extends FixField.CharValuesEnum> FixFieldsDecoderMapper mapCharValuesEnumField(FixField field, Consumer<T> setter, T resetValue);

    /**
     * Same as {@link #mapCharValuesEnumField(FixField, Consumer, FixField.CharValuesEnum)} but with a mapping function
     * applied between the decoded enum constant and the value handed to the setter.
     *
     * @param field      the FIX field
     * @param setter     consumer receiving the mapped value
     * @param mapper     function mapping the matched enum constant to the value to store
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @param <T>        the generated enum type
     * @param <M>        the mapped target type
     * @return this mapper, for chaining
     */
    <T extends FixField.CharValuesEnum, M> FixFieldsDecoderMapper mapCharValuesEnumField(FixField field, Consumer<M> setter, Function<T, M> mapper, M resetValue);

    /**
     * Registers a setter for a FIX integer field.
     *
     * @param field      the FIX field; its declared type must be {@code INT}, {@code LENGTH}, {@code NUMINGROUP},
     *                   {@code SEQNUM} or {@code TAGNUM}
     * @param setter     int consumer receiving the decoded value
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapIntField(FixField field, IntConsumer setter, int resetValue);

    /**
     * VarHandle variant of {@link #mapIntField(FixField, IntConsumer, int)}.
     *
     * @param field      the FIX integer field
     * @param fieldName  Java {@code int} field name on the target object
     * @param target     supplier of the instance holding the field
     * @param resetValue value written to the field after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapIntField(FixField field, String fieldName, Supplier<Object> target, int resetValue);

    /**
     * Registers a setter for a FIX long-valued integer field (typically used when the FIX value may exceed the
     * {@code int} range).
     *
     * @param field      the FIX integer field
     * @param setter     long consumer receiving the decoded value
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapLongField(FixField field, LongConsumer setter, long resetValue);

    /**
     * VarHandle variant of {@link #mapLongField(FixField, LongConsumer, long)}.
     *
     * @param field      the FIX integer field
     * @param fieldName  Java {@code long} field name on the target object
     * @param target     supplier of the instance holding the field
     * @param resetValue value written to the field after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapLongField(FixField field, String fieldName, Supplier<Object> target, long resetValue);

    /**
     * Registers a setter for a FIX decimal field, decoded as a {@code double}. Prefer this variant when approximate
     * floating point precision is acceptable and raw numeric performance matters; use {@link #mapDecimalFloatField}
     * or {@link #mapBigDecimalField} to preserve exact decimal semantics.
     *
     * @param field      the FIX field; its declared type must be {@code FLOAT}, {@code QTY}, {@code PRICE},
     *                   {@code PRICEOFFSET}, {@code AMT} or {@code PERCENTAGE}
     * @param setter     double consumer receiving the decoded value
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapDoubleField(FixField field, DoubleConsumer setter, double resetValue);

    /**
     * VarHandle variant of {@link #mapDoubleField(FixField, DoubleConsumer, double)}.
     *
     * @param field      the FIX decimal field
     * @param fieldName  Java {@code double} field name on the target object
     * @param target     supplier of the instance holding the field
     * @param resetValue value written to the field after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapDoubleField(FixField field, String fieldName, Supplier<Object> target, double resetValue);

    /**
     * Registers a setter for a FIX decimal field, decoded as a {@link DecimalFloat} (mantissa/exponent pair) to avoid
     * double precision loss while remaining allocation-friendly.
     *
     * @param field                  the FIX field; its declared type must be one of the decimal types accepted by
     *                               {@link #mapDoubleField}
     * @param setter                 consumer receiving the decoded {@link DecimalFloat}
     * @param resetValue             value passed to {@code setter} after message processing when auto-reset is
     *                               enabled
     * @param objectInstanceStrategy object instance strategy to apply
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapDecimalFloatField(FixField field, Consumer<DecimalFloat> setter, DecimalFloat resetValue, ObjectInstanceStrategy objectInstanceStrategy);

    /**
     * Registers a setter for a FIX decimal field, decoded as a {@link DecimalFloat} using the default
     * {@link ObjectInstanceStrategy#NEW_INSTANCE} strategy.
     *
     * @param field      the FIX field; its declared type must be one of the decimal types accepted by
     *                   {@link #mapDoubleField}
     * @param setter     consumer receiving the decoded {@link DecimalFloat}
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     * @see #mapDecimalFloatField(FixField, Consumer, DecimalFloat, ObjectInstanceStrategy)
     */
    default FixFieldsDecoderMapper mapDecimalFloatField(FixField field, Consumer<DecimalFloat> setter, DecimalFloat resetValue) {
        return mapDecimalFloatField(field, setter, resetValue, ObjectInstanceStrategy.NEW_INSTANCE);
    }

    /**
     * VarHandle variant of {@link #mapDecimalFloatField(FixField, Consumer, DecimalFloat, ObjectInstanceStrategy)}.
     *
     * @param field                  the FIX decimal field
     * @param fieldName              Java {@link DecimalFloat} field name on the target object
     * @param target                 supplier of the instance holding the field
     * @param resetValue             value written to the field after message processing when auto-reset is enabled
     * @param objectInstanceStrategy object instance strategy to apply
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapDecimalFloatField(FixField field, String fieldName, Supplier<Object> target, DecimalFloat resetValue, ObjectInstanceStrategy objectInstanceStrategy);

    /**
     * VarHandle variant of {@link #mapDecimalFloatField(FixField, Consumer, DecimalFloat)} using the default
     * {@link ObjectInstanceStrategy#NEW_INSTANCE} strategy.
     *
     * @param field      the FIX decimal field
     * @param fieldName  Java {@link DecimalFloat} field name on the target object
     * @param target     supplier of the instance holding the field
     * @param resetValue value written to the field after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     * @see #mapDecimalFloatField(FixField, String, Supplier, DecimalFloat, ObjectInstanceStrategy)
     */
    default FixFieldsDecoderMapper mapDecimalFloatField(FixField field, String fieldName, Supplier<Object> target, DecimalFloat resetValue) {
        return mapDecimalFloatField(field, fieldName, target, resetValue, ObjectInstanceStrategy.NEW_INSTANCE);
    }

    /**
     * Registers a setter for a FIX decimal field, decoded as a {@link BigDecimal}. Preserves exact decimal semantics
     * at the cost of one {@link BigDecimal} allocation per value.
     *
     * @param field      the FIX decimal field
     * @param setter     consumer receiving the decoded {@link BigDecimal}
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapBigDecimalField(FixField field, Consumer<BigDecimal> setter, BigDecimal resetValue);

    /**
     * VarHandle variant of {@link #mapBigDecimalField(FixField, Consumer, BigDecimal)}.
     *
     * @param field      the FIX decimal field
     * @param fieldName  Java {@link BigDecimal} field name on the target object
     * @param target     supplier of the instance holding the field
     * @param resetValue value written to the field after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapBigDecimalField(FixField field, String fieldName, Supplier<Object> target, BigDecimal resetValue);

    /**
     * Registers a setter for a FIX string field using the default {@link ObjectInstanceStrategy#NEW_INSTANCE}
     * strategy (one {@link String} allocation per decoded value).
     *
     * @param field      the FIX string field
     * @param setter     consumer receiving the decoded {@link String}
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     * @see #mapStringField(FixField, Consumer, String, ObjectInstanceStrategy)
     */
    default FixFieldsDecoderMapper mapStringField(FixField field, Consumer<String> setter, String resetValue) {
        return mapStringField(field, setter, resetValue, ObjectInstanceStrategy.NEW_INSTANCE);
    }

    /**
     * Registers a setter for a FIX string field with an explicit objectInstanceStrategy strategy. See
     * {@link ObjectInstanceStrategy} for the memory/allocation trade-offs of each mode.
     *
     * @param field                  the FIX field; its declared type must be a string-compatible type
     *                               ({@code STRING}, {@code COUNTRY}, {@code CURRENCY}, {@code EXCHANGE},
     *                               {@code MULTIPLEVALUESTRING}, {@code MULTIPLESTRINGVALUE}, {@code DATA},
     *                               {@code XID}, {@code XIDREF})
     * @param setter                 consumer receiving the decoded {@link String}
     * @param resetValue             value passed to {@code setter} after message processing when auto-reset is enabled
     * @param objectInstanceStrategy object instance strategy to apply
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapStringField(FixField field, Consumer<String> setter, String resetValue, ObjectInstanceStrategy objectInstanceStrategy);

    /**
     * VarHandle variant of {@link #mapStringField(FixField, Consumer, String)} using the default
     * {@link ObjectInstanceStrategy#NEW_INSTANCE} strategy.
     *
     * @param field      the FIX string field
     * @param fieldName  Java {@link String} field name on the target object
     * @param target     supplier of the instance holding the field
     * @param resetValue value written to the field after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    default FixFieldsDecoderMapper mapStringField(FixField field, String fieldName, Supplier<Object> target, String resetValue) {
        return mapStringField(field, fieldName, target, resetValue, ObjectInstanceStrategy.NEW_INSTANCE);
    }

    /**
     * VarHandle variant of {@link #mapStringField(FixField, Consumer, String, ObjectInstanceStrategy)}.
     *
     * @param field                  the FIX string field
     * @param fieldName              Java {@link String} field name on the target object
     * @param target                 supplier of the instance holding the field
     * @param resetValue             value written to the field after message processing when auto-reset is enabled
     * @param objectInstanceStrategy object instance strategy to apply
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapStringField(FixField field, String fieldName, Supplier<Object> target, String resetValue, ObjectInstanceStrategy objectInstanceStrategy);

    /**
     * Registers a setter for a FIX string field decoded as a {@link UUID}, using the default
     * {@link ObjectInstanceStrategy#NEW_INSTANCE} strategy.
     *
     * @param field      the FIX string field carrying the canonical 36-character UUID text
     * @param setter     consumer receiving the decoded {@link UUID}
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     * @see #mapUUIDField(FixField, Consumer, UUID, ObjectInstanceStrategy)
     */
    default FixFieldsDecoderMapper mapUUIDField(FixField field, Consumer<UUID> setter, UUID resetValue) {
        return mapUUIDField(field, setter, resetValue, ObjectInstanceStrategy.NEW_INSTANCE);
    }

    /**
     * Registers a setter for a FIX string field decoded as a {@link UUID} with an explicit object instance
     * strategy.
     * <p>
     * {@link ObjectInstanceStrategy#NEW_INSTANCE} and {@link ObjectInstanceStrategy#THREAD_LOCAL} are supported;
     * {@link ObjectInstanceStrategy#CACHED} throws an {@link IllegalStateException} for now.
     *
     * @param field                  the FIX field; its declared type must be a string-compatible type
     *                               ({@code STRING}, {@code COUNTRY}, {@code CURRENCY}, {@code EXCHANGE},
     *                               {@code MULTIPLEVALUESTRING}, {@code MULTIPLESTRINGVALUE}, {@code DATA},
     *                               {@code XID}, {@code XIDREF})
     * @param setter                 consumer receiving the decoded {@link UUID}
     * @param resetValue             value passed to {@code setter} after message processing when auto-reset is enabled
     * @param objectInstanceStrategy object instance strategy to apply
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapUUIDField(FixField field, Consumer<UUID> setter, UUID resetValue, ObjectInstanceStrategy objectInstanceStrategy);

    /**
     * VarHandle variant of {@link #mapUUIDField(FixField, Consumer, UUID)} using the default
     * {@link ObjectInstanceStrategy#NEW_INSTANCE} strategy.
     *
     * @param field      the FIX string field carrying the canonical 36-character UUID text
     * @param fieldName  Java {@link UUID} field name on the target object
     * @param target     supplier of the instance holding the field
     * @param resetValue value written to the field after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    default FixFieldsDecoderMapper mapUUIDField(FixField field, String fieldName, Supplier<Object> target, UUID resetValue) {
        return mapUUIDField(field, fieldName, target, resetValue, ObjectInstanceStrategy.NEW_INSTANCE);
    }

    /**
     * VarHandle variant of {@link #mapUUIDField(FixField, Consumer, UUID, ObjectInstanceStrategy)}.
     * <p>
     * {@link ObjectInstanceStrategy#NEW_INSTANCE} and {@link ObjectInstanceStrategy#THREAD_LOCAL} are supported;
     * {@link ObjectInstanceStrategy#CACHED} throws an {@link IllegalStateException} for now.
     *
     * @param field                  the FIX string field carrying the canonical 36-character UUID text
     * @param fieldName              Java {@link UUID} field name on the target object
     * @param target                 supplier of the instance holding the field
     * @param resetValue             value written to the field after message processing when auto-reset is enabled
     * @param objectInstanceStrategy object instance strategy to apply
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapUUIDField(FixField field, String fieldName, Supplier<Object> target, UUID resetValue, ObjectInstanceStrategy objectInstanceStrategy);

    /**
     * Registers a raw, type-agnostic handler for a field. The provided consumer receives the
     * {@link SerDe.DeserializationContext} pointing at the field bytes and is free to implement any custom decoding
     * logic (e.g. bespoke binary layouts carried in a {@code DATA} field, or multi-field conditional parsing).
     * <p>
     * Use this only when the typed {@code mapXxxField} methods cannot express the field's decoding semantics; typed
     * methods should be preferred whenever possible as they perform type checking against the dictionary and reuse
     * optimized SerDes.
     *
     * @param field                          the FIX field to process
     * @param deserializationContextConsumer consumer invoked with the deserialization context positioned at the
     *                                       field's value
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapSerdeCtxField(FixField field, Consumer<SerDe.DeserializationContext> deserializationContextConsumer);

    /**
     * Registers a setter for a FIX {@code BOOLEAN} field decoded as a primitive {@code boolean}.
     *
     * @param field      the FIX boolean field
     * @param setter     boolean consumer receiving the decoded value
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapBooleanField(FixField field, BooleanConsumer setter, boolean resetValue);

    /**
     * VarHandle variant of {@link #mapBooleanField(FixField, BooleanConsumer, boolean)}.
     *
     * @param field      the FIX boolean field
     * @param fieldName  Java {@code boolean} field name on the target object
     * @param target     supplier of the instance holding the field
     * @param resetValue value written to the field after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapBooleanField(FixField field, String fieldName, Supplier<Object> target, boolean resetValue);

    /**
     * Registers a setter for a FIX {@code BOOLEAN} field decoded as a boxed {@link Boolean}. Useful when the decoder
     * needs to differentiate an absent value ({@code null} reset value) from a present {@code false}.
     *
     * @param field      the FIX boolean field
     * @param setter     consumer receiving the decoded {@link Boolean}
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled; may be
     *                   {@code null}
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapBooleanObjectField(FixField field, Consumer<Boolean> setter, Boolean resetValue);

    /**
     * VarHandle variant of {@link #mapBooleanObjectField(FixField, Consumer, Boolean)}.
     *
     * @param field      the FIX boolean field
     * @param fieldName  Java {@link Boolean} field name on the target object
     * @param target     supplier of the instance holding the field
     * @param resetValue value written to the field after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapBooleanObjectField(FixField field, String fieldName, Supplier<Object> target, Boolean resetValue);

    /**
     * Registers a setter for a FIX single-character field ({@code CHAR}).
     *
     * @param field      the FIX char field
     * @param setter     char consumer receiving the decoded value
     * @param resetValue value passed to {@code setter} after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapCharField(FixField field, CharConsumer setter, char resetValue);

    /**
     * VarHandle variant of {@link #mapCharField(FixField, CharConsumer, char)}.
     *
     * @param field      the FIX char field
     * @param fieldName  Java {@code char} field name on the target object
     * @param target     supplier of the instance holding the field
     * @param resetValue value written to the field after message processing when auto-reset is enabled
     * @return this mapper, for chaining
     */
    FixFieldsDecoderMapper mapCharField(FixField field, String fieldName, Supplier<Object> target, char resetValue);

    /**
     * Assigns an index supplier for a given field. The supplied index starts at zero and is incremented by one for
     * each distinct value observed for that field across decoded messages. The index can be used to drive
     * {@link FixSession#getMessageExecutor(Class, IntSupplier)} for per-key ordered multi-threaded processing, or to key
     * into specialized data structures (e.g. open-addressed arrays) for extremely fast writes and reads.
     * <p>
     * Only one indexer may be registered per message decoder instance.
     *
     * @param field  the FIX field to index
     * @param target consumer receiving the {@link IntSupplier} that resolves to the current field-value index; it is
     *               handed over once per message before the setters fire, so that downstream code can capture the
     *               supplier alongside its other per-field state
     * @return this mapper, for chaining
     * @throws IllegalStateException if an indexer has already been registered
     */
    FixFieldsDecoderMapper indexer(FixField field, Consumer<IntSupplier> target);

    /**
     * Object instance strategy
     */
    enum ObjectInstanceStrategy {

        /**
         * Standard deserialization: a fresh new object instance is allocated for each decoded value. The
         * reference is safe to retain and to hand off to other threads.
         */
        NEW_INSTANCE,

        /**
         * The object is backed by a thread-local buffer reused across decoded values. The returned
         * reference is invalidated as soon as the next field using the {@code THREAD_LOCAL} strategy is decoded.
         * <p>
         * It is safe to read it within the setter body. It is also safe to read it across the full message if this
         * is the only field on the decoder using {@code THREAD_LOCAL}. Never hand it off to another thread.
         * <p>
         * Use case is specific but allows zero-allocation processing of a field.
         */
        THREAD_LOCAL,

        /**
         * The object is served from a cache bound to the {@link org.lolaf.staffix.api.session.FixSessionId} who is decoding the field.
         * Cache entries are reused across messages for the given FixSessionId, so identical values no longer allocate.
         * <p>
         * The cache is unbounded, so this strategy should only be used for fields whose value universe is small
         * (symbols, currencies, exchanges, enumerations); using it on a high-cardinality field
         * would leak memory over time.
         */
        CACHED
    }

    /**
     * Primitive-specialized consumer for {@code boolean} values, mirroring {@link IntConsumer} for integers. Used by
     * {@link #mapBooleanField(FixField, BooleanConsumer, boolean)} to avoid boxing.
     */
    @FunctionalInterface
    interface BooleanConsumer {

        /**
         * Receives the decoded boolean value.
         *
         * @param value the decoded boolean
         */
        void accept(boolean value);
    }

    /**
     * Primitive-specialized consumer for {@code char} values, mirroring {@link IntConsumer} for integers. Used by
     * {@link #mapCharField(FixField, CharConsumer, char)} to avoid boxing.
     */
    @FunctionalInterface
    interface CharConsumer {

        /**
         * Receives the decoded char value.
         *
         * @param value the decoded char
         */
        void accept(char value);
    }
}
