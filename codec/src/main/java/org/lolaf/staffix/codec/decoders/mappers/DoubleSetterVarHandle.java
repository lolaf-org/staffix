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

import java.lang.invoke.VarHandle;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

class DoubleSetterVarHandle extends VarHandleSetterBase {

    private final ToDoubleFunction<SerDe.DeserializationContext> deserializer;
    private final double resetValue;

    DoubleSetterVarHandle(FixField field, ToDoubleFunction<SerDe.DeserializationContext> deserializer, VarHandle varHandle, Supplier<Object> varHandleTargetSupplier, double resetValue, boolean fieldAutoResetEnabled) {
        super(field, varHandle, varHandleTargetSupplier, fieldAutoResetEnabled);
        this.deserializer = deserializer;
        this.resetValue = resetValue;
    }

    @Override
    public void accept(SerDe.DeserializationContext ctx) {
        varHandle.set(varHandleTargetSupplier.get(), deserializer.applyAsDouble(ctx));
    }

    @Override
    public void resetFieldImpl() {
        varHandle.set(varHandleTargetSupplier.get(), resetValue);
    }
}
