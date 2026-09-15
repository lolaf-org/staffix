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

import org.lolaf.staffix.api.serde.DecimalFloat;
import org.lolaf.staffix.api.serde.Hashing;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.collections.Int2ObjectHashMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link org.lolaf.staffix.api.serde.DecimalFloat} serde that returns the same instance for bytes it has seen
 * before, cached per session.
 *
 * <p>Market data repeats: the same price ticks back and forth across thousands of messages, so decoding it once
 * removes an allocation per occurrence rather than per distinct value. Keyed by
 * {@link org.lolaf.staffix.api.session.FixSessionId} so one session's traffic cannot evict another's.
 */
public class DecimalFloatCachedSerde extends AbstractDecimalFloatSerde {

    private static final Map<FixSessionId, DecimalFloatCachedSerde> INSTANCES = new ConcurrentHashMap<>();

    private final Int2ObjectHashMap<DecimalFloat> cache;

    private DecimalFloatCachedSerde() {
        cache = new Int2ObjectHashMap<>(16);
    }

    public static DecimalFloatCachedSerde instance(FixSessionId fixSessionId) {
        return INSTANCES.computeIfAbsent(fixSessionId, sid -> new DecimalFloatCachedSerde());
    }

    @Override
    protected DecimalFloat getInstance(long unscaled, byte scale) {
        return DecimalFloat.of(unscaled, scale);
    }

    @Override
    public DecimalFloat deserialize(DeserializationContext ctx) throws IllegalFieldValueException {
        int contentHash = Hashing.hash(ctx);
        DecimalFloat cached = cache.get(contentHash);
        if (cached == null) {
            cached = super.deserialize(ctx);
            cache.put(contentHash, cached);
        }
        return cached;
    }
}