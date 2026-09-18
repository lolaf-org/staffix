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

import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.codec.serde.BooleanSerde;

class BooleanSetterLambda extends SetterBase {

    private final FixFieldsDecoderMapper.BooleanConsumer consumer;
    private final boolean resetValue;

    BooleanSetterLambda(FixField field, FixFieldsDecoderMapper.BooleanConsumer consumer, boolean resetValue, boolean fieldAutoResetEnabled) {
        super(field, fieldAutoResetEnabled);
        this.consumer = consumer;
        this.resetValue = resetValue;
    }

    @Override
    public void accept(SerDe.DeserializationContext ctx) {
        consumer.accept(BooleanSerde.deserialize(ctx));
    }

    @Override
    public void resetFieldImpl() {
        consumer.accept(resetValue);
    }
}
