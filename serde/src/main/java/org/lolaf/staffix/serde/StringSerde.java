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
package org.lolaf.staffix.serde;

import org.lolaf.staffix.api.serde.SerDe;
import lombok.extern.slf4j.Slf4j;

/**
 * The plain String field serde: a fresh String per field.
 *
 * <p>{@link StringThreadLocalSerde} avoids that allocation where the value is only read inside the callback,
 * and {@link StringCachedSerde} where the same values repeat.
 */
@Slf4j
public class StringSerde extends StringBaseSerde {

    private static final StringSerde INSTANCE = new StringSerde();

    public static StringSerde instance() {
        return INSTANCE;
    }

    @Override
    public String deserialize(DeserializationContext serdeContext) {
        return new String(serdeContext.getDeserializationBuffer(), serdeContext.getStartOffset(),
                serdeContext.getLength(), SerDe.CHARSET);
    }
}