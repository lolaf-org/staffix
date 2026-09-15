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
import org.lolaf.staffix.api.serde.Hashing;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.collections.Int2ObjectHashMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A String serde that returns the same instance for bytes it has seen before, cached per session.
 *
 * <p>For the fields whose values repeat across a session - symbols, currencies, account ids - where decoding
 * once is worth a map lookup. Results are interned, so two sessions decoding the same symbol also share one
 * String.
 */
public class StringCachedSerde extends StringBaseSerde {

    private static final Map<FixSessionId, StringCachedSerde> INSTANCES = new ConcurrentHashMap<>();

    private final Int2ObjectHashMap<String> cache;

    private StringCachedSerde() {
        cache = new Int2ObjectHashMap<>(16);
    }

    public static StringCachedSerde instance(FixSessionId fixSessionId) {
        return INSTANCES.computeIfAbsent(fixSessionId, sid -> new StringCachedSerde());
    }

    @Override
    public String deserialize(DeserializationContext serdeContext) {
        int contentHash = Hashing.hash(serdeContext);
        String cached = cache.get(contentHash);
        if (cached == null) {
            cached = new String(serdeContext.getDeserializationBuffer(), serdeContext.getStartOffset(),
                    serdeContext.getLength(), SerDe.CHARSET).intern();
            cache.put(contentHash, cached);
        }
        return cached;
    }
}