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
import org.lolaf.staffix.api.serde.Hashing;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.collections.Int2ObjectHashMap;

import java.util.function.Consumer;
import java.util.function.IntSupplier;

class IndexerSetter extends SetterBase {

    private final Consumer<IntSupplier> consumer;
    private final Int2ObjectHashMap<IntSupplier> indexes;

    IndexerSetter(FixField field, Consumer<IntSupplier> consumer) {
        super(field, false);
        this.consumer = consumer;
        this.indexes = new Int2ObjectHashMap<>(8);
    }

    @Override
    public void accept(SerDe.DeserializationContext deserializationContext) {
        int fixFieldValueHash = Hashing.hash(deserializationContext.getDeserializationBuffer(), deserializationContext.getStartOffset(), deserializationContext.getLength());
        IntSupplier index = indexes.get(fixFieldValueHash);
        if (index == null) {
            int currentMapSize = indexes.size();
            index = () -> currentMapSize;
            indexes.put(fixFieldValueHash, index);
        }
        consumer.accept(index);
    }

    @Override
    public void resetFieldImpl() {
        throw new IllegalStateException("should never have been called");
    }
}
