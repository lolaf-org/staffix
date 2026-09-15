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

import org.lolaf.staffix.api.codec.SessionRejectReasonCodes;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.serde.StringSerde;

import java.util.function.Consumer;
import java.util.function.Function;

class StringValuesEnumSetter<T extends FixField.StringValuesEnum, M> extends SetterBase {

    private final Consumer<T> plainConsumer;
    private final Consumer<M> mapperConsumer;
    private final Function<T, M> mapper;
    private final T plainResetValue;
    private final M mapperResetValue;

    StringValuesEnumSetter(FixField field, Consumer<T> consumer, T resetValue, boolean fieldAutoResetEnabled) {
        super(field, fieldAutoResetEnabled);
        this.plainConsumer = consumer;
        this.plainResetValue = resetValue;
        this.mapperConsumer = null;
        this.mapper = null;
        this.mapperResetValue = null;
    }

    StringValuesEnumSetter(FixField field, Consumer<M> consumer, Function<T, M> mapper, M resetValue, boolean fieldAutoResetEnabled) {
        super(field, fieldAutoResetEnabled);
        this.mapperConsumer = consumer;
        this.mapper = mapper;
        this.mapperResetValue = resetValue;
        this.plainConsumer = null;
        this.plainResetValue = null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void accept(SerDe.DeserializationContext ctx) {
        String providedValue = StringSerde.instance().deserialize(ctx);
        FixField field = getField();
        for (Enum<?> candidate : field.getValues()) {
            FixField.StringValuesEnum enumValue = (FixField.StringValuesEnum) candidate;
            if (enumValue.code().equals(providedValue)) {
                T matched = (T) enumValue;
                if (plainConsumer != null) {
                    plainConsumer.accept(matched);
                } else {
                    mapperConsumer.accept(mapper.apply(matched));
                }
                return;
            }
        }
        throw new IllegalFieldValueException("Unable to find a " + field.getClass().getSimpleName() + " enum value mapping for: " + providedValue,
                SessionRejectReasonCodes.VALUE_IS_INCORRECT);
    }

    @Override
    public void resetFieldImpl() {
        if (plainConsumer != null) {
            plainConsumer.accept(plainResetValue);
        } else {
            mapperConsumer.accept(mapperResetValue);
        }
    }
}