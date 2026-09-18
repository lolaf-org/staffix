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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.MessageFieldsRegistry;
import org.lolaf.staffix.api.serde.DecimalFloat;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.collections.IndexableMap;
import org.lolaf.staffix.codec.serde.*;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;

import static org.lolaf.staffix.api.fields.FieldType.*;

/**
 * Binds a decoder's fields to its variables, once, at registration.
 *
 * <p>Each binding becomes a setter the parser calls directly, so reading a field costs a call rather than a
 * map lookup - which is the point of doing this at startup instead of per message.
 */
@Slf4j
public class FixFieldsDecoderMapperImpl implements FixFieldsDecoderMapper {

    private static final FixField BODY_GROUP_FIELD = new FixField() {
        @Override
        public int getCode() {
            throw new IllegalStateException("Should have never been called");
        }

        @Override
        public byte[] serialized() {
            throw new IllegalStateException("Should have never been called");
        }

        @Override
        public int checksum() {
            throw new IllegalStateException("Should have never been called");
        }

        @Override
        public FieldType getType() {
            throw new IllegalStateException("Should have never been called");
        }

        @Override
        public FieldLocation getLocation() {
            throw new IllegalStateException("Should have never been called");
        }

        @Override
        public int getAsInt() {
            return 0; // FIX first field starts with 1 luckily
        }

        @Override
        public String toString() {
            return "Body";
        }
    };

    private static final FieldType[] STRING_FIELD_TYPES = new FieldType[]{
            STRING,
            COUNTRY,
            CURRENCY,
            EXCHANGE,
            MULTIPLEVALUESTRING,
            MULTIPLESTRINGVALUE,
            DATA,
            XID,
            XIDREF};
    private static final FieldType[] INT_OR_STRING_FIELD_TYPES = new FieldType[]{
            STRING,
            DATA,
            INT,
            LENGTH,
            NUMINGROUP,
            SEQNUM,
            TAGNUM};
    private static final FieldType[] DECIMAL_FIELD_TYPES = new FieldType[]{
            FLOAT,
            QTY,
            PRICE,
            PRICEOFFSET,
            AMT,
            PERCENTAGE};

    private final List<FieldSetter> executedSetters;
    private final Map<FixField, FixFieldsDecoderMapperImpl> fixFieldsDecoderMappersPerGroup;
    private final BiConsumer<FixField, SerDe.DeserializationContext> unknownFieldConsumer;
    private final Map<FixField, FieldSetter> fieldSetters;
    private final FixSessionId fixSessionId;
    private boolean fieldAutoResetEnabled = true;
    private FixFieldsDecoderMapperImpl activeMapper;
    private IndexerSetter indexerSetter;

    public FixFieldsDecoderMapperImpl(MessageFieldsRegistry.FixMessageFields messageFields, FixSessionId fixSessionId,
                                      BiConsumer<FixField, SerDe.DeserializationContext> unknownFieldConsumer) {
        this.fixSessionId = fixSessionId;
        this.unknownFieldConsumer = unknownFieldConsumer;
        this.fixFieldsDecoderMappersPerGroup = new IndexableMap<>(FixField.class);
        this.fixFieldsDecoderMappersPerGroup.put(BODY_GROUP_FIELD, this);
        messageFields.getGroupFields().forEach(f -> fixFieldsDecoderMappersPerGroup.put(f, new FixFieldsDecoderMapperImpl(fixFieldsDecoderMappersPerGroup, unknownFieldConsumer, fixSessionId)));
        this.executedSetters = new ArrayList<>(8);
        this.fieldSetters = new HashMap<>(); // perfs seems slightly better than with IndexableMap, no need of an identity hashmap because fix fields are by design singletons
    }

    private FixFieldsDecoderMapperImpl(Map<FixField, FixFieldsDecoderMapperImpl> fixFieldsDecoderMappersPerGroup, BiConsumer<FixField, SerDe.DeserializationContext> unknownFieldConsumer,
                                       FixSessionId fixSessionId) {
        this.fixSessionId = fixSessionId;
        this.unknownFieldConsumer = unknownFieldConsumer;
        this.fixFieldsDecoderMappersPerGroup = fixFieldsDecoderMappersPerGroup;
        this.executedSetters = new ArrayList<>(8);
        this.fieldSetters = new HashMap<>();
    }

    private static void ensureFieldNotNull(FixField field, FieldType... allowedFieldTypes) {
        if (field == null) {
            throw new IllegalArgumentException("Field cannot be null");
        }
        if (allowedFieldTypes != null && Arrays.stream(allowedFieldTypes).noneMatch(ft -> ft.equals(field.getType()))) {
            throw new IllegalArgumentException("Incompatible field " + field + " data type " + field.getType().name() + ", only "
                    + Arrays.stream(allowedFieldTypes).map(Enum::name).collect(Collectors.joining(",")) + " are allowed for registration");
        }
    }

    private static void checkValuesTypeForField(FixField field, Class<? extends FixField.ValuesEnum> valuesEnumClass) {
        if (!field.hasValues()) {
            throw new IllegalStateException("Fix field " + field.getCode() + " has no values enum defined");
        }
        if (!valuesEnumClass.isAssignableFrom(field.getValues()[0].getClass())) {
            throw new IllegalStateException("Fix field " + field.getCode() + " values enum is not of type " + valuesEnumClass.getName());
        }
    }

    private static <T> Function<SerDe.DeserializationContext, T> selectDeserializer(ObjectInstanceStrategy objectInstanceStrategy,
                                                                                    Function<SerDe.DeserializationContext, T> newInstance,
                                                                                    Function<SerDe.DeserializationContext, T> threadLocal,
                                                                                    Function<SerDe.DeserializationContext, T> cached) {
        switch (objectInstanceStrategy) {
            case NEW_INSTANCE:
                return newInstance;
            case THREAD_LOCAL:
                if (threadLocal == null) {
                    throw new IllegalStateException("THREAD_LOCAL instance strategy is not supported");
                }
                return threadLocal;
            case CACHED:
                if (cached == null) {
                    throw new IllegalStateException("CACHED instance strategy is not supported");
                }
                return cached;
            default:
                throw new IllegalArgumentException("Unknown ObjectInstanceStrategy: " + objectInstanceStrategy);
        }
    }

    public void onBegin() {
        activeMapper = fixFieldsDecoderMappersPerGroup.get(BODY_GROUP_FIELD);
    }

    public void onDecoded() {
        resetProcessedFields();
        activeMapper = null;
    }

    public void onDecodingFailed() {
        fixFieldsDecoderMappersPerGroup.values().forEach(FixFieldsDecoderMapperImpl::resetProcessedFields);
        activeMapper = null;
    }

    public void onGroupStart(FixField groupField) {
        activeMapper = fixFieldsDecoderMappersPerGroup.get(groupField);
        if (activeMapper == null) {
            throw new IllegalStateException("Received a group field " + groupField + " not defined in the session dictionary");
        }
    }

    public void onGroupEnd(FixField parentGroup) {
        activeMapper = fixFieldsDecoderMappersPerGroup.get(parentGroup == null ? BODY_GROUP_FIELD : parentGroup);
    }

    public void onGroupEntryEnd() {
        activeMapper.resetProcessedFields();
    }

    private void resetProcessedFields() {
        if (!executedSetters.isEmpty()) {
            executedSetters.forEach(FieldSetter::resetField);
            executedSetters.clear();
        }
    }

    private void register(FixField field, FieldSetter setter) {
        fieldSetters.put(field, setter);
    }

    @Override
    public FixFieldsDecoderMapper indexer(FixField field, Consumer<IntSupplier> target) {
        if (indexerSetter != null) {
            throw new IllegalStateException("Indexer can only be set for one field in the message");
        }
        indexerSetter = new IndexerSetter(field, target);
        return this;
    }

    @Override
    public FixFieldsDecoderMapper forGroup(FixField groupField) {
        FixFieldsDecoderMapper mapper = fixFieldsDecoderMappersPerGroup.get(groupField);
        if (mapper == null) {
            throw new IllegalStateException("Unknow group for message, available groups are " + fixFieldsDecoderMappersPerGroup.keySet());
        }
        return mapper;
    }

    @Override
    public FixFieldsDecoderMapper withFieldsAutoResetDisabled() {
        fieldAutoResetEnabled = false;
        return this;
    }

    @Override
    public FixFieldsDecoderMapper withFieldsAutoResetEnabled() {
        fieldAutoResetEnabled = true;
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapLocalMktTimeField(FixField field, Consumer<LocalTime> setter, LocalTime resetValue) {
        ensureFieldNotNull(field, LOCALMKTTIME);
        register(field, new ConsumerSetterLambda<>(field, LocalMktTimeSerde.instance()::deserialize, setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapLocalMktTimeField(FixField field, String fieldName, Supplier<Object> target, LocalTime resetValue) {
        ensureFieldNotNull(field, LOCALMKTTIME);
        register(field, new ConsumerSetterVarHandle<>(field, LocalMktTimeSerde.instance()::deserialize, findVarHandle(fieldName, LocalTime.class, target.get()), target, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapUtcDateTimeField(FixField field, Consumer<UTCTime> setter, UTCTime resetValue) {
        ensureFieldNotNull(field, UTCTIMESTAMP);
        register(field, new ConsumerSetterLambda<>(field, UtcDateTimeSerde::deserialize, setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapUtcDateTimeField(FixField field, String fieldName, Supplier<Object> target, UTCTime resetValue) {
        ensureFieldNotNull(field, UTCTIMESTAMP);
        register(field, new ConsumerSetterVarHandle<>(field, UtcDateTimeSerde::deserialize, findVarHandle(fieldName, UTCTime.class, target.get()), target, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapUtcDateOnlyField(FixField field, IntConsumer setter, int resetValue) {
        ensureFieldNotNull(field, UTCDATEONLY, UTCDATE, LOCALMKTDATE);
        register(field, new IntSetterLambda(field, UtcDateOnlySerde::deserializeToEpochDays, setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapUtcDateOnlyField(FixField field, String fieldName, Supplier<Object> target, int resetValue) {
        ensureFieldNotNull(field, UTCDATEONLY, UTCDATE, LOCALMKTDATE);
        register(field, new IntSetterVarHandle(field, UtcDateOnlySerde::deserializeToEpochDays, findVarHandle(fieldName, int.class, target.get()), target, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapUtcTimeOnlyField(FixField field, LongConsumer setter, long resetValue) {
        ensureFieldNotNull(field, UTCTIMEONLY);
        register(field, new LongSetterLambda(field, UtcTimeOnlySerde::deserialize, setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapUtcTimeOnlyField(FixField field, String fieldName, Supplier<Object> target, long resetValue) {
        ensureFieldNotNull(field, UTCTIMEONLY);
        register(field, new LongSetterVarHandle(field, UtcTimeOnlySerde::deserialize, findVarHandle(fieldName, long.class, target.get()), target, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapTzDateTimeField(FixField field, Consumer<OffsetDateTime> setter, OffsetDateTime resetValue) {
        ensureFieldNotNull(field, TZTIMESTAMP);
        register(field, new ConsumerSetterLambda<>(field, TzDateTimeSerde.instance()::deserialize, setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapTzDateTimeField(FixField field, String fieldName, Supplier<Object> target, OffsetDateTime resetValue) {
        ensureFieldNotNull(field, TZTIMESTAMP);
        register(field, new ConsumerSetterVarHandle<>(field, TzDateTimeSerde.instance()::deserialize, findVarHandle(fieldName, OffsetDateTime.class, target.get()), target, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapTzTimeField(FixField field, Consumer<OffsetTime> setter, OffsetTime resetValue) {
        ensureFieldNotNull(field, TZTIMEONLY);
        register(field, new ConsumerSetterLambda<>(field, TzTimeOnlySerde.instance()::deserialize, setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapTzTimeField(FixField field, String fieldName, Supplier<Object> target, OffsetTime resetValue) {
        ensureFieldNotNull(field, TZTIMEONLY);
        register(field, new ConsumerSetterVarHandle<>(field, TzTimeOnlySerde.instance()::deserialize, findVarHandle(fieldName, OffsetTime.class, target.get()), target, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public <T extends FixField.StringValuesEnum> FixFieldsDecoderMapper mapStringValuesEnumField(FixField field, Consumer<T> setter, T resetValue) {
        ensureFieldNotNull(field, STRING, MULTIPLEVALUESTRING, MULTIPLESTRINGVALUE);
        checkValuesTypeForField(field, FixField.StringValuesEnum.class);
        register(field, new StringValuesEnumSetter<T, T>(field, setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public <T extends FixField.StringValuesEnum, M> FixFieldsDecoderMapper mapStringValuesEnumField(FixField field, Consumer<M> setter, Function<T, M> mapper, M resetValue) {
        ensureFieldNotNull(field, STRING, MULTIPLEVALUESTRING, MULTIPLESTRINGVALUE);
        checkValuesTypeForField(field, FixField.StringValuesEnum.class);
        register(field, new StringValuesEnumSetter<T, M>(field, setter, mapper, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public <T extends FixField.IntValuesEnum> FixFieldsDecoderMapper mapIntValuesEnumField(FixField field, Consumer<T> setter, T resetValue) {
        ensureFieldNotNull(field, INT, NUMINGROUP);
        checkValuesTypeForField(field, FixField.IntValuesEnum.class);
        register(field, new IntValuesEnumSetter<>(field, setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public <T extends FixField.IntValuesEnum, M> FixFieldsDecoderMapper mapIntValuesEnumField(FixField field, Consumer<M> setter, Function<T, M> mapper, M resetValue) {
        ensureFieldNotNull(field, INT, NUMINGROUP);
        checkValuesTypeForField(field, FixField.IntValuesEnum.class);
        register(field, new IntValuesEnumSetter<>(field, setter, mapper, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public <T extends FixField.CharValuesEnum> FixFieldsDecoderMapper mapCharValuesEnumField(FixField field, Consumer<T> setter, T resetValue) {
        ensureFieldNotNull(field, CHAR, BOOLEAN, MULTIPLECHARVALUE);
        checkValuesTypeForField(field, FixField.CharValuesEnum.class);
        register(field, new CharValuesEnumSetter<>(field, setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public <T extends FixField.CharValuesEnum, M> FixFieldsDecoderMapper mapCharValuesEnumField(FixField field, Consumer<M> setter, Function<T, M> mapper, M resetValue) {
        ensureFieldNotNull(field, CHAR, BOOLEAN, MULTIPLECHARVALUE);
        checkValuesTypeForField(field, FixField.CharValuesEnum.class);
        register(field, new CharValuesEnumSetter<>(field, setter, mapper, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapDoubleField(FixField field, DoubleConsumer setter, double resetValue) {
        ensureFieldNotNull(field, DECIMAL_FIELD_TYPES);
        register(field, new DoubleSetterLambda(field, DoubleSerde::deserialize, setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapDoubleField(FixField field, String fieldName, Supplier<Object> target, double resetValue) {
        ensureFieldNotNull(field, DECIMAL_FIELD_TYPES);
        register(field, new DoubleSetterVarHandle(field, DoubleSerde::deserialize, findVarHandle(fieldName, double.class, target.get()), target, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapDecimalFloatField(FixField field, Consumer<DecimalFloat> setter, DecimalFloat resetValue, ObjectInstanceStrategy objectInstanceStrategy) {
        ensureFieldNotNull(field, DECIMAL_FIELD_TYPES);
        register(field, new DecimalFloatSetterLambda(field,
                selectDeserializer(objectInstanceStrategy, DecimalFloatSerde.instance()::deserialize, DecimalFloatTLSerde.instance()::deserialize, DecimalFloatCachedSerde.instance(fixSessionId)::deserialize),
                setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapDecimalFloatField(FixField field, String fieldName, Supplier<Object> target, DecimalFloat resetValue, ObjectInstanceStrategy objectInstanceStrategy) {
        ensureFieldNotNull(field, DECIMAL_FIELD_TYPES);
        register(field, new DecimalFloatSetterVarHandle(field,
                selectDeserializer(objectInstanceStrategy, DecimalFloatSerde.instance()::deserialize, DecimalFloatTLSerde.instance()::deserialize, DecimalFloatCachedSerde.instance(fixSessionId)::deserialize),
                findVarHandle(fieldName, DecimalFloat.class, target.get()), target, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapBigDecimalField(FixField field, Consumer<BigDecimal> setter, BigDecimal resetValue) {
        ensureFieldNotNull(field, DECIMAL_FIELD_TYPES);
        register(field, new BigDecimalSetterLambda(field, BigDecimalSerde.instance()::deserialize, setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapBigDecimalField(FixField field, String fieldName, Supplier<Object> target, BigDecimal resetValue) {
        ensureFieldNotNull(field, DECIMAL_FIELD_TYPES);
        register(field, new BigDecimalSetterVarHandle(field, BigDecimalSerde.instance()::deserialize, findVarHandle(fieldName, BigDecimal.class, target.get()), target, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapIntField(FixField field, IntConsumer setter, int resetValue) {
        ensureFieldNotNull(field, INT_OR_STRING_FIELD_TYPES);
        register(field, new IntSetterLambda(field, IntSerde::deserialize, setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapIntField(FixField field, String fieldName, Supplier<Object> target, int resetValue) {
        ensureFieldNotNull(field, INT_OR_STRING_FIELD_TYPES);
        register(field, new IntSetterVarHandle(field, IntSerde::deserialize, findVarHandle(fieldName, int.class, target.get()), target, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapLongField(FixField field, LongConsumer setter, long resetValue) {
        ensureFieldNotNull(field, INT_OR_STRING_FIELD_TYPES);
        register(field, new LongSetterLambda(field, LongSerde::deserialize, setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapLongField(FixField field, String fieldName, Supplier<Object> target, long resetValue) {
        ensureFieldNotNull(field, INT_OR_STRING_FIELD_TYPES);
        register(field, new LongSetterVarHandle(field, LongSerde::deserialize, findVarHandle(fieldName, long.class, target.get()), target, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapStringField(FixField field, Consumer<String> setter, String resetValue, ObjectInstanceStrategy objectInstanceStrategy) {
        ensureFieldNotNull(field, STRING_FIELD_TYPES);
        register(field, new StringSetterLambda(field,
                selectDeserializer(objectInstanceStrategy, StringSerde.instance()::deserialize, StringThreadLocalSerde.instance()::deserialize, StringCachedSerde.instance(fixSessionId)::deserialize),
                setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapStringField(FixField field, String fieldName, Supplier<Object> target, String resetValue, ObjectInstanceStrategy objectInstanceStrategy) {
        ensureFieldNotNull(field, STRING_FIELD_TYPES);
        register(field, new StringSetterVarHandle(field,
                selectDeserializer(objectInstanceStrategy, StringSerde.instance()::deserialize, StringThreadLocalSerde.instance()::deserialize, StringCachedSerde.instance(fixSessionId)::deserialize),
                findVarHandle(fieldName, String.class, target.get()), target, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapUUIDField(FixField field, Consumer<UUID> setter, UUID resetValue, ObjectInstanceStrategy objectInstanceStrategy) {
        ensureFieldNotNull(field, STRING_FIELD_TYPES);
        register(field, new ConsumerSetterLambda<>(field,
                selectDeserializer(objectInstanceStrategy, UUIDSerde.instance()::deserialize, UUIDThreadLocalSerde.instance()::deserialize, null),
                setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapUUIDField(FixField field, String fieldName, Supplier<Object> target, UUID resetValue, ObjectInstanceStrategy objectInstanceStrategy) {
        ensureFieldNotNull(field, STRING_FIELD_TYPES);
        register(field, new ConsumerSetterVarHandle<>(field,
                selectDeserializer(objectInstanceStrategy, UUIDSerde.instance()::deserialize, UUIDThreadLocalSerde.instance()::deserialize, null),
                findVarHandle(fieldName, UUID.class, target.get()), target, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapSerdeCtxField(FixField field, Consumer<SerDe.DeserializationContext> deserializationContextConsumer) {
        register(field, new SetterSerde(field, deserializationContextConsumer));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapBooleanObjectField(FixField field, Consumer<Boolean> setter, Boolean resetValue) {
        ensureFieldNotNull(field, BOOLEAN);
        register(field, new BooleanObjectSetterLambda(field, setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapBooleanObjectField(FixField field, String fieldName, Supplier<Object> target, Boolean resetValue) {
        ensureFieldNotNull(field, BOOLEAN);
        register(field, new BooleanObjectSetterVarHandle(field, findVarHandle(fieldName, boolean.class, target.get()), target, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapBooleanField(FixField field, BooleanConsumer setter, boolean resetValue) {
        ensureFieldNotNull(field, BOOLEAN);
        register(field, new BooleanSetterLambda(field, setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapBooleanField(FixField field, String fieldName, Supplier<Object> target, boolean resetValue) {
        ensureFieldNotNull(field, BOOLEAN);
        register(field, new BooleanSetterVarHandle(field, findVarHandle(fieldName, boolean.class, target.get()), target, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapCharField(FixField field, CharConsumer setter, char resetValue) {
        ensureFieldNotNull(field, CHAR);
        register(field, new CharSetterLambda(field, setter, resetValue, fieldAutoResetEnabled));
        return this;
    }

    @Override
    public FixFieldsDecoderMapper mapCharField(FixField field, String fieldName, Supplier<Object> target, char resetValue) {
        ensureFieldNotNull(field, CHAR);
        register(field, new CharSetterVarHandle(field, findVarHandle(fieldName, char.class, target.get()), target, resetValue, fieldAutoResetEnabled));
        return this;
    }

    public void onField(FixField fixField, SerDe.DeserializationContext deserializationContext) {
        activeMapper.processField(fixField, deserializationContext);
    }

    private void processField(FixField fixField, SerDe.DeserializationContext deserializationContext) {
        if (indexerSetter != null && indexerSetter.getField().equals(fixField)) {
            indexerSetter.accept(deserializationContext);
        }
        if (fixField.getType() == UNKNOWN) {
            unknownFieldConsumer.accept(fixField, deserializationContext);
            return;
        }
        FieldSetter setter = fieldSetters.get(fixField);
        if (setter != null) {
            setter.accept(deserializationContext);
            if (setter.isFieldResetEnabled()) {
                executedSetters.add(setter);
            }
        }
    }

    private VarHandle findVarHandle(String fieldName, Class<?> typeClass, Object varHandleTarget) {
        try {
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(varHandleTarget.getClass(), MethodHandles.lookup());
            return l.findVarHandle(varHandleTarget.getClass(), fieldName, typeClass);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to find '" + typeClass.getSimpleName() + " " + fieldName + "' in class " + this.getClass(), e);
        }
    }
}