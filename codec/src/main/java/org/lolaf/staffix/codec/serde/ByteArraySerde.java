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
package org.lolaf.staffix.codec.serde;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.lolaf.staffix.api.serde.SerDe;

import java.nio.ByteBuffer;

/**
 * Allows to access the raw serialized content of a field
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ByteArraySerde implements SerDe<byte[]> {

    private static final ByteArraySerde INSTANCE = new ByteArraySerde();

    public static ByteArraySerde instance() {
        return INSTANCE;
    }

    @Override
    public void serialize(ByteBuffer out, byte[] value) {
        out.put(value);
    }

    @Override
    public byte[] deserialize(DeserializationContext serdeContext) {
        byte[] deserialized = new byte[serdeContext.getLength()];
        System.arraycopy(serdeContext.getDeserializationBuffer(), serdeContext.getStartOffset(), deserialized, 0, serdeContext.getLength());
        return deserialized;
    }
}