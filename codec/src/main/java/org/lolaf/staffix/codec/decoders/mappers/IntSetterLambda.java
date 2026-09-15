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

import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.serde.SerDe;

import java.util.function.IntConsumer;
import java.util.function.ToIntFunction;

class IntSetterLambda extends SetterBase {

    private final ToIntFunction<SerDe.DeserializationContext> deserializer;
    private final IntConsumer consumer;
    private final int resetValue;

    IntSetterLambda(FixField field, ToIntFunction<SerDe.DeserializationContext> deserializer, IntConsumer consumer, int resetValue, boolean fieldAutoResetEnabled) {
        super(field, fieldAutoResetEnabled);
        this.deserializer = deserializer;
        this.consumer = consumer;
        this.resetValue = resetValue;
    }

    @Override
    public void accept(SerDe.DeserializationContext ctx) {
        consumer.accept(deserializer.applyAsInt(ctx));
    }

    @Override
    public void resetFieldImpl() {
        consumer.accept(resetValue);
    }
}
