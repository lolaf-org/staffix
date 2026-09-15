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

import lombok.Getter;
import org.lolaf.staffix.api.serde.SerDe;

/**
 * A field's window into the read buffer - the array, an offset and a length.
 *
 * <p>Mutated and handed to each serde in turn rather than allocated per field, which is what keeps decoding
 * allocation-free. Valid only for the call it is passed to.
 */
@Getter
public class DeserializationContextImpl implements SerDe.DeserializationContext {

    private byte[] deserializationBuffer;
    private int startOffset;
    private int length;

    @Override
    public SerDe.DeserializationContext setup(byte[] deserializationBuffer, int startOffset, int length) {
        this.deserializationBuffer = deserializationBuffer;
        this.startOffset = startOffset;
        this.length = length;
        return this;
    }

    @Override
    public SerDe.DeserializationContext setup(byte[] deserializationBuffer) {
        this.deserializationBuffer = deserializationBuffer;
        this.startOffset = 0;
        this.length = deserializationBuffer.length;
        return this;
    }

    @Override
    public void clean() {
        deserializationBuffer = null;
        startOffset = length = 0;
    }

    @Override
    public String contentToString() {
        return deserializationBuffer != null
                ? new String(deserializationBuffer, startOffset, length, SerDe.CHARSET) : "";
    }
}